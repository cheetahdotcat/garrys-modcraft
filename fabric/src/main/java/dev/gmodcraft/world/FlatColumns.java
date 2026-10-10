package dev.gmodcraft.world;

import dev.gmodcraft.link.Proto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where the flat world types put their layers (P8 WP3; pure, unit-tested). gmodcraft:slot_flat with
 * void_maps leaves a column void inside an allocated slot's map footprint (+ kMargin blocks) and,
 * around every slot origin without a known footprint (unallocated, or a slot from before v21), within
 * kUnknownHalf blocks of it: a map spans at most about +-410 blocks, so a slot allocated later never
 * finds terrain under its map. Everything else gets the deep flat column (DeepColumns), top block at floorY - 1.
 *
 * <p>The generator runs on worker threads: it reads {@link #current()}, an immutable snapshot that
 * MapSlots publishes whenever its table is loaded or a slot is allocated.
 */
public final class FlatColumns {
	public static final int SLOT = Proto.SLOT_BLOCKS;
	public static final int MARGIN = 16;
	public static final int UNKNOWN_HALF = 416;

	/** A block box (inclusive) in MC x/z. */
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		boolean contains(int x, int z) {
			return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
		}
	}

	/** The allocated slots' void boxes, by slot key. */
	public record Snapshot(Map<Long, Box> boxes) {
		public Snapshot {
			boxes = Map.copyOf(boxes);
		}
	}

	public static final Snapshot EMPTY = new Snapshot(Map.of());
	private static volatile Snapshot current = EMPTY;

	private FlatColumns() {
	}

	public static Snapshot current() {
		return current;
	}

	public static void publish(Snapshot s) {
		current = s;
	}

	static long key(int slotX, int slotZ) {
		return ((long) slotX << 32) ^ (slotZ & 0xFFFFFFFFL);
	}

	/** The slot index a block coordinate belongs to ([o - 1024, o + 1024) around o = index * 2048). */
	public static int slotOf(int block) {
		return Math.floorDiv(block + SLOT / 2, SLOT);
	}

	/**
	 * A slot's void box: its map's footprint {minX, minY, maxX, maxY} (Source units, around the
	 * slot origin) in blocks + MARGIN; mc.x = x / 40 + ox, mc.z = -y / 40 + oz (y flips). Null or
	 * malformed footprint: the +-UNKNOWN_HALF box.
	 */
	public static Box boxFor(int slotX, int slotZ, @Nullable List<Float> footprint) {
		int ox = slotX * SLOT, oz = slotZ * SLOT;
		if (footprint == null || footprint.size() != 4 || footprint.stream().anyMatch(f -> f == null || !Float.isFinite(f))
			|| footprint.get(2) < footprint.get(0) || footprint.get(3) < footprint.get(1)) {
			return new Box(ox - UNKNOWN_HALF, oz - UNKNOWN_HALF, ox + UNKNOWN_HALF, oz + UNKNOWN_HALF);
		}
		double u = Proto.UNITS_PER_BLOCK;
		int minX = ox + (int) Math.floor(footprint.get(0) / u) - MARGIN;
		int maxX = ox + (int) Math.ceil(footprint.get(2) / u) + MARGIN;
		int minZ = oz - (int) Math.ceil(footprint.get(3) / u) - MARGIN;
		int maxZ = oz - (int) Math.floor(footprint.get(1) / u) + MARGIN;
		return new Box(minX, minZ, maxX, maxZ);
	}

	/** A snapshot of these slots: {slotX, slotZ, footprint}. */
	public static Snapshot snapshot(List<MapSlots.Slot> slots) {
		Map<Long, Box> m = new HashMap<>();
		for (MapSlots.Slot s : slots) {
			m.put(key(s.slotX(), s.slotZ()), boxFor(s.slotX(), s.slotZ(), s.footprint()));
		}
		return new Snapshot(m);
	}

	/** Is the column at block x/z void (void_maps)? */
	public static boolean isVoid(Snapshot s, int x, int z) {
		int sx = slotOf(x), sz = slotOf(z);
		Box b = s.boxes().get(key(sx, sz));
		if (b != null) {
			return b.contains(x, z);
		}
		int dx = x - sx * SLOT, dz = z - sz * SLOT;
		return Math.abs(dx) <= UNKNOWN_HALF && Math.abs(dz) <= UNKNOWN_HALF;
	}
}
