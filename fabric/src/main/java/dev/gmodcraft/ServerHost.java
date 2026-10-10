package dev.gmodcraft;

import dev.gmodcraft.combat.SkyCombat;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.mixin.ServerGamePacketListenerAccessor;
import dev.gmodcraft.weapon.GmodWeapons;
import dev.gmodcraft.wire.Bridges;
import dev.gmodcraft.world.BlockDeltas;
import dev.gmodcraft.world.MapSlots;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyWater;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft server's end of the server link. Polls it at the start of every server tick (the
 * link's heartbeat) and then, all on the server thread and never touching client classes (so an
 * integrated and a dedicated server behave the same):
 * <ul>
 * <li>answers the host's map with its slot (McServerState, from {@link MapSlots});</li>
 * <li>keeps the player map both ways: HostPlayers (GMod players with the identity their Minecraft
 * reported) -> SteamID64 of each online Minecraft player, by UUID, else by name (offline / dev);</li>
 * <li>runs host events at the end of the tick: hurt, teleport and respawn requests, and acks each
 * request once the player's client has confirmed the move;</li>
 * <li>sends its blocks to the host ({@link BlockDeltas});</li>
 * <li>(v14) tells the host how other players reach it (McServerInfo: a dedicated server's port, a
 * world opened to LAN on request, e4mc's relay address), takes the host's per-player water grids,
 * credits PvP hurts to the attacking Minecraft player, and keeps each player's join token.</li>
 * <li>(P6b) when it doesn't check Microsoft accounts ({@code !usesAuthentication()}: offline / LAN
 * worlds opened for offline logins), maps a player to a SteamID only after its join token checks
 * out ({@link JoinGate}): single use, with an expiry; the token's hash picks the HostPlayers slot
 * (exactly one must carry it), never the identity the player claims. A missing, wrong, ambiguous,
 * reused or expired token is kicked, and so is a verified player whose GMod player left HostPlayers
 * for longer than the grace period. The integrated server's own player (the listen host: owner UUID
 * and an in-memory connection) is exempt: it never gets a JoinInfo.</li>
 * </ul>
 */
public final class ServerHost {
	private static final ServerLink.ServerState STATE = new ServerLink.ServerState();
	private static final List<ServerLink.HostPlayer> HOST_PLAYERS = new ArrayList<>();
	private static final List<ServerLink.McPlayer> MC_PLAYERS = new ArrayList<>();
	private static final List<ServerPlayer> MC_ENTITY_CENTRES = new ArrayList<>();
	// The same players and SteamIDs, for McWeaponSets (v17 hybrid mode).
	private static final List<ServerPlayer> WEAPON_PLAYERS = new ArrayList<>();
	private static final List<Long> WEAPON_STEAM_IDS = new ArrayList<>();
	// Every slot claiming a Minecraft UUID / lower-case name (several may: claims are unchecked).
	private static volatile Map<UUID, List<ServerLink.HostPlayer>> byUuid = Map.of();
	private static volatile Map<String, List<ServerLink.HostPlayer>> byName = Map.of();
	// HostPlayers as last read, by slot (v14: slot-indexed; steamId 0 = empty slot).
	private static volatile List<ServerLink.HostPlayer> hostSlots = List.of();
	// Per-player water grids (v14) as last read, by slot; WATER_SEQ[i] is the seq they were read at.
	private static final GLink.WaterGrid[] WATER = new GLink.WaterGrid[Proto.MAX_PLAYERS];
	private static final int[] WATER_SEQ = new int[Proto.MAX_PLAYERS];
	private static final long[] WATER_OWNER = new long[Proto.MAX_PLAYERS]; // steamId of the slot when its grid was read
	private static final boolean[] WATER_USED = new boolean[Proto.MAX_PLAYERS];
	private static GLink.@Nullable WaterGrid legacyWater;
	private static int legacyWaterSeq;
	private static int waterGeneration = -1;
	private static String waterLog = "";
	// McServerInfo as written (and in which session), the last open request answered.
	private static ServerLink.@Nullable ServerInfo infoWritten;
	private static int infoGeneration = -1;
	private static int lastOpenRequest;
	private static @Nullable String lanIp;
	// Join tokens players brought (v14 pairing), by Minecraft UUID. Never logged in full.
	private static final Map<UUID, String> JOIN_TOKENS = new HashMap<>();
	// P6b pairing check, and when each unverified player must have shown its token (nanoTime).
	private static final JoinGate GATE = new JoinGate(JoinGate.configuredTtlSeconds(), JoinGate.configuredOrphanGraceSeconds());
	private static final Map<UUID, Long> TOKEN_DEADLINE = new HashMap<>();
	private static final long TOKEN_WAIT_NS = 10_000_000_000L;
	// Whether the server checks tokens (no Microsoft account check), and the integrated server's own
	// player (exempt), as of the last tick start.
	private static volatile boolean gated;
	private static volatile @Nullable UUID ownerUuid;
	private static volatile boolean linked;
	private static volatile int worldId;
	// The slot answer currently published (worldId it answers, or 0).
	private static int answeredWorldId;
	// The published slot's origin (blocks), valid while slotValid (P7: bridges outside it are ignored).
	private static volatile boolean slotValid;
	private static volatile int slotOriginX;
	private static volatile int slotOriginZ;
	private static volatile int slotOriginY;  // v21: the slot's vertical offset (Source units; only the host applies it)
	// v21: a NEW map waits for the host's floor hint (kServerAnchorReady) up to anchorWaitMs.
	private static boolean anchorWaiting;
	private static String anchorWaitFor = "";
	private static long anchorWaitStartNs;
	private static String answeredMap = "";
	private static int answeredGeneration;
	// What McServerState holds now (generation of the segment it was written to, and the fields):
	// written only when this changes. A new session's segment starts zeroed, so it is rewritten.
	private static int writtenGeneration = -1;
	private static int[] written = new int[0];

	/** A teleport / respawn waiting for the player's client to confirm. */
	private record Pending(int requestId, long steamId, UUID player, long deadlineNs, int reason) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	// Players the host is carrying in a seat (last teleport kTeleportReasonVehicle, P6i): GMod drives
	// them and moves Minecraft's player along by teleport, so they fall between moves. No fall damage
	// until a teleport for another reason (the host sends a plain one when they leave the seat), the
	// link drops or they leave. (Floating isn't kicked while linked anyway: the flight mixins.)
	private static final Set<UUID> SEATED = new java.util.HashSet<>();
	// True while we respawn a player ourselves (kHostEvRespawn): no kEvPlayerRespawned for that.
	private static boolean respawningForHost;

