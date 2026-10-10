package dev.gmodcraft.link;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.gmodcraft.GmodCraft;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One shared-memory link to the GMod host, on Linux: a POSIX shm object in /dev/shm that the
 * host creates and Minecraft maps. Two of these exist ({@link ClientLink}, {@link ServerLink}); see
 * protocol/gmodcraft_protocol.h for the layout and for how a link is found (discovery file or an
 * explicit name), kept alive (heartbeats that keep changing) and replaced (new session nonce).
 *
 * <p>The current mapping is published as one immutable {@link Session} behind a single volatile:
 * code that touches the mapping reads {@link #session()} ONCE and uses that record throughout, so
 * a relink in the middle can't pair one session's segment with another's generation or ring
 * positions. Old mappings stay valid (automatic arena) as long as someone holds them.
 *
 * <p>{@link #poll()} is called from one thread only (the render thread for the client link, the
 * server thread for the server link). Everything else is safe from any thread.
 */
public abstract class GLink {
	protected static final VarHandle INT = JAVA_INT.varHandle();
	protected static final VarHandle LONG = JAVA_LONG.varHandle();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long RETRY_NS = 1_000_000_000L;

	/** One mapped host session: the segment, its generation (bumps per new session) and nonce. */
	public record Session(MemorySegment seg, int generation, long nonce) {
	}

	private final String label;           // "client" / "server": discovery file name, logs
	private final int kind;               // Proto.LINK_*
	private final long mappingBytes;
	private final @Nullable String override; // shm name or absolute path, from -D / env

	private volatile @Nullable Session session;
	private int generationCounter;
	private String linkedTo = "";
	// Liveness: the host's heartbeat must keep changing (observer clock, no cross-clock compare).
	private long lastHostBeat;
	private volatile long lastBeatChangeNs;
	private volatile boolean everBeat;
	private boolean wasActive;
	private int peerDownCount;
	private long attachNs;
	private long statsUpdates;
	private long lastRateNs;
	private final long[] lastRateBytes = new long[MAX_STAT_RINGS];
	private long lastAttemptNs = System.nanoTime() - RETRY_NS;
	private String lastProblem = "";

	// Filled in by the code that owns this side (SkyClient / ServerHost); published by poll().
	private volatile float statTickMs;
	private volatile float statFrameMs;

	protected GLink(String label, int kind, long mappingBytes, String property, String env) {
		this.label = label;
		this.kind = kind;
		this.mappingBytes = mappingBytes;
		String o = System.getProperty(property);
		if (o == null || o.isBlank()) {
			o = System.getenv(env);
		}
		this.override = o == null || o.isBlank() ? null : o.trim();
	}

	/** CLOCK_MONOTONIC nanoseconds: System.nanoTime() is clock_gettime(CLOCK_MONOTONIC) on Linux. */
	public static long nowNs() {
		return System.nanoTime();
	}

	/**
	 * /dev/shm/gmodcraft: where the discovery files and the running-Minecraft lock live. Not
	 * $XDG_RUNTIME_DIR: GMod runs in a pressure-vessel container whose /run/user/&lt;uid&gt; is a private
	 * tmpfs; /dev/shm is shared with the host.
	 */
	public static Path discoveryDir() {
		return DISCOVERY_DIR;
	}

	private static final Path DISCOVERY_DIR = Path.of("/dev/shm", "gmodcraft");

	public String label() {
		return this.label;
	}

	/** The explicit link name (-D / env), or null when the discovery file decides. */
	public @Nullable String override() {
		return this.override;
	}

	/** The current session (whether or not its host is still alive), or null. Read it once per use. */
	public @Nullable Session session() {
		return this.session;
	}

	/** The mapping if it has been opened. Prefer {@link #session()} when you also need the generation. */
	public @Nullable MemorySegment segment() {
		Session s = this.session;
		return s == null ? null : s.seg();
	}

	/** Bumps whenever a (new) host session is on the other end: everything it caches must be resent. */
	public int generation() {
		Session s = this.session;
		return s == null ? 0 : s.generation();
	}

	/** True while a live host is on the other end. Cheap; safe from any thread. */
	public boolean active() {
		return this.session != null && this.everBeat && nowNs() - this.lastBeatChangeNs < HEARTBEAT_TIMEOUT_MS * 1_000_000L;
	}

	/** The LinkStats region of this link (Proto.CL_OFF_LINK_STATS / SV_OFF_LINK_STATS). */
	protected abstract long statsOffset();

	/** This side's tick and frame time for the debug panels (ms; 0 = not applicable). */
	public void setTiming(float tickMs, float frameMs) {
		this.statTickMs = tickMs;
		this.statFrameMs = frameMs;
	}

	/** Called regularly from this link's thread: heartbeat, liveness, (re)linking, stats. */
	public void poll() {
		Session cur = this.session;
		long now = nowNs();
		if (cur != null) {
			MemorySegment s = cur.seg();
			LONG.setRelease(s, H_MC_HEARTBEAT_NS, now);
			long beat = (long) LONG.getAcquire(s, H_HOST_HEARTBEAT_NS);
			if (beat != this.lastHostBeat) {
				this.lastHostBeat = beat;
				this.lastBeatChangeNs = now;
				if (!this.everBeat) {
					this.everBeat = true;
					GmodCraft.LOG.info("GmodCraft: {} link up ({})", this.label, this.linkedTo);
				}
			}
			boolean alive = active();
			if (this.wasActive && !alive) {
				this.peerDownCount++;
				GmodCraft.LOG.info("GmodCraft: {} link: GMod's heartbeat stopped (no change for {} ms)", this.label, HEARTBEAT_TIMEOUT_MS);
			}
			this.wasActive = alive;
			writeSideStats(cur, now, alive);
			if (now - this.lastBeatChangeNs < RETRY_NS) {
				return;
			}
		}
		// Not linked, or the host has been quiet for a second (it may be gone already: a map change
		// or a GMod restart makes a new session long before our heartbeat timeout): look, once a
		// second, whether the discovery file (or the fixed name) now names another session. While
		// it still names ours, tryOpen does nothing.
		if (now - this.lastAttemptNs < RETRY_NS) {
			return;
		}
		this.lastAttemptNs = now;
		tryOpen(cur);
	}

	private void tryOpen(@Nullable Session current) {
		if ("none".equalsIgnoreCase(this.override)) {
			problem("disabled (link name \"none\")");
			return;
		}
		Path path;
		long expectNonce = 0;
		boolean checkNonce = false;
		if (this.override != null) {
			path = this.override.startsWith("/") ? Path.of(this.override) : Path.of("/dev/shm", this.override);
		} else {
			Path discovery = discoveryDir().resolve(this.label + ".json");
			JsonObject json;
			try {
				json = JsonParser.parseString(Files.readString(discovery, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (NoSuchFileException e) {
				problem("no host yet (" + discovery + " doesn't exist)");
				return;
			} catch (Exception e) {
				problem("unreadable discovery file " + discovery + ": " + e);
				return;
			}
			try {
				path = Path.of("/dev/shm", json.get("shm").getAsString());
				expectNonce = Long.parseUnsignedLong(json.get("nonce").getAsString(), 16);
				checkNonce = true;
			} catch (Exception e) {
				problem("bad discovery file " + discovery + ": " + json);
				return;
			}
		}
		if (current != null && checkNonce && expectNonce == current.nonce()) {
			return; // the discovery file still names the session we have (its host is just quiet)
		}
		MemorySegment seg;
		long nonce;
		try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			long size = ch.size();
			if (size < this.mappingBytes) {
				// Mapping READ_WRITE past the end would silently grow the host's object.
				problem(path + " is " + size + " bytes, expected " + this.mappingBytes);
				return;
			}
			// Look at the header alone first: while a quiet host is polled every second, nothing
			// big gets mapped unless it really is a new session.
			try (Arena peek = Arena.ofConfined()) {
				MemorySegment h = ch.map(FileChannel.MapMode.READ_ONLY, 0, LINK_HEADER_BYTES, peek);
				int magic = (int) INT.getAcquire(h, H_MAGIC);
				int version = h.get(JAVA_INT, H_VERSION);
				int linkKind = h.get(JAVA_INT, H_LINK_KIND);
				long bytes = h.get(JAVA_LONG, H_MAPPING_BYTES);
				nonce = h.get(JAVA_LONG, H_SESSION_NONCE);
				if (magic != MAGIC || version != VERSION || linkKind != this.kind || bytes != this.mappingBytes) {
					problem(String.format("%s: protocol mismatch (magic %08x version %d kind %d bytes %d); expected %08x v%d kind %d bytes %d", path, magic, version,
						linkKind, bytes, MAGIC, VERSION, this.kind, this.mappingBytes));
					return;
				}
			}
			if (checkNonce && nonce != expectNonce) {
				problem(path + ": session nonce " + Long.toHexString(nonce) + " doesn't match the discovery file's " + Long.toHexString(expectNonce));
				return;
			}
			if (current != null && nonce == current.nonce()) {
				return; // same session, still quiet
			}
			// An automatic arena: the mapping lives as long as anything references it, so a thread
			// mid-read keeps an old mapping valid after a relink swaps in a new one.
			seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, this.mappingBytes, Arena.ofAuto());
		} catch (NoSuchFileException e) {
			problem(path + " doesn't exist");
			return;
		} catch (IOException e) {
			problem("can't map " + path + ": " + e);
			return;
		}
		seg.set(JAVA_LONG, H_MC_NONCE, RANDOM.nextLong());
		this.lastHostBeat = (long) LONG.getAcquire(seg, H_HOST_HEARTBEAT_NS);
		this.everBeat = false; // alive once the heartbeat is seen to change
		this.wasActive = false;
		long now = nowNs();
		LONG.setRelease(seg, H_MC_HEARTBEAT_NS, now);
		this.linkedTo = path + ", session " + Long.toHexString(nonce);
		this.attachNs = now;
		java.util.Arrays.fill(this.lastRateBytes, 0L);
		Session next = new Session(seg, ++this.generationCounter, nonce);
		onNewSession(next);
		this.session = next; // the one publication point
		this.lastProblem = "";
		GmodCraft.LOG.info("GmodCraft: {} link mapped: {}", this.label, this.linkedTo);
	}

	/** A new host session was mapped (before it is published): reset per-session state. */
	protected void onNewSession(Session next) {
	}

	private void problem(String what) {
		if (!what.equals(this.lastProblem)) {
			this.lastProblem = what;
			GmodCraft.LOG.info("GmodCraft: {} link: {}", this.label, what);
		}
	}

	// ---- link stats (MC half) ----------------------------------------------------------------

	private long mcStats() {
		return statsOffset() + LS_MC;
	}

	private void writeSideStats(Session cur, long now, boolean alive) {
		MemorySegment s = cur.seg();
		long b = mcStats();
		s.set(JAVA_INT, b + SD_PROTOCOL_VERSION, VERSION);
		s.set(JAVA_INT, b + SD_ATTACH_COUNT, cur.generation());
		s.set(JAVA_LONG, b + SD_SESSION_NONCE, cur.nonce());
		s.set(JAVA_LONG, b + SD_ATTACH_NS, this.attachNs);
		s.set(JAVA_INT, b + SD_PEER_DOWN_COUNT, this.peerDownCount);
		s.set(JAVA_INT, b + SD_FLAGS, alive ? SIDE_PEER_ALIVE : 0);
		s.set(JAVA_FLOAT, b + SD_TICK_MS, this.statTickMs);
		s.set(JAVA_FLOAT, b + SD_FRAME_MS, this.statFrameMs);
		if (now - this.lastRateNs >= 1_000_000_000L) {
			// Bytes per second, per ring (the ring counters themselves are written where they happen).
			double secs = this.lastRateNs == 0 ? 1.0 : (now - this.lastRateNs) / 1e9;
			for (int r = 0; r < MAX_STAT_RINGS; r++) {
				long at = b + SD_RINGS + r * RING_STATS_BYTES;
				long bytes = s.get(JAVA_LONG, at + RS_BYTES);
				s.set(JAVA_LONG, at + RS_BYTES_PER_SEC, (long) ((bytes - this.lastRateBytes[r]) / secs));
				this.lastRateBytes[r] = bytes;
			}
			this.lastRateNs = now;
		}
		s.set(JAVA_LONG, b + SD_UPDATE_COUNT, ++this.statsUpdates);
	}

	/**
	 * Counts ring traffic in this side's half of LinkStats. Call it from the one thread (or under
	 * the one lock) that drives that ring on this side, so each field keeps a single writer.
	 */
	protected void countRing(MemorySegment s, int ring, long messages, long bytes, long drops, long fill) {
		long at = mcStats() + SD_RINGS + ring * RING_STATS_BYTES;
		if (messages != 0) {
			s.set(JAVA_LONG, at + RS_MESSAGES, s.get(JAVA_LONG, at + RS_MESSAGES) + messages);
		}
		if (bytes != 0) {
			s.set(JAVA_LONG, at + RS_BYTES, s.get(JAVA_LONG, at + RS_BYTES) + bytes);
		}
		if (drops != 0) {
			s.set(JAVA_LONG, at + RS_DROPS, s.get(JAVA_LONG, at + RS_DROPS) + drops);
		}
		s.set(JAVA_LONG, at + RS_FILL, fill);
		if (fill > s.get(JAVA_LONG, at + RS_HIGH_WATER)) {
			s.set(JAVA_LONG, at + RS_HIGH_WATER, fill);
		}
	}

	/** Sets a whole-side counter in this side's half (e.g. overlay frames). */
	protected void setSideLong(MemorySegment s, long field, long value) {
		s.set(JAVA_LONG, mcStats() + field, value);
	}

	// ---- shared region helpers ---------------------------------------------------------------

	/** One nearby host actor (see ActorRecord in the protocol header). */
	public record Actor(int entId, int flags, float x, float y, float z, float yaw, float width, float height, float healthFrac, int tier, String name) {
		public boolean dead() {
			return (this.flags & ACTOR_DEAD) != 0;
		}

		public boolean hostile() {
			return (this.flags & ACTOR_HOSTILE) != 0;
		}
	}

	/** Seqlock read of an actor table at {@code base}. Returns false (leaving {@code out} empty) on a torn read. */
	protected boolean readActors(long base, List<Actor> out) {
		out.clear();
		MemorySegment s = segment();
		if (s == null) {
			return false;
		}
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = (int) INT.getAcquire(s, base + AT_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(Math.max(s.get(JAVA_INT, base + AT_COUNT), 0), MAX_ACTORS);
			for (int i = 0; i < count; i++) {
				long r = base + AT_RECORDS + i * ACTOR_RECORD_BYTES;
				out.add(new Actor(
					s.get(JAVA_INT, r + AR_ENT_ID), s.get(JAVA_INT, r + AR_FLAGS),
					s.get(JAVA_FLOAT, r + AR_X), s.get(JAVA_FLOAT, r + AR_Y), s.get(JAVA_FLOAT, r + AR_Z),
					s.get(JAVA_FLOAT, r + AR_YAW), s.get(JAVA_FLOAT, r + AR_WIDTH), s.get(JAVA_FLOAT, r + AR_HEIGHT),
					s.get(JAVA_FLOAT, r + AR_HEALTH_FRAC), Short.toUnsignedInt(s.get(JAVA_SHORT, r + AR_TIER)), readString(s, r + AR_NAME, (int) ACTOR_NAME_BYTES)
				));
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, base + AT_SEQ) == seq1) {
				return true;
			}
			out.clear();
		}
		return false;
	}

	/** The host's water surface around the player(s) (see WaterGrid in the protocol). */
	public static final class WaterGrid {
		public int originX, originZ, worldId;
		public final int size = WATER_GRID_SIZE;
		public final float[] surface = new float[WATER_GRID_SIZE * WATER_GRID_SIZE];
	}

	/** A consistent copy of the water grid at {@code base}, or null (no link, none written, or mid-write). */
	protected @Nullable WaterGrid readWaterGrid(long base) {
		MemorySegment s = segment();
		if (s == null) {
			return null;
		}
		WaterGrid out = new WaterGrid();
		for (int attempt = 0; attempt < 100; attempt++) {
			int seq1 = (int) INT.getAcquire(s, base + WG_SEQ);
			if (seq1 == 0) {
				return null; // the host hasn't written one yet
			}
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			out.originX = s.get(JAVA_INT, base + WG_ORIGIN_X);
			out.originZ = s.get(JAVA_INT, base + WG_ORIGIN_Z);
			out.worldId = s.get(JAVA_INT, base + WG_WORLD_ID);
			for (int i = 0; i < out.surface.length; i++) {
				out.surface[i] = s.get(JAVA_FLOAT, base + WG_SURFACE + i * 4L);
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, base + WG_SEQ) == seq1) {
				return out;
			}
		}
		return null;
	}

	// ---- collision ring (consumer side) ------------------------------------------------------

	/** Collision ring of this link: its offset in the mapping and its size. */
	public abstract long collisionRingOffset();

	public abstract long collisionRingBytes();

	/** The ring index of the collision ring in LinkStats (same on both links). */
	public static final int COLLISION_STAT_RING = CL_RING_COLLISION;

	public long collisionHead(MemorySegment s) {
		return (long) LONG.getAcquire(s, collisionRingOffset() + CR_HEAD);
	}

	public long collisionTail(MemorySegment s) {
		return s.get(JAVA_LONG, collisionRingOffset() + CR_TAIL);
	}

	/** Consumer: everything up to {@code tail} has been read ({@code messages} of them, {@code bytes} long). */
	public void setCollisionTail(MemorySegment s, long tail, long messages, long bytes) {
		LONG.setRelease(s, collisionRingOffset() + CR_TAIL, tail);
		countRing(s, COLLISION_STAT_RING, messages, bytes, 0, collisionHead(s) - tail);
	}

	// ---- MC event rings (server link: kSvOffEventRing; client link, v14: kClOffEventRing) ----

	/**
	 * Queues one McEvent on the ring at {@code base} ({@code entries} long, LinkStats ring
	 * {@code statRing}). Drops it (counted) when the host is a full ring behind. The caller holds
	 * the link's producer lock.
	 */
	/** Returns false when the ring is full (the event is dropped and counted). */
	protected boolean pushMcEvent(MemorySegment s, long base, int entries, int statRing, int type, int entId, long steamId, float a, float b, float c, float d,
		int flags, int weapon, int requestId, int result) {
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head - tail >= entries) {
			countRing(s, statRing, 0, 0, 1, head - tail);
			return false;
		}
		long e = base + ER_DATA + (head & (entries - 1)) * MC_EVENT_BYTES;
		s.set(JAVA_INT, e + ME_TYPE, type);
		s.set(JAVA_INT, e + ME_ENT_ID, entId);
		s.set(JAVA_LONG, e + ME_STEAM_ID, steamId);
		s.set(JAVA_FLOAT, e + ME_A, a);
		s.set(JAVA_FLOAT, e + ME_B, b);
		s.set(JAVA_FLOAT, e + ME_C, c);
		s.set(JAVA_FLOAT, e + ME_D, d);
		s.set(JAVA_INT, e + ME_FLAGS, flags);
		s.set(JAVA_INT, e + ME_WEAPON, weapon);
		s.set(JAVA_INT, e + ME_REQUEST_ID, requestId);
		s.set(JAVA_INT, e + ME_RESULT, result);
		LONG.setRelease(s, base + ER_HEAD, head + 1);
		countRing(s, statRing, 1, MC_EVENT_BYTES, 0, head + 1 - tail);
		return true;
	}

	// ---- strings and UUIDs -------------------------------------------------------------------

	protected static String readString(MemorySegment s, long off, int max) {
		byte[] bytes = new byte[max];
		int n = 0;
		while (n < max) {
			byte b = s.get(JAVA_BYTE, off + n);
			if (b == 0) {
				break;
			}
			bytes[n++] = b;
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	/** Writes a NUL-terminated, NUL-padded UTF-8 string (truncated to max - 1 bytes). */
	protected static void writeString(MemorySegment s, long off, int max, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		int n = Math.min(bytes.length, max - 1);
		for (int i = 0; i < max; i++) {
			s.set(JAVA_BYTE, off + i, i < n ? bytes[i] : 0);
		}
	}

	/** A UUID as 16 big-endian bytes (most significant byte first). */
	protected static void writeUuid(MemorySegment s, long off, @Nullable UUID uuid) {
		long msb = uuid == null ? 0 : uuid.getMostSignificantBits(), lsb = uuid == null ? 0 : uuid.getLeastSignificantBits();
		for (int k = 0; k < 8; k++) {
			s.set(JAVA_BYTE, off + k, (byte) (msb >>> (56 - 8 * k)));
			s.set(JAVA_BYTE, off + 8 + k, (byte) (lsb >>> (56 - 8 * k)));
		}
	}

	/** Reads 16 big-endian bytes; null when all are zero. */
	protected static @Nullable UUID readUuid(MemorySegment s, long off) {
		long msb = 0, lsb = 0;
		for (int k = 0; k < 8; k++) {
			msb = (msb << 8) | (s.get(JAVA_BYTE, off + k) & 0xFF);
			lsb = (lsb << 8) | (s.get(JAVA_BYTE, off + 8 + k) & 0xFF);
		}
		return msb == 0 && lsb == 0 ? null : new UUID(msb, lsb);
	}
}
