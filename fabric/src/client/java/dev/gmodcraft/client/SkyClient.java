package dev.gmodcraft.client;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.client.mixin.AbstractContainerScreenAccessor;
import dev.gmodcraft.client.mixin.CreativeSlotWrapperAccessor;
import dev.gmodcraft.client.render.WorldExporter;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;

/**
 * Per-frame glue between the Minecraft client and the GMod client (the client link). Everything
 * here runs on the render thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("gmodcraft.showWindow");
	// Started by GMod (GmodCraft's Prism instance passes -Dgmodcraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while GMod is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once GMod is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("gmodcraft.startHidden");
	private static boolean startedHidden;

	private static final ClientLink.HostState sky = new ClientLink.HostState();
	private static final ClientLink.McState mc = new ClientLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Hold state: after joining, a respawn or a server teleport (the GMod server moves players with
	// kHostEvTeleport on the server link; this client just follows the MC server's position packet),
	// the player is held where the server put it until GMod's collision there has arrived.
	private static int teleportCount;
	private static long teleportLoggedAt;
	private static int teleportsUnlogged;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean hostStalled;
	private static int exporterErrors;
	// Debug panel timing (LinkStats): last frame and last client tick, ms.
	private static long lastFrameNs;
	private static float frameMs;
	private static long tickStartNs;
	private static float tickMs;
	// McIdentity as last written (rewritten when it changes or the session does).
	private static String identityWritten = "";
	private static int identityGeneration;

	private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once GMod has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if GMod closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	/** Started by GMod's launcher (-Dgmodcraft.startHidden=true): its window is never the user's. */
	public static boolean startHidden() {
		return START_HIDDEN;
	}

	// I1: who owns the system mouse (InputConstantsMixin), and how often a grab was refused.
	private static final dev.gmodcraft.link.MouseOwner MOUSE = new dev.gmodcraft.link.MouseOwner();
	private static long mouseLoggedAt;

	public static dev.gmodcraft.link.MouseOwner mouse() {
		return MOUSE;
	}

	/** InputConstantsMixin refused a system mouse grab / release (logged at most every 5 s). */
	public static void mouseBlocked(String what) {
		int n = MOUSE.blockedOne();
		long now = System.currentTimeMillis();
		if (now - mouseLoggedAt > 5000) {
			mouseLoggedAt = now;
			GmodCraft.LOG.info("GmodCraft: system mouse {} refused (GMod owns the mouse; startHidden {}, linked {}; {} so far)", what, START_HIDDEN,
				linked, n);
		}
	}

	/**
	 * The window was just created (WindowMixin): a Minecraft started for GMod hides it at once, so it
	 * never takes the focus from GMod while it loads (it used to show until the first frame).
	 */
	public static void windowCreated(long handle) {
		// Every window made (a backend that fails after making one may be followed by another).
		if (START_HIDDEN && !SHOW_WINDOW && handle != 0L) {
			windowHidden = true;
			SDLVideo.SDL_HideWindow(handle);
			GmodCraft.LOG.info("GmodCraft: game window hidden on creation (started for GMod)");
		}
	}

	public static ClientLink.HostState sky() {
		return sky;
	}

	/** The player is held in place (collision not there yet, or GMod gone): nothing else moves it. */
	public static boolean holding() {
		return holdPos != null || unlinkedHold != null;
	}

	/** Start of Minecraft.runTick: pull state and input from GMod before anything else runs. */
	public static void beginFrame() {
		long frameNow = System.nanoTime();
		if (lastFrameNs != 0) {
			frameMs = (frameNow - lastFrameNs) / 1e6F;
		}
		lastFrameNs = frameNow;
		ClientLink.INSTANCE.setTiming(tickMs, frameMs);
		ClientLink.INSTANCE.poll();
		quitWithHost(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = ClientLink.INSTANCE.active();
		if (nowLinked) {
			ClientLink.INSTANCE.readHostState(sky); // on a torn read we simply keep last frame's state
			dev.gmodcraft.world.SkyWater.CLIENT.refresh();
			hostGoneSince = 0;
		} else {
			dev.gmodcraft.world.SkyWater.CLIENT.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			GmodCraft.LOG.info("GmodCraft: GMod client link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				takeOverMouse(Minecraft.getInstance());
				SkyCollision.CLIENT.startConsumer();
				applyLinkedOptions();
				var window = Minecraft.getInstance().getWindow();
				FrameExporter.warmUp(window.getWidth(), window.getHeight());
			} else {
				InputBridge.releaseAll();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.followHost(minecraft);
		MirrorWorld.openWhenReady(minecraft);
		publishIdentity(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		// A new player object means we just joined or respawned: hold it where the server put it
		// until GMod's collision there has arrived (the GMod server then moves it, see onServerTeleport).
		if (player != lastPlayer) {
			lastPlayer = player;
			holdPos = player.position();
			holdSince = 0;
			CarryClient.stop();
		}

		// Look direction is driven by GMod (zero-latency camera); MC uses it for everything else.
		if (minecraft.gui.screen() == null) {
			player.setYRot(sky.yaw);
			player.setXRot(sky.pitch);
			player.yRotO = sky.yaw;
			player.xRotO = sky.pitch;
		}
	}

	/**
	 * The MC server moved the local player (ClientPacketListener.handleMovePlayer, render thread):
	 * a teleport or respawn the GMod server asked for. Hold the player at the new place until
	 * GMod's collision around it has arrived, and tell the host not to interpolate across it.
	 */
	public static void onServerTeleport(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		teleportCount++;
		holdPos = player.position();
		holdSince = 0;
		CarryClient.stop();
		// At most once a second (a GMod seat moves the player along many times a second, P6i).
		long now = System.currentTimeMillis();
		if (now - teleportLoggedAt >= 1000) {
			GmodCraft.LOG.info("GmodCraft: the server moved the player to {} {} {} (move {}{})", String.format("%.2f", holdPos.x),
				String.format("%.2f", holdPos.y), String.format("%.2f", holdPos.z), teleportCount,
				teleportsUnlogged > 0 ? ", " + teleportsUnlogged + " more since the last line" : "");
			teleportLoggedAt = now;
			teleportsUnlogged = 0;
		} else {
			teleportsUnlogged++;
		}
	}

	/** McIdentity: who this Minecraft is, so the GMod server can map its player to this one. */
	private static void publishIdentity(Minecraft minecraft) {
		var user = minecraft.getUser();
		int flags = 0;
		if (minecraft.player != null && minecraft.level != null) {
			flags |= Proto.ID_IN_WORLD;
		}
		// A Mojang profile id is a random (version 4) UUID; offline / dev profiles get a name-based
		// (version 3) one. Launchers don't reliably pass an xuid or client id (Prism doesn't).
		if (user.getProfileId() != null && user.getProfileId().version() == 4) {
			flags |= Proto.ID_ONLINE_ACCOUNT;
		}
		if (minecraft.getSingleplayerServer() != null) {
			flags |= Proto.ID_INTEGRATED_SERVER;
		}
		java.util.UUID uuid = user.getProfileId();
		String key = flags + "/" + uuid + "/" + user.getName();
		int generation = ClientLink.INSTANCE.generation();
		if (key.equals(identityWritten) && generation == identityGeneration) {
			return;
		}
		identityWritten = key;
		identityGeneration = generation;
		ClientLink.INSTANCE.writeIdentity(flags, uuid, user.getName());
		GmodCraft.LOG.info("GmodCraft: identity {} ({}) flags {}", user.getName(), uuid, flags);
	}

	/** Start of every client tick (for the debug panel's tick time). */
	public static void clientTickStart(Minecraft minecraft) {
		tickStartNs = System.nanoTime();
	}

	// Minecraft is started for GMod, so it goes when that GMod has closed for good: saved and shut
	// down the normal way (QuitPolicy, L2). The process rule below applies to a Minecraft started hidden
	// (-Dgmodcraft.startHidden=true, the GmodCraft instance); a visible one keeps the timers. Minecraft runs on the host, which sees GMod's process even
	// when GMod runs in Steam's container: while a GMod process runs (map change, disconnected to the
	// menu, a hang) Minecraft stays for the next join; once none runs it quits 15 s after the link went
	// down (30 s when the discovery files were left behind: a crash). Without /proc the old timers apply
	// (60 s with the discovery files gone, else 120 s).
	// -Dgmodcraft.quitWithHost=false keeps running instead (development, debugging).
	private static final boolean QUIT_WITH_HOST = Boolean.parseBoolean(System.getProperty("gmodcraft.quitWithHost", "true"));
	private static final java.util.Set<String> HOST_NAMES = dev.gmodcraft.link.QuitPolicy.hostNames();
	private static long hostGoneSince;
	private static long nextHostCheck;
	// Started hidden by the host but never connected: nobody can see or use this Minecraft, and it
	// would stop the next GMod from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	private static void quitWithHost(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (QUIT_WITH_HOST && START_HIDDEN && !tookOver && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			GmodCraft.LOG.warn("GmodCraft: started hidden but GMod never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_HOST || !tookOver || linked || now < nextHostCheck) {
			return;
		}
		nextHostCheck = now + 1000;
		if (hostGoneSince == 0) {
			hostGoneSince = now;
			return;
		}
		boolean discoveryGone = ClientLink.INSTANCE.override() == null && !java.nio.file.Files.exists(GLink.discoveryDir().resolve("client.json"))
			&& !java.nio.file.Files.exists(GLink.discoveryDir().resolve("server.json"));
		// Only a Minecraft started hidden for GMod follows its process; a visible one the user plays keeps
		// the old timers (60 s with the discovery files gone, else 120 s).
		dev.gmodcraft.link.QuitPolicy.Host host = START_HIDDEN ? dev.gmodcraft.link.QuitPolicy.probe(java.nio.file.Path.of("/proc"), HOST_NAMES)
			: dev.gmodcraft.link.QuitPolicy.Host.UNKNOWN;
		long gone = now - hostGoneSince;
		if (dev.gmodcraft.link.QuitPolicy.shouldQuit(QUIT_WITH_HOST, tookOver, linked, gone, host, discoveryGone)) {
			GmodCraft.LOG.info("GmodCraft: GMod has closed (no heartbeat for {} s; GMod process {}; discovery files {}); saving and quitting",
				gone / 1000, host == dev.gmodcraft.link.QuitPolicy.Host.GONE ? "gone" : "unknown", discoveryGone ? "gone" : "left behind");
			minecraft.stop();
		}
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		MirrorWorld.tick(minecraft);
		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		PlayerPushOut.tick(minecraft, holdPos != null || unlinkedHold != null);
		publishTick(minecraft);
		if (tickStartNs != 0) {
			tickMs = (System.nanoTime() - tickStartNs) / 1e6F;
		}
	}

	/**
	 * GMod went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (a map change), so keep the player exactly where they were
	 * instead of letting them fall; GMod puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(unlinkedHold.x, unlinkedHold.y, unlinkedHold.z);
		player.xo = unlinkedHold.x;
		player.yo = unlinkedHold.y;
		player.zo = unlinkedHold.z;
		player.resetFallDistance();
	}

	/**
	 * Hands GMod the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * CLOCK_MONOTONIC timestamp. The host interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		mc.tickNs = GLink.nowNs() - (long) (remainder * tickMs * 1_000_000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
		var avatar = player.avatarState();
		mc.walkDistO = bob ? avatar.getInterpolatedWalkDistance(0.0F) : 0.0F;
		mc.walkDist = bob ? avatar.getInterpolatedWalkDistance(1.0F) : 0.0F;
		mc.bobO = bob ? avatar.getInterpolatedBob(0.0F) : 0.0F;
		mc.bob = bob ? avatar.getInterpolatedBob(1.0F) : 0.0F;
		mc.carryEnt = CarryClient.carriedEntity(); // v30: the GMod client turns the look with it
		ClientLink.INSTANCE.writeMcState(mc);
	}

	/** Freeze the player until GMod's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// GMod is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		int bx = (int) Math.floor(holdPos.x), by = (int) Math.floor(holdPos.y), bz = (int) Math.floor(holdPos.z);
		boolean known = SkyCollision.CLIENT.isKnown(bx, by - 1, bz) && SkyCollision.CLIENT.isKnown(bx, by, bz)
			&& SkyCollision.CLIENT.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
		// Release once there is actual ground below (or after a timeout, e.g. when mid-air on purpose).
		boolean ready = known && (SkyCollision.CLIENT.hasSolidBelow(bx, by, bz, 12) || System.currentTimeMillis() - holdSince > 6000);
		if (ready && sky.inGame() && !sky.loading()) {
			// GMod's feet can sit a fraction of a voxel inside our ground layer. Minecraft's
			// collision never pushes you out of a shape, so you'd drop through: lift out first.
			Vec3 safe = liftOutOfGeometry(player, holdPos);
			if (safe.y != holdPos.y) {
				player.setPos(safe.x, safe.y, safe.z);
				player.yo = safe.y;
				GmodCraft.LOG.info("GmodCraft: lifted player {} blocks out of the ground", String.format("%.3f", safe.y - holdPos.y));
			}
			holdPos = null;
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.resetFallDistance();
	}

	private static Vec3 liftOutOfGeometry(LocalPlayer player, Vec3 pos) {
		// Stand on the exact GMod ground if it is slightly above the feet (up to 2.5 blocks).
		double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
		return !Double.isNaN(ground) && ground > pos.y ? new Vec3(pos.x, ground, pos.z) : pos;
	}

	/** After GameRenderer.render(): report the player to GMod and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.mainCamera();
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.position();
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = camera.getFov();
			// Minecraft's F5 camera: GMod puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			mc.cameraDistance = camera.isDetached() ? (float) camera.position().distanceTo(player.getEyePosition(partial)) : 0.0F;
			// v17 hybrid mode: which GMod weapon the main hand holds (GMod selects it, H2).
			mc.heldWeapon = dev.gmodcraft.weapon.GmodWeapons.heldHash(player);
			mc.heldSlot = player.getInventory().getSelectedSlot();
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
			var entityState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.entityRenderState;
			boolean bob = minecraft.options.bobView().get() && entityState.isPlayer;
			mc.bobPhase = bob ? entityState.backwardsInterpolatedWalkDistance : 0.0F;
			mc.bobAmount = bob ? entityState.bob : 0.0F;
		}
		if (minecraft.gui.screen() != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		if (holdPos != null && player != null) {
			flags |= Proto.MC_HELD;
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportCount = teleportCount;
		mc.guiScale = minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		ClientLink.INSTANCE.writeMcState(mc);
		writeMcScreen(minecraft, player);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
			try {
				WorldExporter.frame(minecraft, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					GmodCraft.LOG.error("GmodCraft: world export failed", e);
				}
			}
			FrameExporter.capture(minecraft);
		}
	}

	// While GMod's frames don't come (a stall, a loading screen, a pause) or GMod is gone, the hidden
	// Minecraft would otherwise run at its 260 fps limit: hold it to about 60 instead.
	private static final long UNPACED_FRAME_NS = 1_000_000_000L / 60;
	private static long lastPaceEndNs;

	/**
	 * v35 (S1): the open screen's slot under the cursor, as the local player's inventory index (the
	 * GMod spawnmenu's Minecraft tab drops weapons onto it). After the render, so hoveredSlot belongs
	 * to the cursor applied this frame.
	 */
	private static void writeMcScreen(Minecraft minecraft, LocalPlayer player) {
		var screen = minecraft.gui.screen();
		int flags = 0;
		int hovered = -1;
		if (screen != null) {
			flags |= Proto.SCR_OPEN;
			if (screen instanceof AbstractContainerScreenAccessor acc) {
				flags |= Proto.SCR_CONTAINER;
				Slot slot = acc.gmodcraft$hoveredSlot();
				if (slot instanceof CreativeSlotWrapperAccessor wrapper) {
					slot = wrapper.gmodcraft$target();
				}
				if (slot != null && player != null && slot.container == player.getInventory()) {
					hovered = slot.getContainerSlot();
				}
			}
		}
		ClientLink.INSTANCE.writeMcScreen(flags, hovered, InputBridge.cursorX(), InputBridge.cursorY(), frameCounter);
	}

	private static void capUnpaced() {
		long wait = lastPaceEndNs + UNPACED_FRAME_NS - System.nanoTime();
		if (wait > 0) {
			java.util.concurrent.locks.LockSupport.parkNanos(wait);
		}
		lastPaceEndNs = System.nanoTime();
	}

	/** End of the frame: render at most once per GMod frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			if (tookOver) {
				capUnpaced(); // GMod went away: nothing to pace on, and nobody sees this window
			}
			return;
		}
		if (hostStalled && (ClientLink.INSTANCE.hostStateSeq() >>> 1) == lastPacedSeq) {
			capUnpaced(); // GMod is paused (menu / alt-tab, loading): don't block every frame waiting for it
			return;
		}
		hostStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// HostState.seq advances by 2 per GMod frame (odd while writing).
		while ((ClientLink.INSTANCE.hostStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = ClientLink.INSTANCE.hostStateSeq() >>> 1;
		hostStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
		lastPaceEndNs = System.nanoTime();
	}

	/**
	 * GMod connected (I1): from now on Minecraft never touches the system mouse. A grab it made before
	 * (a visible Minecraft in its world) is undone without a warp, and MouseHandler's own flag says
	 * grabbed while in a world with no screen, as it would with real focus (clicks then act at once
	 * instead of first "grabbing").
	 */
	private static void takeOverMouse(Minecraft minecraft) {
		if (MOUSE.takeStaleGrab()) {
			org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode(minecraft.getWindow().handle(), false);
			GmodCraft.LOG.info("GmodCraft: Minecraft's system mouse grab from before GMod connected released (no warp)");
		}
		if (minecraft.level != null && minecraft.gui.screen() == null && !minecraft.mouseHandler.isMouseGrabbed()) {
			minecraft.mouseHandler.grabMouse(); // the system part is refused now (tookOver): only the flag
			GmodCraft.LOG.info("GmodCraft: mouse marked grabbed for GMod's input (in world, no screen): {}", minecraft.mouseHandler.isMouseGrabbed());
		}
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		options.vignette().set(false);
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and GMod NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
		GmodCraft.LOG.info("GmodCraft: game window hidden (run with -Dgmodcraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
		int w = Math.min(sky.viewportW, Proto.MAX_OVERLAY_W);
		int h = Math.min(sky.viewportH, Proto.MAX_OVERLAY_H);
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		minecraft.getWindow().setWindowed(w, h);
		GmodCraft.LOG.info("GmodCraft: sizing overlay to the GMod viewport {}x{}", w, h);
	}
}
