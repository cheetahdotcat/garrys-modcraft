package dev.gmodcraft.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting the local player out of a GMod prop or door that moved onto it (P6i).
 * <p>The player collides with the host's exact triangles ({@link TriCollider}), which pushes its
 * cylinder out of faces that cut into it: a thin door swinging through the player shoves it aside.
 * A thicker prop set down over the player is different: once the body is wholly inside, no face
 * touches the cylinder and nothing pushes; the player is shut in the prop. The host voxels do show
 * it (a convex prop is voxelized solid), so when a region update brings host-entity triangles
 * around the player and its body's core is inside solid voxels, the player is moved to the nearest
 * free spot within {@link #MAX_DIST} blocks (sideways or up: on top of the prop), never across a
 * static (map) triangle or through a Minecraft block / dug-hole wall on the way (the server replays
 * the move against those and would snap the player back). Spots with something to stand on come
 * first; one in mid-air only when there's no other.
 * <p>Pure: the caller hands in the triangles near the player and tests for solid voxels / blocks.
 */
public final class PushOut {
	/** Farthest a player is moved (blocks). */
	public static final double MAX_DIST = 2.0;
	private static final double GRID = 0.25;       // horizontal candidate spacing (blocks)
	private static final double LIFT_STEP = 0.5;   // vertical candidate spacing (blocks)
	// The core box tested against voxels: the cylinder shrunk by more than a voxel (1/8 block), from
	// the step height up (below it the feet may stand in the stair-steps of sloped ground).
	private static final double CORE_INSET = 0.15;
	private static final double HEAD_GAP = 0.1;
	// The swept box for the path test, shrunk a little (flush against a wall or floor isn't through it).
	private static final double SWEEP_INSET = 0.02;
	// How far below the feet support may be (a step down is fine).
	private static final double SUPPORT_DEPTH = 0.6;
	// Heights above the feet at which the way out must not cross a static triangle.
	private static final double[] PATH_HEIGHTS = { 0.65, 1.2 };

	/** Is any solid voxel or block in the box (x0..x1, y0..y1, z0..z1)? */
	@FunctionalInterface
	public interface BoxTest {
		boolean solid(double x0, double y0, double z0, double x1, double y1, double z1);
	}

	private PushOut() {
	}

	/** Is the body core at feet (x, y, z) in solid? */
	public static boolean coreSolid(BoxTest solid, double x, double y, double z, double radius, double height, double step) {
		double r = Math.max(radius - CORE_INSET, 0.05);
		return solid.solid(x - r, y + step, z - r, x + r, y + height - HEAD_GAP, z + r);
	}

	/** A host entity's triangle (prop, door) within the body's box, a little enlarged. */
	public static boolean dynamicNear(List<SkyTri> tris, double x, double y, double z, double radius, double height) {
		double m = 0.25;
		for (SkyTri t : tris) {
			if (t.dynamic && t.maxX >= x - radius - m && t.minX <= x + radius + m && t.maxZ >= z - radius - m && t.minZ <= z + radius + m
				&& t.maxY >= y - m && t.minY <= y + height + m) {
				return true;
			}
		}
		return false;
	}

	/** Shut in by a host entity: its triangles are around the body and the body core is in solid. */
	public static boolean trapped(List<SkyTri> tris, BoxTest solid, double x, double y, double z, double radius, double height, double step) {
		return coreSolid(solid, x, y, z, radius, height, step) && dynamicNear(tris, x, y, z, radius, height);
	}

	/**
	 * The nearest feet position within {@code maxDist} where the core is free ({@code solid}: host
	 * voxels, blocks, entities), no triangle cuts into the cylinder, and the way there crosses no
	 * static triangle and nothing {@code path} reports in the box swept from the body here to the
	 * body there (blocks and dug-hole walls: what the server replays the move against). Spots with
	 * support underfoot first; null if there is none.
	 */
	public static double[] find(List<SkyTri> tris, BoxTest solid, BoxTest path, double x, double y, double z, double radius, double height,
		double step, double maxDist) {
		List<double[]> cand = new ArrayList<>();
		int n = (int) Math.floor(maxDist / GRID);
		int up = (int) Math.floor(maxDist / LIFT_STEP);
		for (int iy = 0; iy <= up; iy++) {
			double dy = iy * LIFT_STEP;
			for (int ix = -n; ix <= n; ix++) {
				for (int iz = -n; iz <= n; iz++) {
					double dx = ix * GRID, dz = iz * GRID;
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > 0 && d2 <= maxDist * maxDist + 1e-9) {
						cand.add(new double[] { dx, dy, dz, d2 });
					}
				}
			}
		}
		cand.sort((a, b) -> Double.compare(a[3], b[3]));
		List<SkyTri> walls = new ArrayList<>();
		for (SkyTri t : tris) {
			if (!t.dynamic && !t.stairHelper) {
				walls.add(t);
			}
		}
		double[] unsupported = null;
		double r = radius - SWEEP_INSET;
		for (double[] c : cand) {
			double nx = x + c[0], ny = y + c[1], nz = z + c[2];
			if (coreSolid(solid, nx, ny, nz, radius, height, step) || !TriCollider.clearOfWalls(tris, nx, ny, nz, radius, height, step)) {
				continue;
			}
			if (crosses(walls, x, y, z, nx, ny, nz)) {
				continue;
			}
			if (path.solid(Math.min(x, nx) - r, Math.min(y, ny) + SWEEP_INSET, Math.min(z, nz) - r, Math.max(x, nx) + r,
				Math.max(y, ny) + height - SWEEP_INSET, Math.max(z, nz) + r)) {
				continue;
			}
			if (supported(tris, solid, nx, ny, nz, radius)) {
				return new double[] { nx, ny, nz };
			}
			if (unsupported == null) {
				unsupported = new double[] { nx, ny, nz };
			}
		}
		return unsupported;
	}

	/** Something to stand on within {@link #SUPPORT_DEPTH} below the feet (a triangle, a voxel, a block). */
	static boolean supported(List<SkyTri> tris, BoxTest solid, double x, double y, double z, double radius) {
		double g = TriCollider.groundAt(tris, x, y, z, 0.0);
		if (!Double.isNaN(g) && g >= y - SUPPORT_DEPTH) {
			return true;
		}
		double r = Math.max(radius - CORE_INSET, 0.05);
		return solid.solid(x - r, y - SUPPORT_DEPTH, z - r, x + r, y, z + r);
	}

	/** Does the way from feet a to feet b, at the body heights, cross any of these triangles? */
	private static boolean crosses(List<SkyTri> tris, double ax, double ay, double az, double bx, double by, double bz) {
		for (double h : PATH_HEIGHTS) {
			double sy = ay + h, ey = by + h;
			double lox = Math.min(ax, bx), hix = Math.max(ax, bx), loy = Math.min(sy, ey), hiy = Math.max(sy, ey), loz = Math.min(az, bz),
				hiz = Math.max(az, bz);
			for (SkyTri t : tris) {
				if (t.maxX < lox || t.minX > hix || t.maxY < loy || t.minY > hiy || t.maxZ < loz || t.minZ > hiz) {
					continue;
				}
				if (segmentHits(t, ax, sy, az, bx, ey, bz)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Moller-Trumbore, limited to the segment. */
	static boolean segmentHits(SkyTri t, double sx, double sy, double sz, double ex, double ey, double ez) {
		double dx = ex - sx, dy = ey - sy, dz = ez - sz;
		double e1x = t.bx - t.ax, e1y = t.by - t.ay, e1z = t.bz - t.az;
		double e2x = t.cx - t.ax, e2y = t.cy - t.ay, e2z = t.cz - t.az;
		double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
		double det = e1x * px + e1y * py + e1z * pz;
		if (Math.abs(det) < 1e-12) {
			return false;
		}
		double inv = 1.0 / det;
		double tx = sx - t.ax, ty = sy - t.ay, tz = sz - t.az;
		double u = (tx * px + ty * py + tz * pz) * inv;
		if (u < 0 || u > 1) {
			return false;
		}
		double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
		double v = (dx * qx + dy * qy + dz * qz) * inv;
		if (v < 0 || u + v > 1) {
			return false;
		}
		double s = (e2x * qx + e2y * qy + e2z * qz) * inv;
		return s >= 0 && s <= 1;
	}
}
