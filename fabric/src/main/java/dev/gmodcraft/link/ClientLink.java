package dev.gmodcraft.link;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.gmodcraft.GmodCraft;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The client link: the GMod client of this player (host state, input, overlay, render ring, the
 * client's copy of collision and actors). Used only by Minecraft client code, polled from the
 * render thread. Found through -Dgmodcraft.link / GMODCRAFT_LINK or /dev/shm/gmodcraft/client.json.
 */
public final class ClientLink extends GLink {
	public static final ClientLink INSTANCE = new ClientLink();

	private ClientLink() {
		super("client", LINK_CLIENT, CL_MAPPING_BYTES, "gmodcraft.link", "GMODCRAFT_LINK");
	}

	@Override
	protected long statsOffset() {
		return CL_OFF_LINK_STATS;
	}

	@Override
	public long collisionRingOffset() {
		return CL_OFF_COLLISION_RING;
	}

	@Override
	public long collisionRingBytes() {
		return CL_COLLISION_RING_BYTES;
	}

	// ---- "Minecraft is already running" ----------------------------------------------------

	// Held for the life of the JVM (a collected channel would drop the lock). POSIX locks are per
	// process and file, so nothing else in this JVM may open this file.
	private static @Nullable FileChannel lockChannel;
	private static @Nullable FileLock lock;

	/**
	 * Holds an fcntl lock on /dev/shm/gmodcraft/minecraft-client[-name].lock for as long as
	 * this Minecraft runs, so the host knows not to start another one (even before they link up).
	 */
	public static synchronized void announceRunning() {
		if (lockChannel != null) {
			return;
		}
		String name = INSTANCE.override();
		String file = name == null ? "minecraft-client.lock" : "minecraft-client-" + name.replaceAll("[^A-Za-z0-9._-]", "_") + ".lock";
		Path dir = discoveryDir();
		Path path = dir.resolve(file);
		try {
			if (!Files.isDirectory(dir)) {
				Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			}
			FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
			FileLock l = ch.tryLock(0, 1, false);
			if (l == null) {
				GmodCraft.LOG.warn("GmodCraft: another Minecraft already holds {}", path);
				ch.close();
				return;
			}
			lockChannel = ch;
			lock = l;
			GmodCraft.LOG.info("GmodCraft: holding {}", path);
		} catch (IOException | OverlappingFileLockException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't take the running-Minecraft lock {}", path, e);
		}
	}

	// ---- HostState (read) ------------------------------------------------------------------

	/** Plain snapshot of HostState. */
	public static final class HostState {
		public int seq;
		public int flags;
		public int worldId;
		public int collisionEpoch;
		public double x, y, z;
		public float yaw, pitch;
		public int slotOriginX, slotOriginZ;
		public int slotOriginY;  // v21: the slot's vertical offset, Source units (debug only: MC never converts)
		public int viewportW, viewportH;
		public float gameHour;
		// v17 hybrid mode (H2 uses them): HostHybridFlags and the active GMod weapon's ammo (-1 none).
		public int hybridFlags;
		public int clip1 = -1, maxClip1 = -1, ammo1 = -1, ammo2 = -1;
		// v30 (P6i carry): the moving GMod entity under the player, 0 = none (see the protocol header).
		public int carryEnt;
		public int carrySeq;
		public int carryFlags;
		public float carryVelX, carryVelY, carryVelZ; // MC blocks per tick
		public float carryYawRate;                    // MC yaw, radians per tick
		public double carryPivotX, carryPivotY, carryPivotZ;
		public double carryTopY;
		public float carryYaw;                        // MC yaw, radians

		public boolean inGame() {
			return (this.flags & HOST_IN_GAME) != 0;
		}

		public boolean menuOpen() {
			return (this.flags & HOST_MENU_OPEN) != 0;
		}

		public boolean loading() {
			return (this.flags & HOST_LOADING) != 0;
		}

		public boolean slotKnown() {
			return (this.flags & HOST_SLOT_KNOWN) != 0;
		}
	}

	/** Seqlock read of HostState into {@code out}. Returns false if the link is down or the read kept tearing. */
	public boolean readHostState(HostState out) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		long b = CL_OFF_HOST_STATE;
		for (int attempt = 0; attempt < 1000; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + HS_SEQ);
			if ((seq1 & 1) != 0) {
				if (attempt > 100) {
					Thread.yield();
				} else {
					Thread.onSpinWait();
				}
				continue;
			}
			out.flags = s.get(JAVA_INT, b + HS_FLAGS);
			out.worldId = s.get(JAVA_INT, b + HS_WORLD_ID);
			out.collisionEpoch = s.get(JAVA_INT, b + HS_COLLISION_EPOCH);
			out.x = s.get(JAVA_DOUBLE, b + HS_POS_X);
			out.y = s.get(JAVA_DOUBLE, b + HS_POS_Y);
			out.z = s.get(JAVA_DOUBLE, b + HS_POS_Z);
			out.yaw = s.get(JAVA_FLOAT, b + HS_YAW);
			out.pitch = s.get(JAVA_FLOAT, b + HS_PITCH);
			out.slotOriginX = s.get(JAVA_INT, b + HS_SLOT_ORIGIN_X);
			out.slotOriginZ = s.get(JAVA_INT, b + HS_SLOT_ORIGIN_Z);
			out.slotOriginY = s.get(JAVA_INT, b + HS_SLOT_ORIGIN_Y);
			out.viewportW = s.get(JAVA_INT, b + HS_VIEWPORT_W);
			out.viewportH = s.get(JAVA_INT, b + HS_VIEWPORT_H);
			out.gameHour = s.get(JAVA_FLOAT, b + HS_GAME_HOUR);
			out.hybridFlags = s.get(JAVA_INT, b + HS_HYBRID_FLAGS);
			out.clip1 = s.get(JAVA_INT, b + HS_CLIP1);
			out.maxClip1 = s.get(JAVA_INT, b + HS_MAX_CLIP1);
			out.ammo1 = s.get(JAVA_INT, b + HS_AMMO1);
			out.ammo2 = s.get(JAVA_INT, b + HS_AMMO2);
			out.carryEnt = s.get(JAVA_INT, b + HS_CARRY_ENT);
			out.carrySeq = s.get(JAVA_INT, b + HS_CARRY_SEQ);
			out.carryFlags = s.get(JAVA_INT, b + HS_CARRY_FLAGS);
			out.carryVelX = s.get(JAVA_FLOAT, b + HS_CARRY_VEL_X);
			out.carryVelY = s.get(JAVA_FLOAT, b + HS_CARRY_VEL_Y);
			out.carryVelZ = s.get(JAVA_FLOAT, b + HS_CARRY_VEL_Z);
			out.carryYawRate = s.get(JAVA_FLOAT, b + HS_CARRY_YAW_RATE);
			out.carryPivotX = s.get(JAVA_DOUBLE, b + HS_CARRY_PIVOT_X);
			out.carryPivotY = s.get(JAVA_DOUBLE, b + HS_CARRY_PIVOT_Y);
			out.carryPivotZ = s.get(JAVA_DOUBLE, b + HS_CARRY_PIVOT_Z);
			out.carryTopY = s.get(JAVA_DOUBLE, b + HS_CARRY_TOP_Y);
			out.carryYaw = s.get(JAVA_FLOAT, b + HS_CARRY_YAW);
			VarHandle.loadLoadFence();
			int seq2 = (int) INT.getAcquire(s, b + HS_SEQ);
			if (seq1 == seq2) {
				out.seq = seq1;
				return true;
			}
		}
		return false;
	}

	/** Raw HostState sequence number; changes once per host frame. */
	public int hostStateSeq() {
		MemorySegment s = segment();
		return s == null ? 0 : (int) INT.getAcquire(s, CL_OFF_HOST_STATE + HS_SEQ);
	}

	/** The host's water around the local player, or null. */
	public @Nullable WaterGrid readWaterGrid() {
		return readWaterGrid(CL_OFF_WATER_GRID);
	}

	/** The actors as the GMod client draws them this frame. */
	public boolean readActors(List<Actor> out) {
		return readActors(CL_OFF_ACTOR_TABLE, out);
	}

	// ---- JoinInfo (read), JoinStatus + event ring (write): multiplayer pairing, v14 ----------

	/**
	 * The Minecraft server this player should play on (empty: its own world), its pairing token,
	 * and the instruction's id, as the GMod server announced it. toString leaves the token out.
	 */
	public record JoinInfo(String serverAddress, String joinToken, int joinId) {
		@Override
		public String toString() {
			return "JoinInfo[" + this.joinId + " " + (this.serverAddress.isEmpty() ? "(own world)" : this.serverAddress) + "]";
		}
	}

	/** Seqlock write of JoinStatus (see the protocol header). Render thread only. */
	public void writeJoinStatus(int state, int joinId, int result, String serverAddress, String reason) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = CL_OFF_JOIN_STATUS;
		int seq = s.get(JAVA_INT, b + JS_SEQ);
		INT.setRelease(s, b + JS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + JS_STATE, state);
		s.set(JAVA_INT, b + JS_JOIN_ID, joinId);
		s.set(JAVA_INT, b + JS_RESULT, result);
		writeString(s, b + JS_SERVER_ADDRESS, (int) SERVER_ADDRESS_BYTES, serverAddress);
		writeString(s, b + JS_REASON, (int) JOIN_REASON_BYTES, reason);
		INT.setRelease(s, b + JS_SEQ, seq + 2);
	}

	/** kEvJoinResult on the client link's event ring. */
	public synchronized void pushJoinResult(int joinId, int result) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		pushMcEvent(s, CL_OFF_EVENT_RING, CL_EVENT_RING_ENTRIES, CL_RING_EVENTS, EV_JOIN_RESULT, 0, 0L, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, joinId, result);
	}

	/** Seqlock read of JoinInfo; null if the link is down, nothing was written yet, or the read kept tearing. */
	public @Nullable JoinInfo readJoinInfo() {
		MemorySegment s = segment();
		if (s == null) {
			return null;
		}
		long b = CL_OFF_JOIN_INFO;
		for (int attempt = 0; attempt < 100; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + JI_SEQ);
			if (seq1 == 0) {
				return null;
			}
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			String address = readString(s, b + JI_SERVER_ADDRESS, (int) SERVER_ADDRESS_BYTES);
			String token = readString(s, b + JI_JOIN_TOKEN, (int) JOIN_TOKEN_BYTES);
			int joinId = s.get(JAVA_INT, b + JI_JOIN_ID);
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + JI_SEQ) == seq1) {
				return new JoinInfo(address, token, joinId);
			}
		}
		return null;
	}

	// ---- McState (write) -------------------------------------------------------------------

	public static final class McState {
		public int flags;
		public double x, y, z;
		public float yaw, pitch;
		public float eyeHeight;
		public float sensitivity;
		public int teleportCount;
		public int guiScale;
		public long frameCounter;
		public float fov;
		public float bobPhase;
		public float bobAmount;
		public double eyeX, eyeY, eyeZ;
		public long tickNs;
		public double prevX, prevY, prevZ;
		public double curX, curY, curZ;
		public float eyeHeightO, eyeHeightT;
		public float walkDistO, walkDist;
		public float bobO, bob;
		public float tickMs = 50.0F;
		public int cameraMode;
		public float cameraDistance;
		public int heldWeapon; // v17: class hash of the gmod_weapon in the main hand, 0 = none
		public int heldSlot;   // v17: selected hotbar slot
		public int carryEnt;   // v30: the GMod entity the player was carried with this tick, 0 = none
	}

	public void writeMcState(McState st) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = CL_OFF_MC_STATE;
		int seq = s.get(JAVA_INT, b + MS_SEQ);
		INT.setRelease(s, b + MS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + MS_FLAGS, st.flags);
		s.set(JAVA_DOUBLE, b + MS_X, st.x);
		s.set(JAVA_DOUBLE, b + MS_Y, st.y);
		s.set(JAVA_DOUBLE, b + MS_Z, st.z);
		s.set(JAVA_FLOAT, b + MS_YAW, st.yaw);
		s.set(JAVA_FLOAT, b + MS_PITCH, st.pitch);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT, st.eyeHeight);
		s.set(JAVA_FLOAT, b + MS_SENSITIVITY, st.sensitivity);
		s.set(JAVA_INT, b + MS_TELEPORT_COUNT, st.teleportCount);
		s.set(JAVA_INT, b + MS_GUI_SCALE, st.guiScale);
		s.set(JAVA_LONG, b + MS_FRAME_COUNTER, st.frameCounter);
		s.set(JAVA_FLOAT, b + MS_FOV, st.fov);
		s.set(JAVA_FLOAT, b + MS_BOB_PHASE, st.bobPhase);
		s.set(JAVA_FLOAT, b + MS_BOB_AMOUNT, st.bobAmount);
		s.set(JAVA_DOUBLE, b + MS_EYE_X, st.eyeX);
		s.set(JAVA_DOUBLE, b + MS_EYE_Y, st.eyeY);
		s.set(JAVA_DOUBLE, b + MS_EYE_Z, st.eyeZ);
		s.set(JAVA_LONG, b + MS_TICK_NS, st.tickNs);
		s.set(JAVA_DOUBLE, b + MS_PREV_X, st.prevX);
		s.set(JAVA_DOUBLE, b + MS_PREV_Y, st.prevY);
		s.set(JAVA_DOUBLE, b + MS_PREV_Z, st.prevZ);
		s.set(JAVA_DOUBLE, b + MS_CUR_X, st.curX);
		s.set(JAVA_DOUBLE, b + MS_CUR_Y, st.curY);
		s.set(JAVA_DOUBLE, b + MS_CUR_Z, st.curZ);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT_O, st.eyeHeightO);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT_T, st.eyeHeightT);
		s.set(JAVA_FLOAT, b + MS_WALK_O, st.walkDistO);
		s.set(JAVA_FLOAT, b + MS_WALK, st.walkDist);
		s.set(JAVA_FLOAT, b + MS_BOB_O, st.bobO);
		s.set(JAVA_FLOAT, b + MS_BOB, st.bob);
		s.set(JAVA_FLOAT, b + MS_TICK_MS, st.tickMs);
		s.set(JAVA_INT, b + MS_CAMERA_MODE, st.cameraMode);
		s.set(JAVA_FLOAT, b + MS_CAMERA_DISTANCE, st.cameraDistance);
		s.set(JAVA_INT, b + MS_HELD_WEAPON, st.heldWeapon);
		s.set(JAVA_INT, b + MS_HELD_SLOT, st.heldSlot);
		s.set(JAVA_INT, b + MS_CARRY_ENT, st.carryEnt);
		INT.setRelease(s, b + MS_SEQ, seq + 2);
	}

	// ---- McScreen (write; v35, S1) -------------------------------------------------------------

	/** Seqlock write of the open screen's slot under the cursor (McScreen). Render thread only. */
	public void writeMcScreen(int flags, int hoveredSlot, int cursorX, int cursorY, long frameCounter) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = CL_OFF_MC_SCREEN;
		int seq = s.get(JAVA_INT, b + SCR_SEQ);
		INT.setRelease(s, b + SCR_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + SCR_FLAGS, flags);
		s.set(JAVA_INT, b + SCR_HOVERED_SLOT, hoveredSlot);
		s.set(JAVA_INT, b + SCR_CURSOR_X, cursorX);
		s.set(JAVA_INT, b + SCR_CURSOR_Y, cursorY);
		s.set(JAVA_LONG, b + SCR_FRAME_COUNTER, frameCounter);
		INT.setRelease(s, b + SCR_SEQ, seq + 2);
	}

	// ---- McIdentity (write) ----------------------------------------------------------------

	/** Seqlock write of who this Minecraft is (McIdentity). Render thread only. */
	public void writeIdentity(int flags, java.util.@Nullable UUID uuid, String name) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = CL_OFF_MC_IDENTITY;
		int seq = s.get(JAVA_INT, b + ID_SEQ);
		INT.setRelease(s, b + ID_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + ID_FLAGS, flags);
		writeUuid(s, b + ID_UUID, uuid);
		writeString(s, b + ID_NAME, (int) MC_NAME_BYTES, name);
		INT.setRelease(s, b + ID_SEQ, seq + 2);
	}

	// ---- input ring (consume) --------------------------------------------------------------

	public interface InputSink {
		void accept(int type, int code, int a, int b, int c);
	}

	/** Drains every pending input event. Render thread only. */
	public void drainInput(InputSink sink) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long base = CL_OFF_INPUT_RING;
		long head = (long) LONG.getAcquire(s, base + IR_HEAD);
		long tail = s.get(JAVA_LONG, base + IR_TAIL);
		if (head == tail) {
			return;
		}
		long fill = head - tail;
		long lost = 0;
		if (head - tail > INPUT_RING_ENTRIES) {
			lost = head - tail - INPUT_RING_ENTRIES;
			tail = head - INPUT_RING_ENTRIES; // producer lapped us; drop the oldest
		}
		long read = head - tail;
		while (tail < head) {
			long e = base + IR_DATA + (tail & (INPUT_RING_ENTRIES - 1)) * INPUT_EVENT_BYTES;
			int type = Short.toUnsignedInt(s.get(JAVA_SHORT, e));
			int code = Short.toUnsignedInt(s.get(JAVA_SHORT, e + 2));
			int a = s.get(JAVA_INT, e + 4);
			int b = s.get(JAVA_INT, e + 8);
			int c = s.get(JAVA_INT, e + 12);
			tail++;
			sink.accept(type, code, a, b, c);
		}
		LONG.setRelease(s, base + IR_TAIL, tail);
		countRing(s, CL_RING_INPUT, read, read * INPUT_EVENT_BYTES, lost, fill);
	}

	// ---- world entities (write) ------------------------------------------------------------

	/**
	 * One Minecraft thing for the host to draw (see WorldEntity in the protocol header). {@code uv}
	 * holds up to three atlas rects {u0, v0, u1, v1}: sprite/side, top, bottom.
	 */
	public record WorldEntity(int kind, int id, float x, float y, float z, float yaw, float pitch, float scale, float[] ext, float[] uv, int tint) {
	}

	/** Seqlock write of the world-entity table and block selection. Render thread only. */
	public void writeWorldEntities(List<WorldEntity> entities, float @Nullable [] selection) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = CL_OFF_WORLD_ENTITIES;
		int seq = s.get(JAVA_INT, b + WT_SEQ);
		INT.setRelease(s, b + WT_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int count = Math.min(entities.size(), MAX_WORLD_ENTITIES);
		s.set(JAVA_INT, b + WT_COUNT, count);
		s.set(JAVA_INT, b + WT_HAS_SELECTION, selection != null ? 1 : 0);
		if (selection != null) {
			for (int i = 0; i < 6; i++) {
				s.set(JAVA_FLOAT, b + WT_SEL_MIN + i * 4L, selection[i]);
			}
		}
		for (int i = 0; i < count; i++) {
			WorldEntity w = entities.get(i);
			long r = b + WT_RECORDS + i * WORLD_ENTITY_BYTES;
			s.set(JAVA_INT, r, w.kind());
			s.set(JAVA_INT, r + 4, w.id());
			s.set(JAVA_FLOAT, r + 8, w.x());
			s.set(JAVA_FLOAT, r + 12, w.y());
			s.set(JAVA_FLOAT, r + 16, w.z());
			s.set(JAVA_FLOAT, r + 20, w.yaw());
			s.set(JAVA_FLOAT, r + 24, w.pitch());
			s.set(JAVA_FLOAT, r + 28, w.scale());
			for (int k = 0; k < 3; k++) {
				s.set(JAVA_FLOAT, r + 32 + k * 4L, w.ext() != null ? w.ext()[k] : 0.0F);
			}
			for (int k = 0; k < 12; k++) {
				s.set(JAVA_FLOAT, r + 44 + k * 4L, w.uv() != null && k < w.uv().length ? w.uv()[k] : 0.0F);
			}
			s.set(JAVA_INT, r + 92, w.tint());
		}
		INT.setRelease(s, b + WT_SEQ, seq + 2);
	}

	// ---- render ring (produce) -------------------------------------------------------------

	private static final long RR_DATA_BYTES = RENDER_RING_BYTES - RR_DATA;

	/**
	 * Writes one render message ({@code header} bytes then {@code body} bytes) into the render ring,
	 * waiting briefly for space. Single producer. Returns false if it never fit.
	 */
	public boolean writeRender(int type, java.nio.ByteBuffer header, java.nio.@Nullable ByteBuffer body) {
		return writeRender(type, header, body, 500);
	}

	/** Like writeRender, but gives up at once if the ring is full (per-frame data that the next frame replaces). */
	public boolean tryWriteRender(int type, java.nio.ByteBuffer header, java.nio.@Nullable ByteBuffer body) {
		return writeRender(type, header, body, 1);
	}

	private synchronized boolean writeRender(int type, java.nio.ByteBuffer header, java.nio.@Nullable ByteBuffer body, int attempts) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		int payload = header.remaining() + (body != null ? body.remaining() : 0);
		long msgBytes = (8 + payload + 7) & ~7L;
		if (msgBytes > RR_DATA_BYTES / 2) {
			GmodCraft.LOG.warn("GmodCraft: render message too large ({} bytes)", msgBytes);
			return false;
		}
		long base = CL_OFF_RENDER_RING;
		for (int attempt = 0; attempt < attempts; attempt++) {
			long head = s.get(JAVA_LONG, base + RR_HEAD);
			long tail = (long) LONG.getAcquire(s, base + RR_TAIL);
			long pos = head % RR_DATA_BYTES;
			long pad = pos + msgBytes > RR_DATA_BYTES ? RR_DATA_BYTES - pos : 0;
			if (RR_DATA_BYTES - (head - tail) < msgBytes + pad) {
				if (attempt + 1 >= attempts) {
					break;
				}
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					return false;
				}
				continue;
			}
			if (pad > 0) {
				s.set(JAVA_INT, base + RR_DATA + pos, REN_PAD);
				s.set(JAVA_INT, base + RR_DATA + pos + 4, 0);
				head += pad;
				pos = 0;
			}
			long at = base + RR_DATA + pos;
			s.set(JAVA_INT, at, type);
			s.set(JAVA_INT, at + 4, payload);
			MemorySegment.copy(MemorySegment.ofBuffer(header), 0, s, at + 8, header.remaining());
			if (body != null && body.remaining() > 0) {
				MemorySegment.copy(MemorySegment.ofBuffer(body), 0, s, at + 8 + header.remaining(), body.remaining());
			}
			LONG.setRelease(s, base + RR_HEAD, head + msgBytes);
			countRing(s, CL_RING_RENDER, 1, msgBytes + pad, 0, head + msgBytes - tail);
			return true;
		}
		countRing(s, CL_RING_RENDER, 0, 0, 1, s.get(JAVA_LONG, base + RR_HEAD) - (long) LONG.getAcquire(s, base + RR_TAIL));
		return false;
	}

	// ---- overlay (publish) -----------------------------------------------------------------

	// The writer's private slot, per session: the host's front starts at 2, the middle at 0.
	private int overlayBack = 1;
	private int overlayGeneration;
	private long overlayFrames;

	/** Where to write the next overlay frame: one session, and the byte offset of its back slot. */
	public record OverlayTarget(Session session, long offset) {
	}

	/** The back slot of the current session (null: no link). Render thread only. */
	public synchronized @Nullable OverlayTarget overlayTarget() {
		Session ss = session();
		if (ss == null) {
			return null;
		}
		if (ss.generation() != this.overlayGeneration) {
			this.overlayGeneration = ss.generation(); // a new host session: the triple buffer starts over
			this.overlayBack = 1;
			this.overlayFrames = 0;
		}
		return new OverlayTarget(ss, CL_OFF_OVERLAY_PIXELS + this.overlayBack * OVERLAY_SLOT_BYTES);
	}

	/** Publishes the frame just written to {@code target} (flags: Proto.OV_*). Same session or nothing. */
	public synchronized void publishOverlay(OverlayTarget target, int width, int height, int flags, long frameId) {
		Session ss = target.session();
		if (ss.generation() != this.overlayGeneration) {
			return; // relinked since the target was taken: that frame went to the old mapping
		}
		MemorySegment s = ss.seg();
		long hdr = CL_OFF_OVERLAY_SLOT_HDR + this.overlayBack * SLOT_HDR_BYTES;
		s.set(JAVA_INT, hdr + SH_WIDTH, width);
		s.set(JAVA_INT, hdr + SH_HEIGHT, height);
		s.set(JAVA_INT, hdr + SH_FLAGS, flags);
		s.set(JAVA_LONG, hdr + SH_FRAME_ID, frameId);
		// xchg: hand the back slot over as the new middle (dirty) and take the old middle back.
		int old = (int) INT.getAndSet(s, CL_OFF_OVERLAY_CTL + OC_STATE, this.overlayBack | OVERLAY_DIRTY);
		this.overlayBack = old & 3;
		LONG.getAndAdd(s, CL_OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED, 1L);
		setSideLong(s, SD_OVERLAY_FRAMES, ++this.overlayFrames);
	}
}