	private ServerHost() {
	}

	static void init() {
		ServerTickEvents.START_SERVER_TICK.register(ServerHost::startTick);
		ServerTickEvents.END_SERVER_TICK.register(ServerHost::endTick);
		ServerPlayerEvents.AFTER_RESPAWN.register(ServerHost::afterRespawn);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUUID();
			SEATED.remove(id);
			Noclip.forget(id);
			JOIN_TOKENS.remove(id);
			GATE.forget(id);
			TOKEN_DEADLINE.remove(id);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			TOKEN_DEADLINE.put(handler.player.getUUID(), System.nanoTime() + TOKEN_WAIT_NS));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			JOIN_TOKENS.clear();
			SEATED.clear();
			Noclip.clear();
			GATE.clear();
			TOKEN_DEADLINE.clear();
			E4mc.forget();
			infoWritten = null;
			infoGeneration = -1;
			lastOpenRequest = 0;
		});
		E4mc.init();
		BlockDeltas.init();
		if (DevCommands.ENABLED || dev.gmodcraft.combat.ProjectileTestHook.ENABLED) {
			// Test runs (tools/test_mc.sh): load the player classes now, so their mixins (noclip, fall
			// damage) are applied and checked even when no player ever joins (defaultRequire: a bad
			// injection point fails the run here instead of on the first join).
			try {
				Class.forName("net.minecraft.server.level.ServerPlayer");
				GmodCraft.LOG.info("GmodCraft test: player classes loaded (their mixins applied)");
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Opens an integrated server's world to LAN. Installed by the client (publishing touches the
	 * client: its player's permissions, its key pair), so this class never loads client code.
	 * {@code done} gets whether it worked; it may run on any thread.
	 */
	public interface LanOpener {
		void open(MinecraftServer server, int port, boolean allowOffline, java.util.function.Consumer<Boolean> done);
	}

	private static volatile @Nullable LanOpener lanOpener;

	public static void setLanOpener(LanOpener opener) {
		lanOpener = opener;
	}

	/** True while a live GMod server is on the server link. */
	public static boolean linked() {
		return linked;
	}

	/** The host is carrying this player in a GMod seat (see {@link #SEATED}): no fall damage. */
	public static boolean seatedByHost(ServerPlayer player) {
		return linked && !SEATED.isEmpty() && SEATED.contains(player.getUUID());
	}

	/** v30 (P6i): GMod says this player's Minecraft rides a moving GMod entity (kHostPlayerCarried). */
	public static boolean carriedByHost(ServerPlayer player) {
		if (!linked) {
			return false;
		}
		ServerLink.HostPlayer hp = hostPlayerOf(player);
		return hp != null && hp.carried();
	}

	/** The host's current world (map hash), as last read; 0 before the first read. */
	public static int worldId() {
		return worldId;
	}

	/** The GMod server has Wiremod (ServerState kServerWiremod, as last read). */
	public static boolean wiremod() {
		return linked && STATE.wiremod();
	}

	/** R2 (v25): the GMod server links map entities to bridges (ServerState kServerMapIo). */
	public static boolean mapIo() {
		return linked && STATE.mapIo();
	}

	/** Whether the current map's slot is known; then {@link #slotOriginX()}/{@link #slotOriginZ()} are its origin. */
	public static boolean slotKnown() {
		return linked && slotValid && answeredWorldId == worldId && worldId != 0;
	}

	public static int slotOriginX() {
		return slotOriginX;
	}

	public static int slotOriginZ() {
		return slotOriginZ;
	}

	/** The GMod map the host runs (lowercase, as ServerState says; "" when none). */
	public static String currentMap() {
		return linked && STATE.inGame() ? STATE.mapName : "";
	}

	// P8 WP2: a re-anchor job owns the slot (kMcSrvSlotBusy), and asks for the slot to be answered again.
	private static volatile boolean slotBusy;
	private static volatile boolean reanswer;
	private static @Nullable MinecraftServer currentServer;  // answerSlot's (for the rules part of the answer)

	/** Marks the current slot busy (kMcSrvSlotBusy in McServerState) while a re-anchor runs. */
	public static void setSlotBusy(boolean busy) {
		slotBusy = busy;
		reanswer = true;
	}

	/** Answers McServerState again (the slot table changed: a re-anchor committed a new offset). */
	public static void reanswerSlot() {
		reanswer = true;
	}

	/** v21: the current slot's vertical offset in Source units (what McServerState::originY says). */
	public static int slotOriginY() {
		return slotOriginY;
	}

	/**
	 * Dev (P8 WP1): -Dgmodcraft.forceOy=&lt;units&gt; (else GMODCRAFT_FORCE_OY) answers every map with this
	 * vertical offset (kAnchorForced), without storing it: the harness runs with oy != 0. Null when unset.
	 */
	static @Nullable Integer forcedOy() {
		String v = System.getProperty("gmodcraft.forceOy");
		if (v == null || v.isBlank()) {
			v = System.getenv("GMODCRAFT_FORCE_OY");
		}
		if (v == null || v.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** How long a new map waits for the floor hint: -Dgmodcraft.anchorWaitMs, else GMODCRAFT_ANCHOR_WAIT_MS, else kAnchorWaitMs. */
	static long anchorWaitMs() {
		String v = System.getProperty("gmodcraft.anchorWaitMs");
		if (v == null || v.isBlank()) {
			v = System.getenv("GMODCRAFT_ANCHOR_WAIT_MS");
		}
		long ms = Proto.ANCHOR_WAIT_MS;
		if (v != null && !v.isBlank()) {
			try {
				ms = Long.parseLong(v.trim());
			} catch (NumberFormatException e) {
				ms = Proto.ANCHOR_WAIT_MS;
			}
		}
		return Math.max(0, Math.min(600_000, ms));
	}

	private static void startTick(MinecraftServer server) {
		ServerLink link = ServerLink.INSTANCE;
		link.setTiming(server.getAverageTickTimeNanos() / 1_000_000.0F, 0.0F);
		link.poll();
		gated = !server.usesAuthentication();
		var owner = server.isDedicatedServer() ? null : server.getSingleplayerProfile();
		ownerUuid = owner != null ? owner.id() : null;
		boolean now = link.active();
		if (now != linked) {
			linked = now;
			GmodCraft.LOG.info("GmodCraft: GMod server link {}", now ? "up" : "down");
			if (now) {
				SkyCollision.SERVER.startConsumer();
				// Whoever is already online gets the full wait for its token from now on.
				long deadline = System.nanoTime() + TOKEN_WAIT_NS;
				TOKEN_DEADLINE.replaceAll((id, d) -> Math.max(d, deadline));
			}
		}
		if (!now) {
			SkyWater.SERVER.clear();
			dev.gmodcraft.combat.HeldMcEntities.releaseAll(); // v29 (T2): no link, nothing held
			return;
		}
		if (link.readServerState(STATE)) {
			worldId = STATE.worldId;
			answerSlot(server, link);
		}
		if (link.readHostPlayers(HOST_PLAYERS)) {
			applyHostPlayers(HOST_PLAYERS);
		}
		enforceTokens(server);
		kickOrphans(server);
		refreshWater(link);
		publishServerInfo(server, link, 0, 0, false);
		MC_PLAYERS.clear();
		WEAPON_PLAYERS.clear();
		WEAPON_STEAM_IDS.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			long steamId = steamIdOf(player);
			WEAPON_PLAYERS.add(player);
			WEAPON_STEAM_IDS.add(steamId);
			int flags = (player.isDeadOrDying() ? Proto.MC_PLAYER_DEAD : 0) | (steamId != 0 ? Proto.MC_PLAYER_MAPPED : 0)
				| (isPending(player.getUUID()) ? Proto.MC_PLAYER_HELD : 0) | (player.isInWater() ? Proto.MC_PLAYER_IN_WATER : 0);
			MC_PLAYERS.add(new ServerLink.McPlayer(player.getUUID(), steamId, player.getId(), flags, player.getHealth(), player.getMaxHealth(),
				player.getGameProfile().name()));
		}
		link.writeMcPlayers(MC_PLAYERS);
		// v27 (B1): the mobs, animals, carts and boats near the mapped players, for GMod's proxies.
		MC_ENTITY_CENTRES.clear();
		for (int i = 0; i < WEAPON_PLAYERS.size(); i++) {
			if (WEAPON_STEAM_IDS.get(i) != 0) {
				MC_ENTITY_CENTRES.add(WEAPON_PLAYERS.get(i));
			}
		}
		dev.gmodcraft.combat.HeldMcEntities.tick(server, link); // v29 (T2): pin what GMod holds before it ticks / is listed
		dev.gmodcraft.combat.McEntities.publish(server, link, MC_ENTITY_CENTRES, !WEAPON_PLAYERS.isEmpty());
		GmodWeapons.publish(server, link, WEAPON_PLAYERS, WEAPON_STEAM_IDS); // v17: set i = McPlayers[i]
	}

	/** McServerState: the slot of the map the host runs, re-published when the map or the session changes. */
	private static void answerSlot(MinecraftServer server, ServerLink link) {
		currentServer = server;
		boolean inGame = STATE.inGame() && STATE.worldId != 0;
		int generation = link.generation();
		if (!anchorWaiting && !reanswer && inGame && STATE.worldId == answeredWorldId && STATE.mapName.equals(answeredMap) && generation == answeredGeneration) {
			return;
		}
		reanswer = false;
		answeredGeneration = generation;
		answeredMap = STATE.mapName;
		int flags = server.isDedicatedServer() ? Proto.MC_SRV_DEDICATED : 0;
		slotValid = false;
		if (!inGame) {
			answeredWorldId = 0;
			anchorWaiting = false;
			anchorWaitFor = "";  // a new visit waits from its own start
			writeAnswer(link, generation, flags, 0, 0, 0, 0, 0, 0);
			return;
		}
		MapSlots.Lookup lookup;
		try {
			lookup = MapSlots.find(server, STATE.mapName, STATE.worldId);
			if (lookup == null && dev.gmodcraft.slot.SlotJobs.locked()) {
				// review finding 5: no slot is allocated while a re-anchor runs (its journal would put
				// map_slots.json back without it): answered once the job is over
				answeredWorldId = STATE.worldId;
				anchorWaiting = true;
				writeAnswer(link, generation, flags | Proto.MC_SRV_ANCHOR_WAIT, STATE.worldId, 0, 0, 0, 0, 0);
				return;
			}
			if (lookup == null) {
				// v21: a NEW map. Its slot's vertical offset comes from the host's floor hint; wait for it.
				MapSlots.Anchor anchor = newSlotAnchor(server);
				if (anchor == null) {
					answeredWorldId = STATE.worldId;
					writeAnswer(link, generation, flags | Proto.MC_SRV_ANCHOR_WAIT, STATE.worldId, 0, 0, 0, 0, 0);
					return;  // anchorWaiting: looked at again next tick
				}
				lookup = MapSlots.lookup(server, STATE.mapName, STATE.worldId, anchor);
			}
		} catch (IllegalStateException e) {
			// No slot rather than a wrong one: the host keeps waiting for kMcSrvSlotValid.
			GmodCraft.LOG.error("GmodCraft: no slot for {}", STATE.mapName, e);
			anchorWaiting = false;
			anchorWaitFor = "";
			answeredWorldId = STATE.worldId;
			writeAnswer(link, generation, flags, 0, 0, 0, 0, 0, 0);
			return;
		}
		anchorWaiting = false;
		anchorWaitFor = "";
		answeredWorldId = STATE.worldId;
		MapSlots.Slot slot = lookup.slot();
		int oy = slot.oyUnits();
		int source = slot.anchorSrc();
		Integer forced = forcedOy();
		if (forced != null) {
			oy = forced;
			source = Proto.ANCHOR_FORCED;
		}
		slotOriginX = slot.slotX() * Proto.SLOT_BLOCKS;
		slotOriginZ = slot.slotZ() * Proto.SLOT_BLOCKS;
		slotOriginY = oy;
		slotValid = true;
		flags |= Proto.MC_SRV_SLOT_VALID | (lookup.isNew() ? Proto.MC_SRV_NEW_SLOT : 0) | (slotBusy ? Proto.MC_SRV_SLOT_BUSY : 0);
		if (!writeAnswer(link, generation, flags, STATE.worldId, slot.slotX(), slot.slotZ(), lookup.count(), oy, source)) {
			return;
		}
		GmodCraft.LOG.info("GmodCraft: GMod map {} -> slot ({}, {}), origin ({}, {}) blocks, vertical offset {} units (anchor source {}{})", STATE.mapName,
			slot.slotX(), slot.slotZ(), slot.slotX() * Proto.SLOT_BLOCKS, slot.slotZ() * Proto.SLOT_BLOCKS, oy, source, forced != null ? ", forced" : "");
	}

	/**
	 * A new map's slot anchor from the host's hint (kServerAnchorReady): the floor on kAnchorFloorY
	 * (MapSlots.offsetFor). Null while it isn't there yet and the wait (anchorWaitMs) isn't over; after
	 * it, oyUnits 0 (kAnchorTimeout, logged).
	 */
	private static MapSlots.@Nullable Anchor newSlotAnchor(MinecraftServer server) {
		String key = STATE.mapName + "/" + Integer.toHexString(STATE.worldId);
		long now = System.nanoTime();
		if (!key.equals(anchorWaitFor)) {
			anchorWaitFor = key;
			anchorWaitStartNs = now;
			if (!STATE.anchorReady()) {
				GmodCraft.LOG.info("GmodCraft: new GMod map {}: waiting up to {} ms for the GMod server's floor hint before its slot is made", STATE.mapName,
					anchorWaitMs());
			}
		}
		anchorWaiting = true;
		if (STATE.anchorReady()) {
			if (STATE.anchorSource == Proto.ANCHOR_NONE || !Float.isFinite(STATE.floorZ)) {
				GmodCraft.LOG.info("GmodCraft: new GMod map {}: the GMod server found no floor; the slot gets no vertical offset", STATE.mapName);
				return MapSlots.Anchor.NONE;
			}
			int oy = MapSlots.offsetFor(MapSlots.floorY(server), STATE.floorZ, STATE.minZ, STATE.maxZ);
			float lowY = (STATE.minZ + oy) / 40.0F, highY = (STATE.maxZ + oy) / 40.0F;
			if (lowY < -64 || highY > 320) {
				GmodCraft.LOG.warn("GmodCraft: new GMod map {}: its geometry spans y {} .. {} (outside vanilla's -64 .. 320; the mirror dimension holds it)",
					STATE.mapName, String.format(Locale.ROOT, "%.1f", lowY), String.format(Locale.ROOT, "%.1f", highY));
			}
			return new MapSlots.Anchor(oy, STATE.floorZ, STATE.anchorSource, List.of(STATE.footMinX, STATE.footMinY, STATE.footMaxX, STATE.footMaxY));
		}
		long waitedMs = (now - anchorWaitStartNs) / 1_000_000L;
		if (waitedMs >= anchorWaitMs()) {
			GmodCraft.LOG.warn("GmodCraft: new GMod map {}: no floor hint from the GMod server within {} ms; the slot gets no vertical offset",
				STATE.mapName, waitedMs);
			return new MapSlots.Anchor(0, 0.0F, Proto.ANCHOR_TIMEOUT, null);
		}
		return null;
	}

	/** Writes McServerState unless it already holds exactly this answer (in this session's segment). */
	private static boolean writeAnswer(ServerLink link, int generation, int flags, int worldId, int slotX, int slotZ, int count, int originY,
		int anchorSource) {
		ServerLink.Rules rules = currentServer != null ? GmodCraft.publishedRules(currentServer) : ServerLink.Rules.NONE;
		int[] answer = { flags, worldId, slotX, slotZ, count, originY, anchorSource, rules.flags(), rules.gameMode(), rules.worldType(), rules.floorY(),
			Float.floatToIntBits(rules.damageScale()) };
		if (generation == writtenGeneration && java.util.Arrays.equals(answer, written)) {
			return false;
		}
		writtenGeneration = generation;
		written = answer;
		link.writeMcServerState(flags, worldId, slotX, slotZ, count, originY, anchorSource, rules);
		return true;
	}

	private static void endTick(MinecraftServer server) {
		Noclip.tick(server, linked, ServerHost::hostPlayerOf);
		if (linked) {
			dev.gmodcraft.combat.HeldMcEntities.reapply(); // v29 (T2): carts / boats moved in their own tick
		}
		dev.gmodcraft.combat.HeldMcEntities.tellClients(server); // T2b: clients follow held ones without lag (also "none" after the link drops)
		if (!linked) {
			PENDING.clear();
			SEATED.clear();
			BlockDeltas.tick(server, worldId); // unlinked: only takes the other threads' marks in (bounded, deduped)
			Bridges.tick(server);              // P7: drops what GMod drove
			return;
		}
		// Ack teleports the client already confirmed before new requests can supersede them (a confirmed
		// request must never be reported as timed out).
		checkPending(server);
		ServerLink.INSTANCE.drainHostEvents(ev -> hostEvent(server, ev));
		DevCommands.endOfSequence(); // a dev command is published whole: one still missing text slots is malformed
		dev.gmodcraft.demo.DemoWorld.endOfSequence(); // P7b: likewise an admin command without its text slot
		GmodWeapons.endOfSequence(); // the same for a weapon give (v17)
		dev.gmodcraft.prop.GmodProps.endOfSequence(); // and a prop give (v33)
		checkPending(server);
		BlockDeltas.tick(server, worldId);
		Bridges.tick(server); // P7: after the host events (outputs applied), before the next tick's reads
		dev.gmodcraft.demo.DemoWorld.tick(server); // P7b: announce demo instances again when the session / map changed
		dev.gmodcraft.world.FireCrossover.tick(server); // v28 (F1): MC fire / lava touching GMod props; the per-tick fire budget
	}

	private static void hostEvent(MinecraftServer server, ServerLink.HostEvent ev) {
		if (dev.gmodcraft.demo.DemoWorld.accept(server, ev) || DevCommands.accept(server, ev) || GmodWeapons.accept(server, ev)
			|| dev.gmodcraft.prop.GmodProps.accept(server, ev)) {
			return;
		}
		switch (ev.type()) {
			case Proto.HOST_EV_HURT -> {
				ServerPlayer target = playerBySteamId(server, ev.steamId());
				if (target == null) {
					GmodCraft.LOG.info("GmodCraft: hurt for SteamID {} dropped: no Minecraft player plays as them", Long.toUnsignedString(ev.steamId()));
					return;
				}
				ServerPlayer attacker = playerByEntIndex(server, ev.entId());
				SkyCombat.hurtPlayer(target, ev.code(), Math.max(0.0F, Math.min(ev.a() / 100.0F, 100000.0F)), ev.entId(), ev.flags(),
					attacker != target ? attacker : null);
			}
			case Proto.HOST_EV_HURT_MC_ENTITY -> dev.gmodcraft.combat.McEntities.hurt(server, ev); // v27 (B1)
			case Proto.HOST_EV_TELEPORT, Proto.HOST_EV_RESPAWN -> teleport(server, ev, ev.type() == Proto.HOST_EV_RESPAWN);
			case Proto.HOST_EV_OPEN_TO_LAN -> openToLan(server, ev);
			case Proto.HOST_EV_BRIDGE_OUTPUTS -> Bridges.hostOutputs(server, ev);
			case Proto.HOST_EV_FIRE -> dev.gmodcraft.world.FireCrossover.hostFire(server, ev); // v28 (F1)
			case Proto.HOST_EV_PUNT_MC_ENTITY -> dev.gmodcraft.combat.HeldMcEntities.punt(server, ev); // v29 (T2)
			default -> GmodCraft.LOG.warn("GmodCraft: unknown host event {}", ev.type());
		}
	}

	private static void teleport(MinecraftServer server, ServerLink.HostEvent ev, boolean respawn) {
		ServerLink link = ServerLink.INSTANCE;
		ServerPlayer player = playerBySteamId(server, ev.steamId());
		if (player == null) {
			GmodCraft.LOG.info("GmodCraft: {} {} for SteamID {}: no Minecraft player plays as them", respawn ? "respawn" : "teleport", ev.requestId(),
				Long.toUnsignedString(ev.steamId()));
			link.pushTeleportAck(ev.steamId(), ev.requestId(), Proto.TELEPORT_NO_PLAYER, 0, 0, 0);
			return;
		}
		if (ev.worldId() != 0 && ev.worldId() != worldId) {
			link.pushTeleportAck(ev.steamId(), ev.requestId(), Proto.TELEPORT_WRONG_WORLD, player.getX(), player.getY(), player.getZ());
			return;
		}
		double x = ev.x(), y = ev.y(), z = ev.z();
		// The overworld is the mirror dimension: a player elsewhere (the nether, a respawn point in
		// another dimension) is brought back to it.
		ServerLevel level = server.overworld();
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 2.9e7 || Math.abs(z) > 2.9e7 || y < level.getMinY() - 64
			|| y > level.getMaxY() + 64 || !Float.isFinite(ev.yaw()) || !Float.isFinite(ev.pitch())) {
			link.pushTeleportAck(ev.steamId(), ev.requestId(), Proto.TELEPORT_BAD_POSITION, player.getX(), player.getY(), player.getZ());
			return;
		}
		if (respawn && player.isDeadOrDying()) {
			respawningForHost = true;
			try {
				player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
			} finally {
				respawningForHost = false;
			}
		}
		// One request outstanding per player: Minecraft has a single teleport handshake per
		// connection, so an older one can't be confirmed separately any more. Ack it now.
		replacePending(player, ev.requestId());
		boolean keepLook = (ev.flags() & Proto.TELEPORT_KEEP_LOOK) != 0;
		float yaw = keepLook ? player.getYRot() : ev.yaw();
		float pitch = keepLook ? player.getXRot() : Math.max(-90.0F, Math.min(90.0F, ev.pitch()));
		player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		if (ev.code() == Proto.TELEPORT_REASON_VEHICLE && !respawn) {
			SEATED.add(player.getUUID());
		} else {
			SEATED.remove(player.getUUID());
		}
		PENDING.add(new Pending(ev.requestId(), ev.steamId(), player.getUUID(), System.nanoTime() + Proto.TELEPORT_ACK_TIMEOUT_MS * 1_000_000L, ev.code()));
		// A seat's follow teleports come many times a second while driving: debug only.
		String line = "GmodCraft: {} {} moved {} to {} {} {} (reason {})";
		Object[] args = { respawn ? "respawn" : "teleport", ev.requestId(), player.getPlainTextName(), x, y, z, ev.code() };
		if (ev.code() == Proto.TELEPORT_REASON_VEHICLE && !respawn) {
			GmodCraft.LOG.debug(line, args);
		} else {
			GmodCraft.LOG.info(line, args);
		}
	}

	private static void replacePending(ServerPlayer player, int newRequest) {
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (p.player().equals(player.getUUID())) {
				it.remove();
				ServerLink.INSTANCE.pushTeleportAck(p.steamId(), p.requestId(), Proto.TELEPORT_TIMEOUT, player.getX(), player.getY(), player.getZ());
				GmodCraft.LOG.info("GmodCraft: teleport {} replaced by {} before the client confirmed it: acked as timed out", p.requestId(), newRequest);
			}
		}
	}

	private static boolean isPending(UUID player) {
		for (Pending p : PENDING) {
			if (p.player().equals(player)) {
				return true;
			}
		}
		return false;
	}

	/** Acks every request whose client has confirmed the move (or that timed out, or whose player left). */
	private static void checkPending(MinecraftServer server) {
		long now = System.nanoTime();
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(p.player());
			int result;
			if (player == null) {
				result = Proto.TELEPORT_NO_PLAYER;
			} else if (((ServerGamePacketListenerAccessor) player.connection).gmodcraft$awaitingPositionFromClient() == null) {
				result = Proto.TELEPORT_OK;
			} else if (now > p.deadlineNs()) {
				result = Proto.TELEPORT_TIMEOUT;
			} else {
				continue;
			}
			it.remove();
			double x = player != null ? player.getX() : 0, y = player != null ? player.getY() : 0, z = player != null ? player.getZ() : 0;
			ServerLink.INSTANCE.pushTeleportAck(p.steamId(), p.requestId(), result, x, y, z);
			if (p.reason() == Proto.TELEPORT_REASON_VEHICLE && result == Proto.TELEPORT_OK) {
				GmodCraft.LOG.debug("GmodCraft: teleport {} acked: result {} at {} {} {}", p.requestId(), result, x, y, z);
			} else {
				GmodCraft.LOG.info("GmodCraft: teleport {} acked: result {} at {} {} {}", p.requestId(), result, x, y, z);
			}
		}
	}

	/** Minecraft respawned a player by itself (death screen / immediate respawn): tell the host. */
	private static void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (alive || respawningForHost || !linked) {
			return;
		}
		long steamId = steamIdOf(newPlayer);
		ServerLink.INSTANCE.pushEvent(Proto.EV_PLAYER_RESPAWNED, 0, steamId, (float) newPlayer.getX(), (float) newPlayer.getY(), (float) newPlayer.getZ(), 0.0F, 0);
		GmodCraft.LOG.info("GmodCraft: {} respawned in Minecraft (SteamID {})", newPlayer.getPlainTextName(), Long.toUnsignedString(steamId));
	}

	/**
	 * The SteamID64 of the GMod player playing as this Minecraft player, or 0 if the host doesn't
	 * list them: the HostPlayer whose Minecraft reported this UUID, else (offline / dev servers,
	 * whose offline UUIDs differ from what the client reported) the one with this name. On a server
	 * that doesn't check accounts (P6b) only a player whose join token checked out is mapped (the
	 * integrated server's own player excepted).
	 */
	public static long steamIdOf(ServerPlayer player) {
		ServerLink.HostPlayer p = hostPlayerOf(player);
		return p != null ? p.steamId() : 0L;
	}

	/** The GMod entity index of the GMod player playing as this Minecraft player, or 0. */
	public static int entIndexOf(ServerPlayer player) {
		ServerLink.HostPlayer p = hostPlayerOf(player);
		return p != null ? p.entIndex() : 0;
	}

	/**
	 * A player whose join token checked out plays as that token's slot's SteamID (in either mode).
	 * Otherwise: on a server that checks accounts, the identity it claims (UUID, else name); on one
	 * that doesn't, nothing, except the integrated server's own player.
	 */
	private static ServerLink.@Nullable HostPlayer hostPlayerOf(ServerPlayer player) {
		long steamId = GATE.verifiedSteamId(player.getUUID());
		if (steamId != 0) {
			return slotOf(steamId);
		}
		if (gated && !isOwner(player)) {
			return null;
		}
		return claimedHostPlayer(player);
	}

	private static ServerLink.@Nullable HostPlayer slotOf(long steamId) {
		for (ServerLink.HostPlayer p : hostSlots) {
			if (p.steamId() == steamId) {
				return p;
			}
		}
		return null;
	}

	/**
	 * The HostPlayer this player's identity points at (UUID, else name), verified or not. Only for the
	 * owner and for servers that check accounts (Microsoft vouches for the Minecraft UUID there, but
	 * not for the GMod player claiming it); never part of the token check. A claim several slots make
	 * resolves by {@link JoinGate#resolveClaim} (listen server: the host's slot; else nobody).
	 */
	private static ServerLink.@Nullable HostPlayer claimedHostPlayer(ServerPlayer player) {
		List<ServerLink.HostPlayer> claims = byUuid.get(player.getUUID());
		if (claims == null) {
			claims = byName.get(player.getGameProfile().name().toLowerCase(Locale.ROOT));
		}
		// An integrated MC server (it has an owner) is taken to be a GMod listen server's, whose host is
		// entity 1. An integrated MC server serving a separate srcds on the same box is unsupported.
		return JoinGate.resolveClaim(claims, ownerUuid != null);
	}

	/**
	 * The integrated server's own player (the listen host): its UUID is the singleplayer profile's
	 * AND it is connected in memory. The UUID alone isn't enough, since an offline UUID follows from
	 * the name, which anyone can pick; vanilla checks only the name. A dedicated server has no
	 * singleplayer profile (ownerUuid stays null), so there nobody is owner-exempt and every player,
	 * including whoever runs the GMod server, needs a join token. That is intended.
	 */
	private static boolean isOwner(ServerPlayer player) {
		UUID owner = ownerUuid;
		return owner != null && owner.equals(player.getUUID())
			&& ((dev.gmodcraft.mixin.ServerCommonPacketListenerAccessor) player.connection).gmodcraft$connection().isMemoryConnection();
	}

	/**
	 * The online Minecraft player of the GMod player with entity index {@code entIndex} (a HostPlayers
	 * record with a Minecraft identity), or null: not a paired player, or not on this server.
	 */
	public static @Nullable ServerPlayer playerByEntIndex(MinecraftServer server, int entIndex) {
		if (entIndex == 0) {
			return null;
		}
		for (ServerLink.HostPlayer p : hostSlots) {
			if (p.entIndex() == entIndex && p.steamId() != 0 && p.hasMc()) {
				return playerBySteamId(server, p.steamId());
			}
		}
		return null;
	}

	// ---- join tokens (v14 pairing, P6b check) ---------------------------------------------

	/** A player's client handed over the join token its GMod client got (SkyNet.JoinToken). Server thread. */
	public static void joinToken(ServerPlayer player, String token) {
		if (token.isEmpty()) {
			return;
		}
		String hash = dev.gmodcraft.net.SkyNet.tokenHash(token);
		JOIN_TOKENS.put(player.getUUID(), token);
		GmodCraft.LOG.info("GmodCraft: {} brought join token {}", player.getPlainTextName(), hash);
		if (isOwner(player)) {
			return; // the listen host plays in its own world: nothing to check
		}
		// The GMod server writes the slot's hash before it sends the token: read the newest list, so
		// a token that overtook our last tick's read isn't refused.
		refreshHostPlayers();
		boolean wasVerified = GATE.isVerified(player.getUUID());
		// The token picks the slot; the identity the player (or any slot) claims plays no part.
		JoinGate.Result r = GATE.verify(player.getUUID(), hostSlots, token, System.nanoTime());
		if (!gated) {
			// Online mode: Microsoft vouches for the UUID, so the UUID-first mapping stays, unless a
			// token checks out: then the token's slot wins (and a mismatch with the UUID's is logged).
			ServerLink.HostPlayer claimed = claimedHostPlayer(player);
			if (r != JoinGate.Result.OK) {
				GmodCraft.LOG.warn("GmodCraft: {}'s join token {} doesn't check out ({}); online mode, mapping by UUID kept", player.getPlainTextName(),
					hash, r.why);
			} else if (claimed != null && claimed.steamId() != GATE.verifiedSteamId(player.getUUID())) {
				GmodCraft.LOG.warn("GmodCraft: {}'s join token {} belongs to SteamID {}, its UUID to SteamID {}; online mode, trusting the token",
					player.getPlainTextName(), hash, Long.toUnsignedString(GATE.verifiedSteamId(player.getUUID())), Long.toUnsignedString(claimed.steamId()));
			}
			return;
		}
		if (r == JoinGate.Result.OK) {
			TOKEN_DEADLINE.remove(player.getUUID());
			GmodCraft.LOG.info("GmodCraft: {}'s join token {} verified: plays as SteamID {}", player.getPlainTextName(), hash,
				Long.toUnsignedString(GATE.verifiedSteamId(player.getUUID())));
			return;
		}
		if (wasVerified) {
			// Already paired by an earlier token (a re-announce whose token doesn't fit): keep the
			// pairing, don't kick a player who proved itself.
			GmodCraft.LOG.warn("GmodCraft: {}'s new join token {} doesn't check out ({}); keeping its earlier pairing", player.getPlainTextName(), hash,
				r.why);
			return;
		}
		kick(player, r.why, hash);
	}

	/** On a server that doesn't check accounts, players who never showed a valid token in time are kicked. */
	private static void enforceTokens(MinecraftServer server) {
		if (!gated || TOKEN_DEADLINE.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			Long deadline = TOKEN_DEADLINE.get(player.getUUID());
			if (deadline == null || now < deadline) {
				continue;
			}
			TOKEN_DEADLINE.remove(player.getUUID());
			if (isOwner(player) || GATE.isVerified(player.getUUID())) {
				continue;
			}
			kick(player, "no join token arrived within " + TOKEN_WAIT_NS / 1_000_000_000L + " s", "none");
		}
	}

	/**
	 * Verified players whose GMod player left HostPlayers (quit GMod) more than the grace period ago
	 * are kicked: their pairing has nobody behind it any more. Gated servers only, like every kick
	 * here (on a server that checks accounts the player just goes unmapped).
	 */
	private static void kickOrphans(MinecraftServer server) {
		List<UUID> orphans = GATE.orphans(System.nanoTime());
		if (orphans.isEmpty()) {
			return;
		}
		for (UUID id : orphans) {
			GATE.forget(id);
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && gated && !isOwner(player)) {
				kick(player, "your GMod player left the GMod server (no pairing for " + GATE.orphanGraceSeconds() + " s)", "none");
			}
		}
	}

	private static void kick(ServerPlayer player, String why, String hash) {
		TOKEN_DEADLINE.remove(player.getUUID());
		GmodCraft.LOG.warn("GmodCraft: kicking {} ({}): {} (token {}); this server doesn't check accounts, so players need the join token their GMod "
			+ "server gave them", player.getPlainTextName(), player.getUUID(), why, hash);
		player.connection.disconnect(net.minecraft.network.chat.Component.literal("Garry's Modcraft: " + why
			+ ". Join through your GMod server (it hands your Minecraft a join token)."));
	}

	private static void refreshHostPlayers() {
		List<ServerLink.HostPlayer> fresh = new ArrayList<>();
		if (ServerLink.INSTANCE.readHostPlayers(fresh)) {
			applyHostPlayers(fresh);
		}
	}

	/** A good read of HostPlayers: the UUID / name lookups, the slots, and the token bookkeeping. */
	private static void applyHostPlayers(List<ServerLink.HostPlayer> list) {
		Map<UUID, List<ServerLink.HostPlayer>> uuids = new HashMap<>();
		Map<String, List<ServerLink.HostPlayer>> names = new HashMap<>();
		for (ServerLink.HostPlayer p : list) {
			if (!p.hasMc()) {
				continue; // no Minecraft identity reported yet: whatever the record holds isn't one
			}
			if (p.mcUuid() != null) {
				uuids.computeIfAbsent(p.mcUuid(), k -> new ArrayList<>(1)).add(p);
			}
			if (!p.mcName().isEmpty()) {
				names.computeIfAbsent(p.mcName().toLowerCase(Locale.ROOT), k -> new ArrayList<>(1)).add(p);
			}
		}
		byUuid = uuids;
		byName = names;
		logClaimConflicts(uuids, names);
		hostSlots = List.copyOf(list);
		GATE.observe(list, System.nanoTime());
	}

	private static String claimConflicts = "";

	/** Logs (once per change) the Minecraft identities several HostPlayers slots claim at once. */
	private static void logClaimConflicts(Map<UUID, List<ServerLink.HostPlayer>> uuids, Map<String, List<ServerLink.HostPlayer>> names) {
		List<String> out = new ArrayList<>();
		for (var e : uuids.entrySet()) {
			if (e.getValue().size() > 1) {
				out.add("UUID " + e.getKey() + " by " + steamIds(e.getValue()));
			}
		}
		for (var e : names.entrySet()) {
			if (e.getValue().size() > 1) {
				out.add("name " + e.getKey() + " by " + steamIds(e.getValue()));
			}
		}
		java.util.Collections.sort(out);
		String now = String.join("; ", out);
		if (!now.equals(claimConflicts)) {
			claimConflicts = now;
			if (!now.isEmpty()) {
				GmodCraft.LOG.warn("GmodCraft: Minecraft identity claimed by several GMod players: {}; {}", now,
					ownerUuid != null ? "listen server: only the host's slot (entity 1) keeps it" : "nobody is mapped by it");
			}
		}
	}

	private static String steamIds(List<ServerLink.HostPlayer> slots) {
		List<String> ids = new ArrayList<>();
		for (ServerLink.HostPlayer p : slots) {
			ids.add(Long.toUnsignedString(p.steamId()) + " (ent " + p.entIndex() + ")");
		}
		return String.join(", ", ids);
	}

	/** The join token this player brought (kept for verifying the pairing; never log it), or null. */
	public static @Nullable String joinTokenOf(UUID player) {
		return JOIN_TOKENS.get(player);
	}

	// ---- per-player water (v14) ------------------------------------------------------------

	/**
	 * The host's water for this tick: grid i for every HostPlayers slot i in use (on the current
	 * map), plus the deprecated single grid as slot 0's while slot 0's own grid is unwritten.
	 * Grids are re-read only when their seq moved; a torn read keeps the previous copy.
	 */
	private static void refreshWater(ServerLink link) {
		int generation = link.generation();
		if (generation != waterGeneration) {
			waterGeneration = generation;
			java.util.Arrays.fill(WATER, null);
			java.util.Arrays.fill(WATER_SEQ, 0);
			java.util.Arrays.fill(WATER_OWNER, 0L);
			java.util.Arrays.fill(WATER_USED, false);
			legacyWater = null;
			legacyWaterSeq = 0;
		}
		List<GLink.WaterGrid> live = new ArrayList<>();
		StringBuilder used = new StringBuilder();
		List<ServerLink.HostPlayer> slots = hostSlots;
		for (int i = 0; i < Math.min(slots.size(), Proto.MAX_PLAYERS); i++) {
			long owner = slots.get(i).steamId();
			if (owner != WATER_OWNER[i]) {
				// The slot changed hands: the old player's grid isn't the new one's. Keep the seq it was
				// last read at: a grid written since (the host may already have written the new owner's)
				// is read, the old player's copy is not.
				boolean first = !WATER_USED[i]; // never owned this session: whatever is there is the new owner's
				WATER_USED[i] |= owner != 0;
				WATER_OWNER[i] = owner;
				WATER[i] = null;
				WATER_SEQ[i] = first ? 0 : WATER_SEQ[i];
			}
			if (owner == 0) {
				continue;
			}
			int seq = link.waterGridSeq(i);
			if (seq != 0 && seq != WATER_SEQ[i] && (seq & 1) == 0) {
				GLink.WaterGrid g = link.readWaterGrid(i);
				if (g != null) {
					WATER[i] = g;
					WATER_SEQ[i] = seq;
				}
			}
			GLink.WaterGrid g = WATER[i];
			if (g != null && g.worldId == worldId) {
				live.add(g);
				used.append(used.isEmpty() ? "" : ", ").append(i);
			}
		}
		if (link.waterGridSeq(0) == 0) {
			int seq = link.legacyWaterGridSeq();
			if (seq != 0 && seq != legacyWaterSeq && (seq & 1) == 0) {
				GLink.WaterGrid g = link.readWaterGrid();
				if (g != null) {
					legacyWater = g;
					legacyWaterSeq = seq;
				}
			}
			if (legacyWater != null) {
				live.add(legacyWater);
				used.append(used.isEmpty() ? "" : ", ").append("legacy");
			}
		}
		SkyWater.SERVER.setGrids(live);
		String now = used.toString();
		if (!now.equals(waterLog)) {
			waterLog = now;
			GmodCraft.LOG.info("GmodCraft: host water grids live: [{}]", now);
		}
	}

	// ---- McServerInfo and opening the world (v14) ------------------------------------------

	/** What McServerInfo should say right now. */
	private static ServerLink.ServerInfo currentInfo(MinecraftServer server) {
		boolean dedicated = server.isDedicatedServer();
		boolean open = dedicated || server.isPublished();
		int port = open ? server.getPort() : 0;
		String e4mc = open ? E4mc.domain() : null;
		int flags = (open ? Proto.SRV_INFO_OPEN : 0) | (dedicated ? Proto.SRV_INFO_DEDICATED : 0)
			| (server.usesAuthentication() ? Proto.SRV_INFO_ONLINE_MODE : 0) | (E4mc.installed() ? Proto.SRV_INFO_E4MC_INSTALLED : 0)
			| (e4mc != null ? Proto.SRV_INFO_E4MC_READY : 0);
		String lan = open && port > 0 ? lanIp(server) + ":" + port : "";
		return new ServerLink.ServerInfo(flags, port, lastOpenRequest, server.getMaxPlayers(), lan, e4mc != null ? e4mc : "");
	}

	/**
	 * Writes McServerInfo and announces it (kEvWorldOpened) when it changed, the session is new, or
	 * this answers an open request ({@code reply}: requestId + OpenResult). Server thread.
	 */
	private static void publishServerInfo(MinecraftServer server, ServerLink link, int requestId, int result, boolean reply) {
		if (!link.active()) {
			return;
		}
		ServerLink.ServerInfo info = currentInfo(server);
		int generation = link.generation();
		boolean changed = generation != infoGeneration || !info.equals(infoWritten);
		if (!changed && !reply) {
			return;
		}
		infoGeneration = generation;
		infoWritten = info;
		link.writeMcServerInfo(info);
		link.pushWorldOpened(reply ? requestId : 0, reply ? result : 0, info.port(), info.flags());
		if (changed) {
			GmodCraft.LOG.info("GmodCraft: server info: {} port {} lan {} e4mc {} (flags {}, max {} players)", (info.flags() & Proto.SRV_INFO_OPEN) != 0
				? "open" : "closed", info.port(), info.lanAddress().isEmpty() ? "-" : info.lanAddress(), info.e4mcAddress().isEmpty() ? "-" : info.e4mcAddress(),
				info.flags(), info.maxPlayers());
		}
	}

	/** kHostEvOpenToLan: open the integrated server's world to other players and answer with kEvWorldOpened. */
	private static void openToLan(MinecraftServer server, ServerLink.HostEvent ev) {
		int requestId = ev.requestId();
		boolean allowOffline = (ev.flags() & Proto.OPEN_ALLOW_OFFLINE) != 0;
		if (server.isDedicatedServer() || server.isPublished()) {
			if (allowOffline && !server.isDedicatedServer()) {
				server.setUsesAuthentication(false);
			}
			lastOpenRequest = requestId;
			GmodCraft.LOG.info("GmodCraft: open-to-LAN request {}: already open on port {}", requestId, server.getPort());
			publishServerInfo(server, ServerLink.INSTANCE, requestId, Proto.OPEN_ALREADY_OPEN, true);
			return;
		}
		LanOpener opener = lanOpener;
		int port = ev.a() > 0 && ev.a() <= 65535 ? ev.a() : 0;
		if (opener == null) {
			lastOpenRequest = requestId;
			GmodCraft.LOG.warn("GmodCraft: open-to-LAN request {}: this server can't be opened (no client)", requestId);
			publishServerInfo(server, ServerLink.INSTANCE, requestId, Proto.OPEN_FAILED, true);
			return;
		}
		GmodCraft.LOG.info("GmodCraft: open-to-LAN request {}: port {}{}", requestId, port == 0 ? "any" : port, allowOffline ? ", offline logins allowed" : "");
		opener.open(server, port, allowOffline, ok -> server.execute(() -> {
			lastOpenRequest = requestId;
			GmodCraft.LOG.info("GmodCraft: open-to-LAN request {}: {} (port {})", requestId, ok ? "opened" : "FAILED", server.isPublished() ? server.getPort() : 0);
			publishServerInfo(server, ServerLink.INSTANCE, requestId, ok ? Proto.OPEN_OK : Proto.OPEN_FAILED, true);
		}));
	}

	/** The address other machines on the LAN reach this one at: server-ip if set, else the first site-local IPv4. */
	private static String lanIp(MinecraftServer server) {
		String configured = server.getLocalIp();
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		String cached = lanIp;
		if (cached != null) {
			return cached;
		}
		String found = "127.0.0.1";
		try {
			search:
			for (var nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
				if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
					continue;
				}
				for (var addr : java.util.Collections.list(nif.getInetAddresses())) {
					if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
						found = addr.getHostAddress();
						break search;
					}
				}
			}
		} catch (java.net.SocketException | RuntimeException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't list network interfaces: {}", e.toString());
		}
		lanIp = found;
		return found;
	}

	/** The Minecraft player a GMod player plays as, or null (not listed by the host, or not online). */
	public static @Nullable ServerPlayer playerBySteamId(MinecraftServer server, long steamId) {
		if (steamId == 0) {
			return null;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (steamIdOf(player) == steamId) {
				return player;
			}
		}
		return null;
	}
}
