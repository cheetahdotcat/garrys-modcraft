package dev.gmodcraft.world;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Which of the host map's ground a cell stands for (roadmap 0.5, M1): Minecraft asks "what block is
 * below?" (snow layers, friction, spawn rules) of a cell that's AIR to it, because map geometry is
 * collision only. A cell answers with the map surface over its column: the highest up-facing,
 * non-moving surface whose height at the column's centre lies in [y, y + 2). That window covers both
 * ways a cell can sit under something resting on uneven ground (placementCell puts it in the cell
 * the surface is in, or the one above), and the friction cell (feet - 0.5).
 *
 * <p>Pure apart from the triangle source; answers are cached per cell, by host region, and dropped
 * when a region's triangles change (SkyCollision calls {@link #regionChanged} and {@link #clear}).
 */
public final class SkyGround {
	/** Triangles whose bounds overlap a box (SkyCollision#trianglesNear: current surfaces, no ghosts). */
	public interface Tris {
		void near(AABB box, List<SkyTri> out);
	}

	/** A cell's ground: the surface's material (Proto.DIG_*, never DIG_NONE) and the cell it's in. */
	public record Ground(int material, int surfaceY, boolean diggable) {
	}

	private static final Ground NONE = new Ground(0, 0, false);
	private static final int MAX_REGIONS = 4096;
	/** Cosine of the steepest slope still counted as ground (60 degrees). */
	static final double UP_NY = 0.5;

	private final Tris tris;
	private final int regionSize;
	private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Ground>> cache = new ConcurrentHashMap<>();

	public SkyGround(Tris tris, int regionSize) {
		this.tris = tris;
		this.regionSize = regionSize;
	}

	/** The map ground this cell stands for, or null (no map surface over it within reach). Any thread. */
	public @Nullable Ground at(int x, int y, int z) {
		long region = BlockPos.asLong(Math.floorDiv(x, this.regionSize), Math.floorDiv(y, this.regionSize), Math.floorDiv(z, this.regionSize));
		ConcurrentHashMap<Long, Ground> cells = this.cache.get(region);
		if (cells == null) {
			if (this.cache.size() >= MAX_REGIONS) {
				this.cache.clear();
			}
			cells = this.cache.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
		}
		long key = BlockPos.asLong(x, y, z);
		Ground g = cells.get(key);
		if (g == null) {
			g = compute(this.tris, x, y, z);
			if (this.cache.get(region) == cells) { // not dropped meanwhile (a region update while computing)
				cells.put(key, g);
			}
		}
		return g == NONE ? null : g;
	}

	/** Cached cells (for tests). */
	int cachedCells() {
		int n = 0;
		for (ConcurrentHashMap<Long, Ground> cells : this.cache.values()) {
			n += cells.size();
		}
		return n;
	}

	/**
	 * A host region's triangles changed (min corner in blocks). Its cells and the region below go:
	 * a cell's window reaches two blocks up, into the region above it.
	 */
	public void regionChanged(int minX, int minY, int minZ) {
		int rx = Math.floorDiv(minX, this.regionSize), ry = Math.floorDiv(minY, this.regionSize), rz = Math.floorDiv(minZ, this.regionSize);
		this.cache.remove(BlockPos.asLong(rx, ry, rz));
		this.cache.remove(BlockPos.asLong(rx, ry - 1, rz));
	}

	public void clear() {
		this.cache.clear();
	}

	/**
	 * The surface cell a crosshair hit on the map's ground means (SkyRay.placementCell is the air in
	 * front of it, the same cell or the one above): the cell the hit point lies in, a hair below it.
	 */
	public static BlockPos surfaceCell(double x, double y, double z) {
		return BlockPos.containing(x, y - 0.01, z);
	}

	static Ground compute(Tris source, int x, int y, int z) {
		double cx = x + 0.5, cz = z + 0.5;
		List<SkyTri> near = new ArrayList<>();
		source.near(new AABB(cx - 0.01, y, cz - 0.01, cx + 0.01, y + 2.0, cz + 0.01), near);
		SkyTri best = null;
		double bestH = Double.NEGATIVE_INFINITY;
		for (SkyTri t : near) {
			if (t.stairHelper || t.dynamic || Math.abs(t.ny) < UP_NY) {
				continue;
			}
			double h = t.heightAt(cx, cz);
			if (Double.isNaN(h) || h < y || h >= y + 2.0) {
				continue;
			}
			if (h > bestH) {
				bestH = h;
				best = t;
			}
		}
		if (best == null) {
			return NONE;
		}
		int material = best.material == Proto.DIG_NONE ? Proto.DIG_STONE : best.material;
		return new Ground(material, (int) Math.floor(bestH), best.diggable);
	}
}
