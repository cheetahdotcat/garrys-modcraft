package dev.gmodcraft.client;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.net.SkyNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.jspecify.annotations.Nullable;

/**
 * Opens (or creates) the void "mirror" world automatically once GMod is connected, or plays on
 * another Minecraft server instead: the one the GMod server announces (protocol v14 JoinInfo on
 * the client link), a friend's world the player picked with /join, or config/gmodcraft.properties.
 * Every join outcome goes back to the host (JoinStatus, kEvJoinResult). Render thread only.
 */
public final class MirrorWorld {
	/**
	 * The preset a NEW world is made with: config/gmodcraft.properties worldType (P8 WP3: mirror,
	 * flat_void_maps or flat_everywhere; the launcher's "New world" writes it). An existing world keeps
	 * its own generator.
	 */
	private static ResourceKey<WorldPreset> preset() {
		String type = dev.gmodcraft.GmodCraftConfig.rules().worldType();
		GmodCraft.LOG.info("GmodCraft: the new world's type: {}", type);
		return ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, type));
	}
	private static boolean attempted;
	private static long lastLog;
	// The server to play on this session (JoinInfo or /join); null: our own world.
	private static @Nullable String sessionJoin;
	// Shown in chat once the player is in a world again (why they're back in their own, ...).
	private static @Nullable String pendingNote;

	// ---- following the host's JoinInfo (v14) ----
	// The last JoinInfo.joinId acted on (kept across link sessions: a rewrite is not a new instruction).
	private static int lastJoinId;
	// The joinId the current join / connection belongs to (0: the player's own /join, or none).
	private static int joinId;
	// The pairing token to hand the server once connected ("" for none).
	private static String joinToken = "";
	// Reached the play phase on sessionJoin (so a later disconnect is "lost", not "failed").
	private static boolean connectedThere;
	// JoinStatus as last written (state, joinId, result, address, reason) and in which session.
	private static int state = Proto.JOIN_STATE_OWN_WORLD;
	private static int lastResult = Proto.JOIN_OK;
	private static String reason = "";
	private static int statusGeneration = -1;

	private MirrorWorld() {
	}

	/**
	 * The address in config/gmodcraft.properties ({@code join=abc-def.e4mc.link}), if any. Written
	 * with the template below the first time, so there's something to fill in.
	 */
	private static @Nullable String joinAddress(Minecraft minecraft) {
		java.nio.file.Path file = minecraft.gameDirectory.toPath().resolve("config").resolve("gmodcraft.properties");
		java.util.Properties props = new java.util.Properties();
		try {
			if (!java.nio.file.Files.exists(file)) {
				java.nio.file.Files.createDirectories(file.getParent());
				java.nio.file.Files.writeString(file, """
					# GmodCraft
					# To play in a friend's world instead of your own: put their address after join=
					# (the link e4mc shows them when they open their world to LAN), then restart Minecraft.
					join=
					""");
			}
			try (var in = java.nio.file.Files.newBufferedReader(file)) {
				props.load(in);
			}
		} catch (java.io.IOException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't read {}", file, e);
			return null;
		}
		String join = props.getProperty("join", "").trim();
		return join.isEmpty() ? null : join;
	}

	/** People paste all sorts: "https://abc-def.e4mc.link/", " abc-def.e4mc.link ". */
	private static String cleanAddress(String link) {
		return link.trim().replaceFirst("^[A-Za-z]+://", "").replaceAll("/+$", "");
	}

	/** /join: leave this world and play in a friend's (their e4mc link, or any server address). */
	public static void joinFriend(Minecraft minecraft, String link) {
		String address = cleanAddress(link);
		if (address.isEmpty()) {
			return;
		}
		GmodCraft.LOG.info("GmodCraft: /join {}", address);
		startJoin(minecraft, address, 0, "");
	}

	/** The friend's world we're in (the address we joined), or null in our own. */
	public static @Nullable String friendAddress(Minecraft minecraft) {
		if (sessionJoin != null) {
			return sessionJoin;
		}
		var server = minecraft.isLocalServer() ? null : minecraft.getCurrentServer();
		return server != null ? server.ip : null;
	}

	/** /leave: back to our own world. */
	public static void leaveFriend(Minecraft minecraft) {
		if (sessionJoin == null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("You're already in your own world."));
			return;
		}
		GmodCraft.LOG.info("GmodCraft: /leave {}", sessionJoin);
		goHome(minecraft, 0, "Back in your own world.");
	}

	private static void startJoin(Minecraft minecraft, String address, int id, String token) {
		cancelConnecting("cancelled: sent to " + address + " instead");
		sessionJoin = address;
		joinId = id;
		joinToken = token;
		connectedThere = false;
		state = Proto.JOIN_STATE_CONNECTING;
		reason = "";
		writeStatus(true);
		leaveWorld(minecraft);
	}

	/** Back to our own world on purpose (the host's empty JoinInfo, or /leave): kJoinLeft. */
	private static void goHome(Minecraft minecraft, int id, String note) {
		// Before cancelConnecting clears it: a connect still in flight must be aborted too.
		boolean away = sessionJoin != null;
		cancelConnecting("cancelled: sent back to its own world");
		sessionJoin = null;
		joinId = id;
		joinToken = "";
		connectedThere = false;
		finish(Proto.JOIN_LEFT, "");
		if (away) {
			pendingNote = note;
			leaveWorld(minecraft);
		}
	}

	/** A join still connecting is superseded: it ends (kJoinFailed, for its own joinId) before the next one starts. */
	private static void cancelConnecting(String why) {
		if (state == Proto.JOIN_STATE_CONNECTING && sessionJoin != null) {
			sessionJoin = null;
			finish(Proto.JOIN_FAILED, why);
		}
	}

	private static void leaveWorld(Minecraft minecraft) {
		attempted = false;
		// A connect still in flight: abort it the way its Cancel button does (no public API for it),
		// or it would finish later and leave us on that server.
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.ConnectScreen connecting) {
			var accessor = (dev.gmodcraft.client.mixin.ConnectScreenAccessor) connecting;
			synchronized (connecting) { // the connect thread takes the same lock
				accessor.gmodcraft$setAborted(true);
				io.netty.channel.ChannelFuture future = accessor.gmodcraft$channelFuture();
				if (future != null) {
					future.cancel(true); // a socket still connecting closes now, not at the timeout
					accessor.gmodcraft$setChannelFuture(null);
				}
				net.minecraft.network.Connection connection = accessor.gmodcraft$connection();
				if (connection != null) {
					connection.disconnect(net.minecraft.client.gui.screens.ConnectScreen.ABORT_CONNECTION);
				}
			}
			GmodCraft.LOG.info("GmodCraft: aborted the connect in progress");
		}
		if (minecraft.level != null) {
			minecraft.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		}
		minecraft.gui.setScreen(new TitleScreen());  // openWhenReady takes it from the title screen
	}

	/** A join ended (or a leave was done): JoinStatus in our own world (unless still joined) + kEvJoinResult. */
	private static void finish(int result, String why) {
		lastResult = result;
		reason = why;
		if (result != Proto.JOIN_BAD_ADDRESS) { // a bad address changes nothing
			state = result == Proto.JOIN_OK ? Proto.JOIN_STATE_JOINED : Proto.JOIN_STATE_OWN_WORLD;
		}
		writeStatus(true);
		ClientLink.INSTANCE.pushJoinResult(joinId, result);
		GmodCraft.LOG.info("GmodCraft: join {} result {}{}", joinId, result, why.isEmpty() ? "" : " (" + why + ")");
	}

	/** JoinStatus: written on every change ({@code force}) and again when the link session is new. */
	private static void writeStatus(boolean force) {
		int generation = ClientLink.INSTANCE.generation();
		if (!force && generation == statusGeneration) {
			return;
		}
		statusGeneration = generation;
		ClientLink.INSTANCE.writeJoinStatus(state, joinId, lastResult, sessionJoin != null ? sessionJoin : "", reason);
	}

	/**
	 * True when {@code address} is this Minecraft's own world opened to LAN (a listen host told to
	 * join itself): its e4mc relay domain (any port), or a local IP literal / localhost on its LAN
	 * port. Never resolves a name (render thread): another host name for this machine isn't caught.
	 */
	private static boolean isOwnWorld(Minecraft minecraft, String address) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || !server.isPublished()) {
			return false;
		}
		var parsed = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address);
		String host = parsed.getHost().replaceAll("\\.$", "");
		String e4mc = dev.gmodcraft.E4mc.domain();
		if (e4mc != null && host.equalsIgnoreCase(e4mc.replaceAll("\\.$", ""))) {
			return true; // the relay forwards to our LAN port whatever port the link names
		}
		if (parsed.getPort() != server.getPort()) {
			return false;
		}
		if (host.equalsIgnoreCase("localhost")) {
			return true;
		}
		String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		if (!com.google.common.net.InetAddresses.isInetAddress(literal)) {
			return false;
		}
		try {
			java.net.InetAddress ip = com.google.common.net.InetAddresses.forString(literal);
			return ip.isLoopbackAddress() || ip.isAnyLocalAddress() || java.net.NetworkInterface.getByInetAddress(ip) != null;
		} catch (java.io.IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Every frame while GMod is linked, before {@link #openWhenReady}: act on a new JoinInfo
	 * instruction (protocol v14). A joinId is acted on once; a failed join is not retried until
	 * the host writes a new one.
	 */
	public static void followHost(Minecraft minecraft) {
		writeStatus(false);
		ClientLink.JoinInfo info = ClientLink.INSTANCE.readJoinInfo();
		if (info == null || info.joinId() == 0 || info.joinId() == lastJoinId) {
			return;
		}
		lastJoinId = info.joinId();
		String address = cleanAddress(info.serverAddress());
		String tokenHash = info.joinToken().isEmpty() ? "none" : SkyNet.tokenHash(info.joinToken());
		if (address.isEmpty()) {
			GmodCraft.LOG.info("GmodCraft: GMod asks to go back to our own world (join {})", info.joinId());
			goHome(minecraft, info.joinId(), "The GMod server sent you back to your own world.");
			return;
		}
		if (!net.minecraft.client.multiplayer.resolver.ServerAddress.isValidAddress(address)) {
			GmodCraft.LOG.warn("GmodCraft: GMod's join {} has a bad server address; staying put", info.joinId());
			int keep = joinId;
			joinId = info.joinId();
			finish(Proto.JOIN_BAD_ADDRESS, "bad server address: " + address);
			joinId = keep;
			return;
		}
		if (isOwnWorld(minecraft, address)) {
			GmodCraft.LOG.warn("GmodCraft: GMod's join {} points at our own open world ({}); staying put", info.joinId(), address);
			int keep = joinId;
			joinId = info.joinId();
			finish(Proto.JOIN_BAD_ADDRESS, "that is this Minecraft's own world: " + address);
			joinId = keep;
			return;
		}
		if (address.equals(sessionJoin) && state == Proto.JOIN_STATE_JOINED && minecraft.level != null) {
			// Already there (the host re-announced it): just adopt the new id and token.
			GmodCraft.LOG.info("GmodCraft: GMod's join {}: already on {} (token {})", info.joinId(), address, tokenHash);
			joinId = info.joinId();
			joinToken = info.joinToken();
			sendToken(minecraft);
			finish(Proto.JOIN_OK, "");
			return;
		}
		GmodCraft.LOG.info("GmodCraft: GMod asks to join {} (join {}, token {})", address, info.joinId(), tokenHash);
		startJoin(minecraft, address, info.joinId(), info.joinToken());
	}

	/** ClientPlayConnectionEvents.JOIN: we're in a world now (a server's, or our own). */
	public static void onJoinedWorld(Minecraft minecraft) {
		if (sessionJoin != null && !minecraft.isLocalServer()) {
			connectedThere = true;
			sendToken(minecraft);
			finish(Proto.JOIN_OK, "");
		} else if (sessionJoin == null && state != Proto.JOIN_STATE_OWN_WORLD) {
			state = Proto.JOIN_STATE_OWN_WORLD;
			writeStatus(true);
		}
	}

	/** Hands the pairing token to the server we just joined (if it has GmodCraft). Never logs the token. */
	private static void sendToken(Minecraft minecraft) {
		if (joinToken.isEmpty() || minecraft.getConnection() == null) {
			return;
		}
		if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SkyNet.JoinToken.TYPE)) {
			GmodCraft.LOG.warn("GmodCraft: {} doesn't run GmodCraft; join token {} not sent", sessionJoin, SkyNet.tokenHash(joinToken));
			return;
		}
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SkyNet.JoinToken(joinToken));
		GmodCraft.LOG.info("GmodCraft: join token {} sent to {}", SkyNet.tokenHash(joinToken), sessionJoin);
	}

	/** Every client tick: a note for the player once they're in a world again. */
	public static void tick(Minecraft minecraft) {
		if (pendingNote != null && minecraft.player != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(pendingNote));
			pendingNote = null;
		}
	}

	public static void openWhenReady(Minecraft minecraft) {
		// Couldn't reach a friend's world, or it closed under us: back to our own, and say why.
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen screen && minecraft.level == null) {
			String why = ((dev.gmodcraft.client.mixin.DisconnectedScreenAccessor) screen).gmodcraft$details().reason().getString();
			pendingNote = sessionJoin != null
				? "Couldn't stay in " + sessionJoin + " (check the link, and that your friend's world is still open to LAN). You're back in your own world."
				: "Disconnected. You're back in your own world.";
			GmodCraft.LOG.info("GmodCraft: disconnected ({}); back to the mirror world", why);
			if (sessionJoin != null) {
				int result = connectedThere ? Proto.JOIN_LOST : Proto.JOIN_FAILED;
				sessionJoin = null;
				connectedThere = false;
				finish(result, why);
			}
			attempted = false;
			minecraft.gui.setScreen(new TitleScreen());
			return;
		}
		if (attempted && minecraft.level == null && minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
			lastLog = System.currentTimeMillis();
			GmodCraft.LOG.info("GmodCraft: still not in the mirror world; current screen {}", minecraft.gui.screen().getClass().getName());
		}
		if (attempted || minecraft.level != null || minecraft.gui.overlay() != null) {
			return;
		}
		// Wait for the menu to settle on the title screen; skip any first-launch prompts in front of it.
		if (!(minecraft.gui.screen() instanceof TitleScreen)) {
			if (minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
				lastLog = System.currentTimeMillis();
				GmodCraft.LOG.info("GmodCraft: waiting on screen {} before opening the mirror world", minecraft.gui.screen().getClass().getName());
			}
			if (minecraft.gui.screen() == null || minecraft.gui.screen().getClass().getName().contains("Onboarding")) {
				minecraft.gui.setScreen(new TitleScreen());
			}
			return;
		}
		TitleScreen title = (TitleScreen) minecraft.gui.screen();
		attempted = true;
		// Multiplayer: the server GMod announced / the player picked, else the config file's (unless
		// the GMod server has taken charge of where we play).
		String join = sessionJoin != null ? sessionJoin : lastJoinId == 0 ? joinAddress(minecraft) : null;
		if (join != null) {
			GmodCraft.LOG.info("GmodCraft: joining {}", join);
			if (sessionJoin == null) {
				sessionJoin = join; // the config file's: tracked like a /join from here on
				state = Proto.JOIN_STATE_CONNECTING;
				writeStatus(true);
			}
			pendingNote = "Joined " + join + ". Type /leave to go back to your own world.";
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(title, minecraft, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(join),
				new net.minecraft.client.multiplayer.ServerData("GmodCraft", join, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
			return;
		}
		if (minecraft.getLevelSource().levelExists(GmodCraft.WORLD_NAME)) {
			GmodCraft.LOG.info("GmodCraft: opening mirror world");
			minecraft.createWorldOpenFlows().openWorld(GmodCraft.WORLD_NAME, () -> minecraft.gui.setScreen(title));
			return;
		}
		GmodCraft.LOG.info("GmodCraft: creating mirror world");
		LevelSettings settings = new LevelSettings(
			GmodCraft.WORLD_NAME,
			GameType.SURVIVAL,
			new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
			true,
			WorldDataConfiguration.DEFAULT
		);
		minecraft.createWorldOpenFlows().createFreshLevel(
			GmodCraft.WORLD_NAME,
			settings,
			new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset()).value().createWorldDimensions(),
			title
		);
	}
}
