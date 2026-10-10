package dev.gmodcraft.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Terrain Repair (P7b-2), the pure core: dug cells become solid GMod geometry again (their dug bit
 * goes). Decision for Minecraft blocks inside dug cells: a block that digging itself revealed
 * (terrain: the material blocks and stone / ores) is removed with the cell, and comes back on undo;
 * any other block (someone built there) keeps its cell dug: that cell is skipped and counted. So a
 * repair never destroys a build and never leaves an MC block buried in restored geometry.
 * Generic over the block value (in Minecraft a BlockState), so it is unit-tested with strings.
 */
public final class TerrainRepair {
	private TerrainRepair() {
	}

	/** The world's dug bits and blocks. */
	public interface Access<S> {
		boolean isDug(int x, int y, int z);

		void setDug(int x, int y, int z, boolean dug);

		S block(int x, int y, int z);

		void setBlock(int x, int y, int z, S value);
	}

	public record Removed<S>(int x, int y, int z, S block) {
	}

	/** What a repair did: the cells no longer dug, the terrain blocks it removed, the cells it skipped. */
	public record Result<S>(List<int[]> restored, List<Removed<S>> removed, int skipped) {
		public Result {
			restored = Collections.unmodifiableList(new ArrayList<>(restored));
			removed = Collections.unmodifiableList(new ArrayList<>(removed));
		}
	}

	/** Cells (x, y, z) whose centres lie within {@code r} blocks of (cx, cy, cz). */
	public static List<int[]> sphere(double cx, double cy, double cz, double r) {
		List<int[]> out = new ArrayList<>();
		int x0 = (int) Math.floor(cx - r), x1 = (int) Math.floor(cx + r);
		int y0 = (int) Math.floor(cy - r), y1 = (int) Math.floor(cy + r);
		int z0 = (int) Math.floor(cz - r), z1 = (int) Math.floor(cz + r);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
					if (dx * dx + dy * dy + dz * dz <= r * r) {
						out.add(new int[] { x, y, z });
					}
				}
			}
		}
		return out;
	}

	/** Repairs the dug ones among {@code cells}. */
	public static <S> Result<S> repair(Access<S> w, List<int[]> cells, S air, Predicate<S> isAir, Predicate<S> isTerrain) {
		List<int[]> restored = new ArrayList<>();
		List<Removed<S>> removed = new ArrayList<>();
		int skipped = 0;
		for (int[] c : cells) {
			if (!w.isDug(c[0], c[1], c[2])) {
				continue;
			}
			S b = w.block(c[0], c[1], c[2]);
			if (!isAir.test(b)) {
				if (!isTerrain.test(b)) {
					skipped++; // someone built here: the cell stays dug
					continue;
				}
				removed.add(new Removed<>(c[0], c[1], c[2], b));
				w.setBlock(c[0], c[1], c[2], air);
			}
			w.setDug(c[0], c[1], c[2], false);
			restored.add(c);
		}
		return new Result<>(restored, removed, skipped);
	}

	/**
	 * Undoes a repair: its cells are dug again, the terrain blocks it removed come back where the
	 * cell is still air. Returns how many cells were dug again.
	 */
	public static <S> int undo(Access<S> w, Result<S> r, Predicate<S> isAir) {
		int n = 0;
		for (int[] c : r.restored()) {
			if (!w.isDug(c[0], c[1], c[2])) {
				w.setDug(c[0], c[1], c[2], true);
				n++;
			}
		}
		for (Removed<S> b : r.removed()) {
			if (isAir.test(w.block(b.x(), b.y(), b.z()))) {
				w.setBlock(b.x(), b.y(), b.z(), b.block());
			}
		}
		return n;
	}
}
