package dev.gmodcraft.slot;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.world.SkyDig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pure parts of a slot re-anchor (P8 WP2): no Minecraft world access, so they are unit-tested on
 * their own. A re-anchor changes a slot's vertical offset by dyUnits (Source units, exact) and moves
 * the slot's Minecraft content by {@link #blocksFor} whole blocks; everything moves straight up or
 * down, so every chunk column stays where it is.
 */
public final class SlotShift {
	private SlotShift() {
	}

	/** Whole blocks the MC content moves for a change of dyUnits Source units: round(dy / 40), half away from zero. */
	public static int blocksFor(int dyUnits) {
		int u = (int) Proto.UNITS_PER_BLOCK;
		int q = Math.abs(dyUnits) / u, r = Math.abs(dyUnits) % u;
		int b = q + (2 * r >= u ? 1 : 0);
		return dyUnits < 0 ? -b : b;
	}

	/**
	 * Review finding 2: the blocks a new re-anchor of dyUnits moves, rounded cumulatively: the content
	 * follows the total offset change since the slot's first re-anchor (round((oy + dy - oyBefore0) / 40))
	 * minus what it has moved so far, so small steps don't drift (two +20 steps move one block, not two).
	 * Undos use the stored block counts instead.
	 */
	public static int blocksForStep(int oyBaseline, int oyNow, int movedSoFar, int dyUnits) {
		long total = (long) oyNow + dyUnits - oyBaseline;
		return blocksFor((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, total))) - movedSoFar;
	}

	/** blocksForStep for a slot (baseline: its first re-anchor's offset before, else its offset now). */
	public static int blocksForStep(dev.gmodcraft.world.MapSlots.Slot s, int dyUnits) {
		List<dev.gmodcraft.world.MapSlots.Reanchor> h = s.reanchors();
		int baseline = h.isEmpty() ? s.oyUnits() : h.get(0).oyBefore();
		return blocksForStep(baseline, s.oyUnits(), s.blocksMoved(h.size()), dyUnits);
	}

	/**
	 * The column's dug cells of {@code world} moved by {@code blocks} (they cross section boundaries);
	 * other worlds' sections are kept as they are. Cells that would leave [minY, maxY] are dropped (the
	 * dry run refuses such a move first).
	 */
	public static SkyDig.DugColumn shiftDug(SkyDig.DugColumn column, int world, int blocks, int minY, int maxY) {
		if (blocks == 0 || column.sections().isEmpty()) {
			return column;
		}
		List<SkyDig.DugSection> keep = new ArrayList<>();
		Map<Integer, long[]> moved = new TreeMap<>();
		for (SkyDig.DugSection s : column.sections()) {
			if (s.world() != world || s.bits().length != 64) {
				keep.add(s);
				continue;
			}
			long[] bits = s.bits();
			for (int w = 0; w < 64; w++) {
				long word = bits[w];
				while (word != 0) {
					int bit = w * 64 + Long.numberOfTrailingZeros(word);
					word &= word - 1;
					int y = s.sectionY() * 16 + (bit >> 8) + blocks;
					if (y < minY || y > maxY) {
						continue;
					}
					int nb = (bit & 255) + 256 * (y & 15);
					moved.computeIfAbsent(y >> 4, k -> new long[64])[nb >> 6] |= 1L << (nb & 63);
				}
			}
		}
		for (Map.Entry<Integer, long[]> e : moved.entrySet()) {
			keep.add(new SkyDig.DugSection(world, e.getKey(), e.getValue()));
		}
		return new SkyDig.DugColumn(List.copyOf(keep));
	}

	/** Region file coordinates (r.X.Z) of a slot: its square [o - 1024, o + 1024) is exactly 4 x 4 region files. */
	public static List<int[]> regionFiles(int ox, int oz) {
		int half = Proto.SLOT_BLOCKS / 2;
		List<int[]> out = new ArrayList<>();
		for (int rx = Math.floorDiv(ox - half, 512); rx <= Math.floorDiv(ox + half - 1, 512); rx++) {
			for (int rz = Math.floorDiv(oz - half, 512); rz <= Math.floorDiv(oz + half - 1, 512); rz++) {
				out.add(new int[] { rx, rz });
			}
		}
		return out;
	}

	/** The chunks a region file holds (its location table: a non-zero entry is a stored chunk), as {cx, cz}. */
	public static List<int[]> chunksIn(Path regionFile, int rx, int rz) throws IOException {
		List<int[]> out = new ArrayList<>();
		if (!Files.isRegularFile(regionFile) || Files.size(regionFile) < 4096) {
			return out;
		}
		ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
		try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
			while (header.hasRemaining() && ch.read(header) > 0) {
				// read the whole location table
			}
		}
		header.flip();
		for (int i = 0; i < 1024 && header.remaining() >= 4; i++) {
			if (header.getInt() != 0) {
				out.add(new int[] { rx * 32 + (i & 31), rz * 32 + (i >> 5) });
			}
		}
		return out;
	}

	/** A demo's box moved up by {@code blocks}. */
	public static dev.gmodcraft.demo.DemoShape.Box shiftBox(dev.gmodcraft.demo.DemoShape.Box b, int blocks) {
		return new dev.gmodcraft.demo.DemoShape.Box(b.minX(), b.minY() + blocks, b.minZ(), b.maxX(), b.maxY() + blocks, b.maxZ());
	}

	/** Blocks a player who has seen {@code seen} of the slot's re-anchors still has to move (the rest of them). */
	public static int missedBlocks(dev.gmodcraft.world.MapSlots.Slot slot, int seen) {
		int n = slot.reanchors().size();
		return seen >= n ? 0 : slot.blocksMoved(n) - slot.blocksMoved(Math.max(0, seen));
	}

	/** The y range [lo, hi] of occupied blocks moved by {@code blocks} stays inside [minY, maxY]? (lo > hi: nothing there.) */
	public static boolean fits(int lo, int hi, int blocks, int minY, int maxY) {
		return lo > hi || (lo + (long) blocks >= minY && hi + (long) blocks <= maxY);
	}
}
