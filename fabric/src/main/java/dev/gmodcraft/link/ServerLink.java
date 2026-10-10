package dev.gmodcraft.link;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.gmodcraft.GmodCraft;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The server link: the GMod server (server state, authoritative actors, collision for the server
 * world, host events for players, the GMod/Minecraft player map) and what the Minecraft server
 * tells it back (slot origin, players, events, its blocks). Used only by Minecraft server-side
 * code and polled from the server thread, so it works the same in an integrated server and on a
 * dedicated one. Found through -Dgmodcraft.serverLink / GMODCRAFT_SERVER_LINK or
 * /dev/shm/gmodcraft/server.json.
 */
public final class ServerLink extends GLink {
	public static final ServerLink INSTANCE = new ServerLink();

	private ServerLink() {
		super("server", LINK_SERVER, SV_MAPPING_BYTES, "gmodcraft.serverLink", "GMODCRAFT_SERVER_LINK");
	}

	@Override
	protected long statsOffset() {
		return SV_OFF_LINK_STATS;
	}

	@Override
	public long collisionRingOffset() {
		return SV_OFF_COLLISION_RING;
	}

	@Override
	public long collisionRingBytes() {
		return SV_COLLISION_RING_BYTES;
	}

	// ---- ServerState (read) ----------------------------------------------------------------

	public static final class ServerState {
		public int seq;
		public int flags;
		public int worldId;
		public int collisionEpoch;
		public long tickNs;
		public String mapName = "";
		// v21 anchor (P8 WP1): the host's floor hint for a new slot (Source units), valid with anchorReady().
		public int anchorSource;
		public float floorZ, minZ, maxZ, footMinX, footMinY, footMaxX, footMaxY;
		/** v43: the day-night authority (Proto.SUN_SYNC_*). */
		public int sunSync;

		public boolean inGame() {
			return (this.flags & SERVER_IN_GAME) != 0;
		}

		public boolean loading() {
			return (this.flags & SERVER_LOADING) != 0;
		}

		/** v16: Wiremod is installed on the GMod server (redstone bridges work). */
		public boolean wiremod() {
			return (this.flags & SERVER_WIREMOD) != 0;
		}

		/** v25: map entity links on bridges (works without Wiremod). */
		public boolean mapIo() {
			return (this.flags & SERVER_MAP_IO) != 0;
		}

		/** v21: the anchor fields describe this map (kServerAnchorReady). */
		public boolean anchorReady() {
			return (this.flags & SERVER_ANCHOR_READY) != 0;
		}
	}

