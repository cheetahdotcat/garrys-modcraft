package dev.gmodcraft.world;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.ServerLink;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The host's world geometry as Minecraft sees it: an 8x8x8 sub-voxel collision shape per block
 * position, plus the exact triangles, streamed from the GMod host over a link's collision ring.
 * These are not blocks; they are merged into block collision queries (see BlockCollisionsMixin) so
 * vanilla movement code collides with them.
 *
 * <p>There is one store per link: {@link #CLIENT} (fed by the client link, used by client-side
 * code) and {@link #SERVER} (fed by the server link, used by server-side code). An integrated
 * server has both; a dedicated server only fills SERVER; a client on a remote server only CLIENT.
 */
public final class SkyCollision {
	/** Host regions are streamed as cubes of this many blocks (Proto.COL_REGION_SIZE). */
	public static final int REGION_SIZE = COL_REGION_SIZE;

	public static final SkyCollision CLIENT = new SkyCollision(ClientLink.INSTANCE);
	public static final SkyCollision SERVER = new SkyCollision(ServerLink.INSTANCE);

	/** The store for one side. */
	public static SkyCollision of(boolean clientSide) {
		return clientSide ? CLIENT : SERVER;
	}

	/** The store for a level's side. */
	public static SkyCollision of(net.minecraft.world.level.Level level) {
		return level.isClientSide() ? CLIENT : SERVER;
	}

	/**
	 * The store for a block getter that may not be a Level. The side is threaded through wherever
	 * the getter knows it:
	 * <ul>
	 * <li>a {@link net.minecraft.world.level.LevelReader} (Level, WorldGenRegion, ...) says
	 * {@code isClientSide()};</li>
	 * <li>a {@link net.minecraft.world.level.PathNavigationRegion} (mob path finding) carries the
	 * Level it was cut from.</li>
	 * </ul>
	 * Anything else (no known way to tell its side) falls back to a HEURISTIC: the server store
	 * when the server link has data, else the client store (a client on a remote server has only
	 * that). In an integrated server with both links up that picks SERVER even for a client-side
	 * caller; both stores then hold the same host geometry, from the same GMod session, so the
	 * answer only differs while one link lags the other. Callers that know their side should use
	 * {@link #of(boolean)} or {@link #of(net.minecraft.world.level.Level)} instead.
	 */
	public static SkyCollision of(net.minecraft.world.level.@Nullable BlockGetter getter) {
		if (getter instanceof net.minecraft.world.level.LevelReader reader) {
			return of(reader.isClientSide());
		}
		if (getter instanceof dev.gmodcraft.mixin.PathNavigationRegionAccessor region && region.gmodcraft$level() != null) {
			return of(region.gmodcraft$level());
		}
		return SERVER.active() || !CLIENT.active() ? SERVER : CLIENT;
	}

	private final GLink link;
	private int linkGeneration;

	private final ConcurrentHashMap<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();
	// Per block: sub-voxel count (bits 0-9), any in the lower half (bit 10), any in the upper half (bit 11).
	private final ConcurrentHashMap<Long, Integer> FILL = new ConcurrentHashMap<>();
	private static final int FILL_LOWER = 1 << 10;
	private static final int FILL_UPPER = 1 << 11;
	private static final int FILL_TOP_SHIFT = 12; // highest occupied of the 8 voxel layers (3 bits)
	private final ConcurrentHashMap<Long, SkyTri[]> TRIS = new ConcurrentHashMap<>();
	// Regions holding at least one dynamic (host entity) triangle: a cheap test before a triangle query.
	private final java.util.Set<Long> DYNAMIC_REGIONS = ConcurrentHashMap.newKeySet();
	// Diggable surfaces as they were before blocks were dug out of them (Proto.TRI_GHOST).
	private final ConcurrentHashMap<Long, SkyTri[]> GHOSTS = new ConcurrentHashMap<>();
	// A hash of each region's triangles as last received, and the regions whose triangles changed
	// since the client last looked (the walls of dug holes are drawn from them).
	private final ConcurrentHashMap<Long, Long> TRI_HASH = new ConcurrentHashMap<>();
	private final java.util.concurrent.ConcurrentLinkedQueue<Long> CHANGED = new java.util.concurrent.ConcurrentLinkedQueue<>();
	// Z1: the same for the static triangles only (what WorldExporter culls block faces against), so
	// moving props don't re-mesh sections. Client side only: nothing polls the server's.
	private final ConcurrentHashMap<Long, Long> STATIC_HASH = new ConcurrentHashMap<>();
	private final java.util.concurrent.ConcurrentLinkedQueue<Long> STATIC_CHANGED = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private volatile int clears;

	/** Regions (min corner, as BlockPos longs) whose static triangles changed since the last call (client side). */
	public void takeStaticChangedRegions(java.util.function.LongConsumer out) {
		Long key;
		while ((key = STATIC_CHANGED.poll()) != null) {
			out.accept(key);
		}
	}

	/** Goes up each time every triangle is dropped (a new epoch): faces culled against them come back. */
	public int clearCount() {
		return this.clears;
	}

	/** Regions (min corner, as BlockPos longs) whose triangles changed since the last call. */
	public void takeChangedRegions(java.util.function.LongConsumer out) {
		Long key;
		while ((key = CHANGED.poll()) != null) {
			out.accept(key);
		}
	}
	private static volatile java.util.function.Predicate<net.minecraft.world.entity.Entity> smoothCollider = e -> false;
	private final Set<Long> KNOWN_REGIONS = ConcurrentHashMap.newKeySet();
	private volatile int epoch = -1;
	private Thread consumer;

	/** Map ground per cell (M1: snow, friction, spawn rules), cached and dropped with changed regions. */
	public final SkyGround ground = new SkyGround(this::trianglesNear, REGION_SIZE);

	private SkyCollision(GLink link) {
		this.link = link;
	}

	public GLink link() {
		return this.link;
	}

	public @Nullable VoxelShape shapeAt(BlockPos pos) {
		return SHAPES.isEmpty() ? null : SHAPES.get(pos.asLong());
	}

	/** Entities (the local player) that collide with Skyrim's exact triangles instead of its voxels. */
	public static void setSmoothCollider(java.util.function.Predicate<net.minecraft.world.entity.Entity> predicate) {
		smoothCollider = predicate;
	}

	public static boolean usesSmoothCollider(net.minecraft.world.entity.@Nullable Entity entity) {
		return entity != null && smoothCollider.test(entity);
	}

	/** Adds every Skyrim triangle whose bounds overlap {@code box}. */
	public void trianglesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (TRIS.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = TRIS.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/**
	 * True if any region overlapping the box (block coordinates) holds a dynamic triangle: a set lookup per
	 * region, no triangle test. False means {@link #trianglesNear} would find no dynamic triangle there.
	 */
	public boolean hasDynamicNear(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (DYNAMIC_REGIONS.isEmpty()) {
			return false;
		}
		int rx0 = Math.floorDiv((int) Math.floor(minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					if (DYNAMIC_REGIONS.contains(regionKey(rx, ry, rz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Every Skyrim surface whose bounds overlap {@code box} as it was before anything was dug out
	 * of it: what's behind these is inside Skyrim's geometry (SkyDig).
	 */
	public void originalSurfacesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		trianglesNear(box, out);
		near(GHOSTS, box, out);
	}

	private void near(ConcurrentHashMap<Long, SkyTri[]> store, net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (store.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = store.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/** True once Skyrim has sent the region containing this block (even if it was empty). */
	public boolean isKnown(int x, int y, int z) {
		return KNOWN_REGIONS.contains(regionKey(Math.floorDiv(x, REGION_SIZE), Math.floorDiv(y, REGION_SIZE), Math.floorDiv(z, REGION_SIZE)));
	}

	/**
	 * May the host's ground be what holds up a body whose feet are at (x, y, z)? True when this store
	 * hasn't got that region (it can't tell) or has geometry in the 3x3 column from the feet block
	 * one block down. A client collides with the exact triangles, this store only with 1/8-block
	 * voxels, so a server-side "nothing under the feet" is no evidence there.
	 */
	public boolean mayStandOnHost(double x, double y, double z) {
		int bx = (int) Math.floor(x), by = (int) Math.floor(y - 0.05), bz = (int) Math.floor(z);
		return !isKnown(bx, by, bz) || hasSolidBelow(bx, by, bz, 1);
	}

	/** True if any Skyrim geometry exists in the 3x3 column below (x, y, z), down to {@code depth} blocks. */
	public boolean hasSolidBelow(int x, int y, int z, int depth) {
		for (int dy = 0; dy <= depth; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (SHAPES.containsKey(BlockPos.asLong(x + dx, y - dy, z + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** How many of this block's 512 sub-voxels are Skyrim geometry (0 .. 512). */
	public int solidCount(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0 : fill & 0x3FF;
	}

	/** Fraction (0..1) of this block's volume that is Skyrim geometry. */
	public float solidFraction(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (fill & 0x3FF) / 512.0F;
	}

	/** True if any Skyrim geometry is in this cell. */
	public boolean hasGeometry(BlockPos pos) {
		return !FILL.isEmpty() && FILL.containsKey(pos.asLong());
	}

	/**
	 * How high (0..1) Skyrim geometry reaches in this cell: the top of its highest part. Terrain
	 * arrives as a thin surface, so what lies below that surface counts as ground too.
	 */
	public float groundTop(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (((fill >> FILL_TOP_SHIFT) & 7) + 1) / 8.0F;
	}

	/** True if Skyrim ground holds up whatever is in this cell (terrain in its lower half or the top of the cell below). */
	public boolean supportsFromBelow(BlockPos pos) {
		if (FILL.isEmpty()) {
			return false;
		}
		Integer here = FILL.get(pos.asLong());
		if (here != null && (here & FILL_LOWER) != 0) {
			return true;
		}
		Integer below = FILL.get(BlockPos.asLong(pos.getX(), pos.getY() - 1, pos.getZ()));
		return below != null && (below & FILL_UPPER) != 0;
	}

	public int blockCount() {
		return SHAPES.size();
	}

	public int regionCount() {
		return KNOWN_REGIONS.size();
	}

	/** Skyrim is describing its world around the player (false in a plain Minecraft world). */
	public boolean active() {
		return !KNOWN_REGIONS.isEmpty();
	}

	private static long regionKey(int rx, int ry, int rz) {
		return BlockPos.asLong(rx, ry, rz);
	}

	public synchronized void startConsumer() {
		if (consumer != null) {
			return;
		}
		consumer = new Thread(this::consumeLoop, "GmodCraft " + this.link.label() + " collision");
		consumer.setDaemon(true);
		consumer.start();
	}

	private void consumeLoop() {
		while (true) {
			try {
				if (!drainOnce()) {
					Thread.sleep(2);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				GmodCraft.LOG.error("GmodCraft: collision consumer error", t);
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/** Processes all pending collision messages. Returns true if anything was consumed. */
	private boolean drainOnce() {
		// One read of the session: segment, generation and ring positions all belong to it, even if
		// the link is remapped while this drains (the old mapping stays valid while we hold it).
		GLink.Session session = this.link.session();
		if (session == null) {
			return false;
		}
		MemorySegment s = session.seg();
		if (session.generation() != this.linkGeneration) {
			// A new host session: its ring starts empty and its epochs are its own.
			this.linkGeneration = session.generation();
			if (this == CLIENT) {
				dev.gmodcraft.weapon.WeaponIcons.clear(); // v18: the new session sends its icons again
			}
			clear(-1);
		}
		long head = this.link.collisionHead(s);
		long tail = this.link.collisionTail(s);
		if (tail >= head) {
			return false;
		}
		long startTail = tail;
		long messages = 0;
		long ringBytes = this.link.collisionRingBytes() - CR_DATA;
		long data = this.link.collisionRingOffset() + CR_DATA;
		while (tail < head) {
			long pos = tail % ringBytes;
			int type = s.get(JAVA_INT, data + pos);
			int payloadBytes = s.get(JAVA_INT, data + pos + 4);
			if (type == COL_PAD) {
				tail += ringBytes - pos;
				continue;
			}
			long payload = data + pos + 8;
			switch (type) {
				case COL_CLEAR -> clear(s.get(JAVA_INT, payload));
				case COL_REGION -> readRegion(s, payload);
				case COL_TRIS -> readTris(s, payload);
				case COL_WEAPON_ICON -> {
					// v18 hybrid mode: a GMod weapon icon (client link only; bounds checked in WeaponIcons)
					if (this == CLIENT) {
						dev.gmodcraft.weapon.WeaponIcons.accept(s, payload, Integer.toUnsignedLong(payloadBytes));
					}
				}
				default -> GmodCraft.LOG.warn("GmodCraft: unknown collision message {}", type);
			}
			tail += align8(8 + payloadBytes);
			messages++;
		}
		this.link.setCollisionTail(s, tail, messages, tail - startTail);
		return true;
	}

	private static long align8(long v) {
		return (v + 7) & ~7L;
	}

	/** A freshly started client joins whatever collision epoch Skyrim is already on. */
	private void adoptEpochIfFresh(int msgEpoch) {
		if (epoch == -1) {
			epoch = msgEpoch;
			GmodCraft.LOG.info("GmodCraft: {} collision joined epoch {} already in progress", this.link.label(), msgEpoch);
		}
	}

	private void clear(int newEpoch) {
		SHAPES.clear();
		FILL.clear();
		TRIS.clear();
		DYNAMIC_REGIONS.clear();
		GHOSTS.clear();
		TRI_HASH.clear();
		STATIC_HASH.clear();
		STATIC_CHANGED.clear();
		this.clears++;
		KNOWN_REGIONS.clear();
		epoch = newEpoch;
		SkyDig.wallsChanged(this);
		this.ground.clear();
		GmodCraft.LOG.info("GmodCraft: {} collision cleared (epoch {})", this.link.label(), newEpoch);
	}

	private void readRegion(MemorySegment s, long p) {
		int minX = s.get(JAVA_INT, p);
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8);
		int maxX = s.get(JAVA_INT, p + 12);
		int maxY = s.get(JAVA_INT, p + 16);
		int maxZ = s.get(JAVA_INT, p + 20);
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch) {
			return; // stale region from before a world change
		}

		// Build the new shapes first so readers never see a half-empty region.
		java.util.HashMap<Long, VoxelShape> fresh = new java.util.HashMap<>(count * 2);
		java.util.HashMap<Long, Integer> freshFill = new java.util.HashMap<>(count * 2);
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_BLOCK_BYTES) {
			int x = s.get(JAVA_INT, e);
			int y = s.get(JAVA_INT, e + 4);
			int z = s.get(JAVA_INT, e + 8);
			VoxelShape shape = buildShape(s, e + 16);
			if (shape != null) {
				long key = BlockPos.asLong(x, y, z);
				fresh.put(key, shape);
				freshFill.put(key, fillInfo(s, e + 16));
			}
		}

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					long key = BlockPos.asLong(x, y, z);
					VoxelShape shape = fresh.get(key);
					if (shape != null) {
						SHAPES.put(key, shape);
						FILL.put(key, freshFill.get(key));
					} else {
						SHAPES.remove(key);
						FILL.remove(key);
					}
				}
			}
		}

		for (int rx = Math.floorDiv(minX, REGION_SIZE); rx <= Math.floorDiv(maxX, REGION_SIZE); rx++) {
			for (int ry = Math.floorDiv(minY, REGION_SIZE); ry <= Math.floorDiv(maxY, REGION_SIZE); ry++) {
				for (int rz = Math.floorDiv(minZ, REGION_SIZE); rz <= Math.floorDiv(maxZ, REGION_SIZE); rz++) {
					if (KNOWN_REGIONS.add(regionKey(rx, ry, rz)) && this.link == ClientLink.INSTANCE) {
						// D1: first voxels here: dug cells' walls next to them are worked out again
						CHANGED.add(BlockPos.asLong(rx * REGION_SIZE, ry * REGION_SIZE, rz * REGION_SIZE));
					}
				}
			}
		}
	}

	private void readTris(MemorySegment s, long p) {
		int minX = s.get(JAVA_INT, p);
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8);
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch) {
			return;
		}
		SkyTri[] tris = new SkyTri[count];
		java.util.List<SkyTri> ghosts = new java.util.ArrayList<>();
		float[] v = new float[9];
		int kept = 0;
		long hash = count;
		long staticHash = 0;
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_TRI_BYTES) {
			for (int k = 0; k < 9; k++) {
				v[k] = s.get(JAVA_FLOAT, e + k * 4L);
				hash = hash * 31 + Float.floatToRawIntBits(v[k]);
			}
			int flags = s.get(JAVA_INT, e + 36);
			hash = hash * 31 + flags;
			SkyTri t = new SkyTri(v, 0, flags);
			if (t.degenerate()) {
				continue;
			}
			if ((flags & TRI_GHOST) != 0) {
				ghosts.add(t);
			} else {
				tris[kept++] = t;
				if (!t.dynamic) {
					for (int k = 0; k < 9; k++) {
						staticHash = staticHash * 31 + Float.floatToRawIntBits(v[k]);
					}
					staticHash = staticHash * 31 + 1;
				}
			}
		}
		long region = regionKey(Math.floorDiv(minX, REGION_SIZE), Math.floorDiv(minY, REGION_SIZE), Math.floorDiv(minZ, REGION_SIZE));
		if (ghosts.isEmpty()) {
			GHOSTS.remove(region);
		} else {
			GHOSTS.put(region, ghosts.toArray(new SkyTri[0]));
		}
		boolean dynamic = false;
		for (int i = 0; i < kept && !dynamic; i++) {
			dynamic = tris[i].dynamic;
		}
		TRIS.put(region, java.util.Arrays.copyOf(tris, kept));
		if (dynamic) {
			DYNAMIC_REGIONS.add(region);
		} else {
			DYNAMIC_REGIONS.remove(region);
		}
		if (this.link == ClientLink.INSTANCE) {
			Long staticBefore = staticHash == 0 ? STATIC_HASH.remove(region) : STATIC_HASH.put(region, staticHash);
			if (staticBefore == null ? staticHash != 0 : staticBefore != staticHash) {
				STATIC_CHANGED.add(BlockPos.asLong(minX, minY, minZ));
			}
		}
		Long before = TRI_HASH.put(region, hash);
		if (before == null || before != hash) {
			CHANGED.add(BlockPos.asLong(minX, minY, minZ));
			this.ground.regionChanged(minX, minY, minZ);
			SkyDig.wallsChanged(this, minX, minY, minZ, minX + REGION_SIZE - 1, minY + REGION_SIZE - 1, minZ + REGION_SIZE - 1);
		}
	}

	public int triangleCount() {
		int n = 0;
		for (SkyTri[] t : TRIS.values()) {
			n += t.length;
		}
		return n;
	}

	private static int fillInfo(MemorySegment s, long bitsOff) {
		int count = 0;
		int info = 0;
		int top = 0;
		for (int y = 0; y < 8; y++) {
			long layer = s.get(JAVA_LONG, bitsOff + y * 8L);
			count += Long.bitCount(layer);
			if (layer != 0) {
				info |= y < 4 ? FILL_LOWER : FILL_UPPER;
				top = y;
			}
		}
		return info | count | top << FILL_TOP_SHIFT;
	}

	private static @Nullable VoxelShape buildShape(MemorySegment s, long bitsOff) {
		boolean any = false;
		boolean full = true;
		long[] layers = new long[8];
		for (int y = 0; y < 8; y++) {
			layers[y] = s.get(JAVA_LONG, bitsOff + y * 8L);
			any |= layers[y] != 0;
			full &= layers[y] == -1L;
		}
		if (!any) {
			return null;
		}
		if (full) {
			return Shapes.block();
		}
		BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(8, 8, 8);
		for (int y = 0; y < 8; y++) {
			long layer = layers[y];
			while (layer != 0) {
				int bit = Long.numberOfTrailingZeros(layer);
				layer &= layer - 1;
				discrete.fill(bit & 7, y, bit >>> 3);
			}
		}
		return new CubeVoxelShape(discrete);
	}
}