	/** Seqlock read of ServerState into {@code out}. Returns false if the link is down or the read kept tearing. */
	public boolean readServerState(ServerState out) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		long b = SV_OFF_SERVER_STATE;
		for (int attempt = 0; attempt < 200; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + SS_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			out.flags = s.get(JAVA_INT, b + SS_FLAGS);
			out.worldId = s.get(JAVA_INT, b + SS_WORLD_ID);
			out.collisionEpoch = s.get(JAVA_INT, b + SS_COLLISION_EPOCH);
			out.tickNs = s.get(JAVA_LONG, b + SS_TICK_NS);
			out.mapName = readString(s, b + SS_MAP_NAME, (int) MAP_NAME_BYTES);
			out.anchorSource = s.get(JAVA_INT, b + SS_ANCHOR_SOURCE);
			out.floorZ = s.get(JAVA_FLOAT, b + SS_FLOOR_Z);
			out.minZ = s.get(JAVA_FLOAT, b + SS_MIN_Z);
			out.maxZ = s.get(JAVA_FLOAT, b + SS_MAX_Z);
			out.footMinX = s.get(JAVA_FLOAT, b + SS_FOOT_MIN_X);
			out.footMinY = s.get(JAVA_FLOAT, b + SS_FOOT_MIN_Y);
			out.footMaxX = s.get(JAVA_FLOAT, b + SS_FOOT_MAX_X);
			out.footMaxY = s.get(JAVA_FLOAT, b + SS_FOOT_MAX_Y);
			out.sunSync = s.get(JAVA_BYTE, b + SS_SUN_SYNC) & 0xFF;
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + SS_SEQ) == seq1) {
				out.seq = seq1;
				return true;
			}
		}
		return false;
	}

	// ---- McServerState (write) -------------------------------------------------------------

	/**
	 * Seqlock write of the MC server's answer: the slot of {@code worldId} (see McServerState), with its
	 * vertical offset {@code originY} (v21, Source units) and where that came from. Server thread only.
	 */
	public void writeMcServerState(int flags, int worldId, int slotX, int slotZ, int slotCount, int originY, int anchorSource, Rules rules) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = SV_OFF_MC_SERVER_STATE;
		int seq = s.get(JAVA_INT, b + MSS_SEQ);
		INT.setRelease(s, b + MSS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + MSS_FLAGS, flags);
		s.set(JAVA_INT, b + MSS_WORLD_ID, worldId);
		s.set(JAVA_INT, b + MSS_SLOT_X, slotX);
		s.set(JAVA_INT, b + MSS_SLOT_Z, slotZ);
		s.set(JAVA_INT, b + MSS_ORIGIN_X, slotX * SLOT_BLOCKS);
		s.set(JAVA_INT, b + MSS_ORIGIN_Z, slotZ * SLOT_BLOCKS);
		s.set(JAVA_INT, b + MSS_SLOT_COUNT, slotCount);
		s.set(JAVA_LONG, b + MSS_TICK_NS, nowNs());
		s.set(JAVA_INT, b + MSS_ORIGIN_Y, originY);
		s.set(JAVA_INT, b + MSS_ANCHOR_SOURCE, anchorSource);
		s.set(JAVA_INT, b + MSS_RULE_FLAGS, rules.flags());
		s.set(JAVA_BYTE, b + MSS_GAME_MODE, (byte) rules.gameMode());
		s.set(JAVA_BYTE, b + MSS_WORLD_TYPE, (byte) rules.worldType());
		s.set(JAVA_SHORT, b + MSS_FLOOR_Y, (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rules.floorY())));
		s.set(JAVA_FLOAT, b + MSS_DAMAGE_SCALE, rules.damageScale());
		s.set(JAVA_BYTE, b + MSS_DIFFICULTY, (byte) rules.difficulty());
		s.set(JAVA_SHORT, b + MSS_MOB_CAP_PERCENT, (short) Math.max(0, Math.min(MOB_CAP_MAX, rules.mobCapPercent())));
		INT.setRelease(s, b + MSS_SEQ, seq + 2);
	}

	// ---- McServerSky (write; v43) ------------------------------------------------------------

	/**
	 * Seqlock write of the overworld's time of day (McServerSky). {@code v}: sunAngle, moonAngle,
	 * starBrightness, skyLight, rate, sky r g b, fog r g b, sunrise r g b a (15 floats). Server thread only.
	 */
	public void writeMcServerSky(int flags, long dayTime, int moonPhase, float[] v) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = SV_OFF_MC_SERVER_SKY;
		int seq = s.get(JAVA_INT, b + MSV_SEQ);
		INT.setRelease(s, b + MSV_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + MSV_FLAGS, flags);
		s.set(JAVA_LONG, b + MSV_DAY_TIME, dayTime);
		s.set(JAVA_FLOAT, b + MSV_SUN_ANGLE, v[0]);
		s.set(JAVA_FLOAT, b + MSV_MOON_ANGLE, v[1]);
		s.set(JAVA_FLOAT, b + MSV_STAR_BRIGHTNESS, v[2]);
		s.set(JAVA_FLOAT, b + MSV_SKY_LIGHT, v[3]);
		s.set(JAVA_INT, b + MSV_MOON_PHASE, moonPhase);
		s.set(JAVA_FLOAT, b + MSV_RATE, v[4]);
		for (int i = 0; i < 3; i++) {
			s.set(JAVA_FLOAT, b + MSV_SKY + 4L * i, v[5 + i]);
			s.set(JAVA_FLOAT, b + MSV_FOG + 4L * i, v[8 + i]);
		}
		for (int i = 0; i < 4; i++) {
			s.set(JAVA_FLOAT, b + MSV_SUNRISE + 4L * i, v[11 + i]);
		}
		s.set(JAVA_LONG, b + MSV_TICK_NS, nowNs());
		INT.setRelease(s, b + MSV_SEQ, seq + 2);
	}

	/** v24 (P8 WP3): McServerState's rules part (ServerRuleFlags, GameModeId, WorldType, floorY, hostDamagePerMcDamage). */
	public record Rules(int flags, int gameMode, int worldType, int floorY, float damageScale, int difficulty, int mobCapPercent) {
		public static final Rules NONE = new Rules(0, 0, 0, 0, 0.0F, 0, 0);
	}

	/** The v13 single water grid (deprecated in v14: player slot 0's while that slot's own grid is unwritten), or null. */
	public @Nullable WaterGrid readWaterGrid() {
		return readWaterGrid(SV_OFF_WATER_GRID);
	}

	/** The authoritative actor table (the MC server's proxies follow it). */
	public boolean readActors(List<Actor> out) {
		return readActors(SV_OFF_ACTOR_TABLE, out);
	}

	// ---- host event ring (consume) ---------------------------------------------------------

	/** One host event (see HostEvent in the protocol header). */
	public record HostEvent(int type, int code, int entId, long steamId, int requestId, int flags, int a, int worldId, double x, double y, double z, float yaw,
		float pitch, byte @Nullable [] text) {
	}

	/** Host events whose bytes from kHeText on are text (dev command, v17 weapon text slots). */
	private static boolean textSlot(int type) {
		return type == HOST_EV_DEV_COMMAND_TEXT || type == HOST_EV_WEAPON_TEXT || type == HOST_EV_ADMIN_TEXT || type == HOST_EV_PROP_TEXT;
	}

	/** Drains every pending host event. Server thread only. */
	public void drainHostEvents(java.util.function.Consumer<HostEvent> sink) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long base = SV_OFF_HOST_EVENT_RING;
		long head = (long) LONG.getAcquire(s, base + HR_HEAD);
		long tail = s.get(JAVA_LONG, base + HR_TAIL);
		if (head == tail) {
			return;
		}
		long fill = head - tail;
		long lost = 0;
		if (head - tail > HOST_EVENT_RING_ENTRIES) {
			lost = head - tail - HOST_EVENT_RING_ENTRIES;
			tail = head - HOST_EVENT_RING_ENTRIES;
		}
		long read = head - tail;
		while (tail < head) {
			long e = base + HR_DATA + (tail & (HOST_EVENT_RING_ENTRIES - 1)) * HOST_EVENT_BYTES;
			HostEvent ev = new HostEvent(
				Short.toUnsignedInt(s.get(JAVA_SHORT, e + HE_TYPE)), Short.toUnsignedInt(s.get(JAVA_SHORT, e + HE_CODE)), s.get(JAVA_INT, e + HE_ENT_ID),
				s.get(JAVA_LONG, e + HE_STEAM_ID), s.get(JAVA_INT, e + HE_REQUEST_ID), s.get(JAVA_INT, e + HE_FLAGS), s.get(JAVA_INT, e + HE_A),
				s.get(JAVA_INT, e + HE_WORLD_ID), s.get(JAVA_DOUBLE, e + HE_X), s.get(JAVA_DOUBLE, e + HE_Y), s.get(JAVA_DOUBLE, e + HE_Z),
				s.get(JAVA_FLOAT, e + HE_YAW), s.get(JAVA_FLOAT, e + HE_PITCH),
				textSlot(Short.toUnsignedInt(s.get(JAVA_SHORT, e + HE_TYPE))) ? s.asSlice(e + HE_TEXT, DEV_COMMAND_CHUNK_BYTES).toArray(JAVA_BYTE) : null);
			tail++;
			try {
				sink.accept(ev);
			} catch (RuntimeException ex) {
				GmodCraft.LOG.error("GmodCraft: host event {} failed", ev, ex);
			}
		}
		LONG.setRelease(s, base + HR_TAIL, tail);
		countRing(s, SV_RING_HOST_EVENTS, read, read * HOST_EVENT_BYTES, lost, fill);
	}

	// ---- player map ------------------------------------------------------------------------

	/**
	 * HostPlayer::reserved (8 bytes after mcName): the GMod server's P6b pairing check, the first 8
	 * bytes of SHA-256 of the join token it handed that player (0: none issued). No layout change:
	 * the offset follows from the header's own constants.
	 */
	public static final long HP_TOKEN_HASH = HP_MC_NAME + MC_NAME_BYTES;
	public static final int TOKEN_HASH_BYTES = 8;

	/**
	 * One GMod player and the Minecraft identity its Minecraft reported (null / empty: none yet).
	 * tokenHash: HP_TOKEN_HASH's 8 bytes, big-endian (0: no join token issued).
	 */
	public record HostPlayer(long steamId, int entIndex, int flags, @Nullable UUID mcUuid, String mcName, long tokenHash) {
		public boolean hasMc() {
			return (this.flags & HOST_PLAYER_HAS_MC) != 0;
		}

		/** v22: GMod has this player in noclip. */
		public boolean noclip() {
			return (this.flags & HOST_PLAYER_NOCLIP) != 0;
		}

		/** v30 (P6i): its Minecraft rides a moving GMod entity (the GMod server validated it). */
		public boolean carried() {
			return (this.flags & HOST_PLAYER_CARRIED) != 0;
		}

		public HostPlayer(long steamId, int entIndex, int flags, @Nullable UUID mcUuid, String mcName) {
			this(steamId, entIndex, flags, mcUuid, mcName, 0L);
		}
	}

	/** Seqlock read of the host's player list. False (and {@code out} empty) on a torn read or no link. */
	public boolean readHostPlayers(List<HostPlayer> out) {
		out.clear();
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		long b = SV_OFF_HOST_PLAYERS;
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + PT_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(Math.max(s.get(JAVA_INT, b + PT_COUNT), 0), MAX_PLAYERS);
			for (int i = 0; i < count; i++) {
				long r = b + PT_RECORDS + i * HOST_PLAYER_BYTES;
				out.add(new HostPlayer(s.get(JAVA_LONG, r + HP_STEAM_ID), s.get(JAVA_INT, r + HP_ENT_INDEX), s.get(JAVA_INT, r + HP_FLAGS),
					readUuid(s, r + HP_MC_UUID), readString(s, r + HP_MC_NAME, (int) MC_NAME_BYTES),
					s.get(JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), r + HP_TOKEN_HASH)));
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + PT_SEQ) == seq1) {
				return true;
			}
			out.clear();
		}
		return false;
	}

	/** One Minecraft player as reported to the host. */
	public record McPlayer(UUID uuid, long steamId, int entityId, int flags, float health, float maxHealth, String name) {
	}

	/** Seqlock write of the Minecraft player list. Server thread only. */
	public void writeMcPlayers(List<McPlayer> players) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = SV_OFF_MC_PLAYERS;
		int seq = s.get(JAVA_INT, b + PT_SEQ);
		INT.setRelease(s, b + PT_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int count = Math.min(players.size(), MAX_PLAYERS);
		s.set(JAVA_INT, b + PT_COUNT, count);
		for (int i = 0; i < count; i++) {
			McPlayer p = players.get(i);
			long r = b + PT_RECORDS + i * MC_PLAYER_BYTES;
			writeUuid(s, r + MP_UUID, p.uuid());
			s.set(JAVA_LONG, r + MP_STEAM_ID, p.steamId());
			s.set(JAVA_INT, r + MP_ENTITY_ID, p.entityId());
			s.set(JAVA_INT, r + MP_FLAGS, p.flags());
			s.set(JAVA_FLOAT, r + MP_HEALTH, p.health());
			s.set(JAVA_FLOAT, r + MP_MAX_HEALTH, p.maxHealth());
			writeString(s, r + MP_NAME, (int) MC_NAME_BYTES, p.name());
		}
		INT.setRelease(s, b + PT_SEQ, seq + 2);
	}

	/** v29 (T2): one Minecraft entity GMod owns right now (HeldMcEntities). MC coords, blocks per tick; yaw / pitch (v32): MC degrees, NaN = keep MC's. */
	public record HeldMcEntity(int entityId, int flags, long holderSteamId, double x, double y, double z, float vx, float vy, float vz, float yaw, float pitch) {
	}

	/**
	 * v29: seqlock read of the entities GMod owns (HeldMcEntities). Returns the seq read (even; 0: never
	 * written) with {@code out} filled, or -1 (and {@code out} empty) on a torn read or no link.
	 */
	public int readHeldMcEntities(List<HeldMcEntity> out) {
		out.clear();
		MemorySegment s = segment();
		if (s == null) {
			return -1;
		}
		long b = SV_OFF_HELD_MC_ENTITIES;
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + PT_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(Math.max(s.get(JAVA_INT, b + PT_COUNT), 0), MAX_HELD_MC_ENTITIES);
			for (int i = 0; i < count; i++) {
				long r = b + PT_RECORDS + i * HELD_MC_ENTITY_BYTES;
				out.add(new HeldMcEntity(s.get(JAVA_INT, r + HME_ENTITY_ID), s.get(JAVA_INT, r + HME_FLAGS), s.get(JAVA_LONG, r + HME_HOLDER_STEAM_ID),
					s.get(JAVA_DOUBLE, r + HME_X), s.get(JAVA_DOUBLE, r + HME_Y), s.get(JAVA_DOUBLE, r + HME_Z), s.get(JAVA_FLOAT, r + HME_VX),
					s.get(JAVA_FLOAT, r + HME_VY), s.get(JAVA_FLOAT, r + HME_VZ), s.get(JAVA_FLOAT, r + HME_YAW), s.get(JAVA_FLOAT, r + HME_PITCH)));
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + PT_SEQ) == seq1) {
				return seq1;
			}
			out.clear();
		}
		return -1;
	}

	/** v27: one Minecraft entity GMod gives a proxy body (McEntities). {@code category}: packed with the body yaw (v31, McEntities.packCategory). */
	public record McEntity(int entityId, int category, int flags, int typeHash, float width, float height, double x, double y, double z, float vx,
		float vy, float vz, float health, float maxHealth) {
	}

	/** v27: seqlock write of the MC entity table (McEntities). Server thread only. */
	public void writeMcEntities(List<McEntity> entities) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = SV_OFF_MC_ENTITIES;
		int seq = s.get(JAVA_INT, b + PT_SEQ);
		INT.setRelease(s, b + PT_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int count = Math.min(entities.size(), MAX_MC_ENTITIES);
		s.set(JAVA_INT, b + PT_COUNT, count);
		for (int i = 0; i < count; i++) {
			McEntity e = entities.get(i);
			long r = b + PT_RECORDS + i * MC_ENTITY_BYTES;
			s.set(JAVA_INT, r + MEN_ENTITY_ID, e.entityId());
			s.set(JAVA_SHORT, r + MEN_CATEGORY, (short) e.category());
			s.set(JAVA_SHORT, r + MEN_FLAGS, (short) e.flags());
			s.set(JAVA_INT, r + MEN_TYPE_HASH, e.typeHash());
			s.set(JAVA_FLOAT, r + MEN_WIDTH, e.width());
			s.set(JAVA_DOUBLE, r + MEN_X, e.x());
			s.set(JAVA_DOUBLE, r + MEN_Y, e.y());
			s.set(JAVA_DOUBLE, r + MEN_Z, e.z());
			s.set(JAVA_FLOAT, r + MEN_VX, e.vx());
			s.set(JAVA_FLOAT, r + MEN_VY, e.vy());
			s.set(JAVA_FLOAT, r + MEN_VZ, e.vz());
			s.set(JAVA_FLOAT, r + MEN_HEALTH, e.health());
			s.set(JAVA_FLOAT, r + MEN_MAX_HEALTH, e.maxHealth());
			s.set(JAVA_FLOAT, r + MEN_HEIGHT, e.height());
		}
		INT.setRelease(s, b + PT_SEQ, seq + 2);
	}

	// ---- event ring (produce) --------------------------------------------------------------

	/** Queues an event for the host. Safe from any thread. Drops it if the host is a full ring behind. */
	public void pushEvent(int type, int entId, long steamId, float a, float b, float c, float d, int flags) {
		pushEvent(type, entId, steamId, a, b, c, d, flags, 0, 0, 0);
	}

	public void pushEvent(int type, int entId, long steamId, float a, float b, float c, float d, int flags, int weapon) {
		pushEvent(type, entId, steamId, a, b, c, d, flags, weapon, 0, 0);
	}

	/** kEvTeleportAck: answers a teleport / respawn request. */
	public void pushTeleportAck(long steamId, int requestId, int result, double x, double y, double z) {
		pushEvent(EV_TELEPORT_ACK, 0, steamId, (float) x, (float) y, (float) z, 0.0F, 0, 0, requestId, result);
	}

	/** Returns false when it wasn't written (no link, or the ring is full). */
	public synchronized boolean pushEvent(int type, int entId, long steamId, float a, float b, float c, float d, int flags, int weapon, int requestId, int result) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		return pushMcEvent(s, SV_OFF_EVENT_RING, EVENT_RING_ENTRIES, SV_RING_EVENTS, type, entId, steamId, a, b, c, d, flags, weapon, requestId, result);
	}

	/**
	 * v42 kEvPistonMove: one event per moving cell, published together (one head store). False when
	 * nothing was written (no link, or the ring can't take them all: dropped whole, counted).
	 */
	public synchronized boolean pushPistonMove(dev.gmodcraft.world.PistonPush.Cell[] cells, int travelTicks, int worldId, int moveId) {
		MemorySegment s = segment();
		if (s == null || cells.length == 0) {
			return false;
		}
		long base = SV_OFF_EVENT_RING;
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head + cells.length - tail > EVENT_RING_ENTRIES) {
			countRing(s, SV_RING_EVENTS, 0, 0, cells.length, head - tail);
			return false;
		}
		java.nio.ByteBuffer slot = java.nio.ByteBuffer.allocate((int) MC_EVENT_BYTES);
		for (int i = 0; i < cells.length; i++) {
			dev.gmodcraft.world.PistonPush.encode(slot, cells[i], travelTicks, worldId, moveId);
			long e = base + ER_DATA + ((head + i) & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
			MemorySegment.copy(slot.array(), 0, s, JAVA_BYTE, e, slot.capacity());
		}
		LONG.setRelease(s, base + ER_HEAD, head + cells.length);
		countRing(s, SV_RING_EVENTS, cells.length, cells.length * MC_EVENT_BYTES, 0, head + cells.length - tail);
		return true;
	}

	/**
	 * kEvDevCommandResult + its kEvDevCommandText slots ({@code output}: UTF-8, at most
	 * kDevOutputMaxBytes), published together (one head store). Dropped whole if they don't fit.
	 */
	public synchronized void pushDevCommandResult(int requestId, int result, int count, byte[] output) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		int len = Math.min(output.length, DEV_OUTPUT_MAX_BYTES);
		int chunks = (len + DEV_OUTPUT_CHUNK_BYTES - 1) / DEV_OUTPUT_CHUNK_BYTES;
		long base = SV_OFF_EVENT_RING;
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head + 1 + chunks - tail > EVENT_RING_ENTRIES) {
			countRing(s, SV_RING_EVENTS, 0, 0, 1 + chunks, head - tail);
			return;
		}
		long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
		MemorySegment.copy(new byte[(int) MC_EVENT_BYTES], 0, s, JAVA_BYTE, e, (int) MC_EVENT_BYTES);
		s.set(JAVA_INT, e + ME_TYPE, EV_DEV_COMMAND_RESULT);
		s.set(JAVA_INT, e + ME_FLAGS, count);
		s.set(JAVA_INT, e + ME_WEAPON, chunks);
		s.set(JAVA_INT, e + ME_REQUEST_ID, requestId);
		s.set(JAVA_INT, e + ME_RESULT, result);
		for (int i = 0; i < chunks; i++) {
			long t = base + ER_DATA + ((head + 1 + i) & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
			byte[] slot = new byte[(int) MC_EVENT_BYTES];
			java.nio.ByteBuffer.wrap(slot).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, EV_DEV_COMMAND_TEXT);
			System.arraycopy(output, i * DEV_OUTPUT_CHUNK_BYTES, slot, (int) ME_TEXT, Math.min(DEV_OUTPUT_CHUNK_BYTES, len - i * DEV_OUTPUT_CHUNK_BYTES));
			MemorySegment.copy(slot, 0, s, JAVA_BYTE, t, slot.length);
		}
		LONG.setRelease(s, base + ER_HEAD, head + 1 + chunks);
		countRing(s, SV_RING_EVENTS, 1 + chunks, (1 + chunks) * MC_EVENT_BYTES, 0, head + 1 + chunks - tail);
	}

	/**
	 * v33 (P1) kEvPropRequest + its kEvPropText slots ({@code text}: the prop text, at most
	 * kPropTextMaxBytes; empty for a pickup), published together (one head store). False when it
	 * wasn't written (no link, or the ring can't take them all).
	 */
	public synchronized boolean pushPropRequest(long steamId, int requestId, int flags, int entId, float x, float y, float z, float yaw, int color, byte[] text) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		int len = Math.min(text.length, PROP_TEXT_MAX_BYTES);
		int chunks = (len + PROP_MC_CHUNK_BYTES - 1) / PROP_MC_CHUNK_BYTES;
		long base = SV_OFF_EVENT_RING;
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head + 1 + chunks - tail > EVENT_RING_ENTRIES) {
			countRing(s, SV_RING_EVENTS, 0, 0, 1 + chunks, head - tail);
			return false;
		}
		long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
		MemorySegment.copy(new byte[(int) MC_EVENT_BYTES], 0, s, JAVA_BYTE, e, (int) MC_EVENT_BYTES);
		s.set(JAVA_INT, e + ME_TYPE, EV_PROP_REQUEST);
		s.set(JAVA_INT, e + ME_ENT_ID, entId);
		s.set(JAVA_LONG, e + ME_STEAM_ID, steamId);
		s.set(JAVA_FLOAT, e + ME_A, x);
		s.set(JAVA_FLOAT, e + ME_B, y);
		s.set(JAVA_FLOAT, e + ME_C, z);
		s.set(JAVA_FLOAT, e + ME_D, yaw);
		s.set(JAVA_INT, e + ME_FLAGS, flags);
		s.set(JAVA_INT, e + ME_WEAPON, chunks);
		s.set(JAVA_INT, e + ME_REQUEST_ID, requestId);
		s.set(JAVA_INT, e + ME_RESULT, color);
		for (int i = 0; i < chunks; i++) {
			long t = base + ER_DATA + ((head + 1 + i) & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
			byte[] slot = new byte[(int) MC_EVENT_BYTES];
			java.nio.ByteBuffer.wrap(slot).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, EV_PROP_TEXT);
			System.arraycopy(text, i * PROP_MC_CHUNK_BYTES, slot, (int) ME_TEXT, Math.min(PROP_MC_CHUNK_BYTES, len - i * PROP_MC_CHUNK_BYTES));
			MemorySegment.copy(slot, 0, s, JAVA_BYTE, t, slot.length);
		}
		LONG.setRelease(s, base + ER_HEAD, head + 1 + chunks);
		countRing(s, SV_RING_EVENTS, 1 + chunks, (1 + chunks) * MC_EVENT_BYTES, 0, head + 1 + chunks - tail);
		return true;
	}

	/**
	 * v38 (S2) kEvStructData + its kEvStructText slots: {@code len} bytes of {@code text} from
	 * {@code offset} (at most kStructChunkBytes * kStructMaxChunks), published together. False when
	 * the ring would then hold more than {@code entries - reserve} (left for the other events) or
	 * there's no link: the caller tries again next tick.
	 */
	public synchronized boolean pushStructData(int requestId, byte[] text, int offset, int len, int reserve) {
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		len = Math.max(0, Math.min(len, Math.min(text.length - offset, STRUCT_CHUNK_BYTES * STRUCT_MAX_CHUNKS)));
		int chunks = Math.max(1, (len + STRUCT_CHUNK_BYTES - 1) / STRUCT_CHUNK_BYTES);
		long base = SV_OFF_EVENT_RING;
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head + 1 + chunks - tail > EVENT_RING_ENTRIES - Math.max(0, reserve)) {
			return false;
		}
		long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
		MemorySegment.copy(new byte[(int) MC_EVENT_BYTES], 0, s, JAVA_BYTE, e, (int) MC_EVENT_BYTES);
		s.set(JAVA_INT, e + ME_TYPE, EV_STRUCT_DATA);
		s.set(JAVA_FLOAT, e + ME_A, text.length);
		s.set(JAVA_INT, e + ME_FLAGS, offset);
		s.set(JAVA_INT, e + ME_WEAPON, chunks);
		s.set(JAVA_INT, e + ME_REQUEST_ID, requestId);
		for (int i = 0; i < chunks; i++) {
			long t = base + ER_DATA + ((head + 1 + i) & (EVENT_RING_ENTRIES - 1)) * MC_EVENT_BYTES;
			byte[] slot = new byte[(int) MC_EVENT_BYTES];
			java.nio.ByteBuffer.wrap(slot).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, EV_STRUCT_TEXT);
			int from = i * STRUCT_CHUNK_BYTES;
			if (from < len) {
				System.arraycopy(text, offset + from, slot, (int) ME_TEXT, Math.min(STRUCT_CHUNK_BYTES, len - from));
			}
			MemorySegment.copy(slot, 0, s, JAVA_BYTE, t, slot.length);
		}
		LONG.setRelease(s, base + ER_HEAD, head + 1 + chunks);
		countRing(s, SV_RING_EVENTS, 1 + chunks, (1 + chunks) * MC_EVENT_BYTES, 0, head + 1 + chunks - tail);
		return true;
	}

	/** kEvWorldOpened: McServerInfo was just rewritten (an answer to {@code requestId}, or 0). */
	public void pushWorldOpened(int requestId, int result, int port, int infoFlags) {
		pushEvent(EV_WORLD_OPENED, 0, 0L, port, 0.0F, 0.0F, 0.0F, infoFlags, 0, requestId, result);
	}

	// ---- McServerInfo (write, v14) ---------------------------------------------------------

	/** How other players reach this Minecraft server (see McServerInfo in the protocol header). */
	public record ServerInfo(int flags, int port, int requestId, int maxPlayers, String lanAddress, String e4mcAddress) {
	}

	/** Seqlock write of McServerInfo. Server thread only. */
	public void writeMcServerInfo(ServerInfo info) {
		MemorySegment s = segment();
		if (s == null) {
			return;
		}
		long b = SV_OFF_MC_SERVER_INFO;
		int seq = s.get(JAVA_INT, b + SI_SEQ);
		INT.setRelease(s, b + SI_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + SI_FLAGS, info.flags());
		s.set(JAVA_INT, b + SI_PORT, info.port());
		s.set(JAVA_INT, b + SI_REQUEST_ID, info.requestId());
		s.set(JAVA_INT, b + SI_MAX_PLAYERS, info.maxPlayers());
		writeString(s, b + SI_LAN_ADDRESS, (int) SERVER_INFO_ADDRESS_BYTES, info.lanAddress());
		writeString(s, b + SI_E4MC_ADDRESS, (int) SERVER_INFO_ADDRESS_BYTES, info.e4mcAddress());
		INT.setRelease(s, b + SI_SEQ, seq + 2);
	}

	// ---- weapon sets (write, v17 hybrid mode) ----------------------------------------------

	/** Seqlock write of weapon set {@code index} (McPlayers order). Server thread only. */
	public void writeWeaponSet(int index, WeaponSetCodec.Set set) {
		MemorySegment s = segment();
		if (s != null) {
			WeaponSetCodec.writeSet(s, SV_OFF_MC_WEAPON_SETS, index, set);
		}
	}

	/** How many weapon sets are in use; written after the sets. Server thread only. */
	public void writeWeaponSetCount(int count) {
		MemorySegment s = segment();
		if (s != null) {
			WeaponSetCodec.writeCount(s, SV_OFF_MC_WEAPON_SETS, count);
		}
	}

	// ---- per-player water grids (read, v14) ------------------------------------------------

	/** The seq of player slot {@code slot}'s water grid (0: never written), or 0 without a link. */
	public int waterGridSeq(int slot) {
		MemorySegment s = segment();
		return s == null || slot < 0 || slot >= MAX_PLAYERS ? 0 : (int) INT.getAcquire(s, SV_OFF_WATER_GRIDS + slot * WATER_GRID_BYTES + WG_SEQ);
	}

	/** The seq of the deprecated single grid @kSvOffWaterGrid (0: never written). */
	public int legacyWaterGridSeq() {
		MemorySegment s = segment();
		return s == null ? 0 : (int) INT.getAcquire(s, SV_OFF_WATER_GRID + WG_SEQ);
	}

	/** A consistent copy of player slot {@code slot}'s water grid, or null (none written, or mid-write). */
	public @Nullable WaterGrid readWaterGrid(int slot) {
		if (slot < 0 || slot >= MAX_PLAYERS) {
			return null;
		}
		return readWaterGrid(SV_OFF_WATER_GRIDS + slot * WATER_GRID_BYTES);
	}

	// ---- block ring (produce) --------------------------------------------------------------

	private static final long BR_DATA_BYTES = SV_BLOCK_RING_BYTES - BR_DATA;

	/**
	 * Writes one block message ({@code header} then {@code body}) into the block ring of
	 * {@code session}. Returns false when it doesn't fit right now (the caller keeps it dirty and
	 * tries again next tick). Server thread only.
	 */
	public synchronized boolean writeBlock(Session session, int type, ByteBuffer header, @Nullable ByteBuffer body) {
		MemorySegment s = session.seg();
		int payload = header.remaining() + (body != null ? body.remaining() : 0);
		long msgBytes = (8 + payload + 7) & ~7L;
		long base = SV_OFF_BLOCK_RING;
		long head = s.get(JAVA_LONG, base + BR_HEAD);
		long tail = (long) LONG.getAcquire(s, base + BR_TAIL);
		long pos = head % BR_DATA_BYTES;
		long pad = pos + msgBytes > BR_DATA_BYTES ? BR_DATA_BYTES - pos : 0;
		if (BR_DATA_BYTES - (head - tail) < msgBytes + pad) {
			countRing(s, SV_RING_BLOCKS, 0, 0, 1, head - tail);
			return false;
		}
		if (pad > 0) {
			s.set(JAVA_INT, base + BR_DATA + pos, BLK_PAD);
			s.set(JAVA_INT, base + BR_DATA + pos + 4, 0);
			head += pad;
			pos = 0;
		}
		long at = base + BR_DATA + pos;
		s.set(JAVA_INT, at, type);
		s.set(JAVA_INT, at + 4, payload);
		int h = header.remaining();
		MemorySegment.copy(MemorySegment.ofBuffer(header), 0, s, at + 8, h);
		if (body != null && body.remaining() > 0) {
			MemorySegment.copy(MemorySegment.ofBuffer(body), 0, s, at + 8 + h, body.remaining());
		}
		LONG.setRelease(s, base + BR_HEAD, head + msgBytes);
		countRing(s, SV_RING_BLOCKS, 1, msgBytes + pad, 0, head + msgBytes - tail);
		return true;
	}
}
