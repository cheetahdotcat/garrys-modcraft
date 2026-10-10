package dev.gmodcraft.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Z1: is a flat Minecraft quad hidden under Skyrim's triangles that lie in its plane and face the
 * same way? Coverage is by the exact union of those triangles (convex-polygon subtraction), so a
 * quad two coplanar triangles only cover together goes, and a partly covered quad stays (no holes).
 * Block coordinates, unshifted. One instance per meshing thread: {@link #begin} once per section,
 * then {@link #covered} per quad.
 */
public final class QuadCover {
	/** 0.5 Source units, in blocks: how far off the triangles' plane a quad corner may be. */
	public static final double PLANE_TOL = 0.5 / 40.0;
	/** cos of the largest angle between the quad's and a triangle's normals (~8 degrees). */
	public static final double MIN_FACING = 0.99;
	/** Triangle edges are pushed out this far (blocks): seams between triangles sharing an edge. */
	static final double EDGE_EPS = 1e-5;
	/** Uncovered pieces smaller than this (blocks squared, ~0.0016 units squared) are slivers. */
	static final double AREA_EPS = 1e-6;
	/** More uncovered pieces than this: give up and keep the quad (bounds the per-quad cost). */
	public static final int MAX_PIECES = 64;
	/** More candidate triangles in a section than this: no culling there (bounds the per-section cost). */
	public static final int MAX_CANDIDATES = 4096;

	private final ArrayList<SkyTri> candidates = new ArrayList<>();
	// The candidates binned into 4x4x4 cells of 4 blocks over the section (clamped at its edges),
	// so a quad only looks at the triangles around it.
	private final int[][] cells = new int[64][];
	private final int[] cellCount = new int[64];
	private int[] mark = new int[64];
	private int stamp;
	private int originX, originY, originZ;
	private final ArrayList<SkyTri> near = new ArrayList<>();
	private List<double[]> pieces = new ArrayList<>();
	private List<double[]> next = new ArrayList<>();
	private double[] flat = new double[96];

	/**
	 * Takes the triangles that may cover quads of one section (from {@link SkyCollision#trianglesNear}
	 * over the section's bounds). Dynamic triangles (props, doors) are left out: one that moves away
	 * would leave a hole where the quad was culled. False: nothing to test against here.
	 */
	public boolean begin(List<SkyTri> tris, int originX, int originY, int originZ) {
		this.candidates.clear();
		java.util.Arrays.fill(this.cellCount, 0);
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		for (SkyTri t : tris) {
			if (!t.dynamic) {
				this.candidates.add(t);
			}
		}
		if (this.candidates.size() > MAX_CANDIDATES) {
			this.candidates.clear();
		}
		if (this.mark.length < this.candidates.size()) {
			this.mark = new int[Math.max(this.candidates.size(), this.mark.length * 2)];
		}
		for (int i = 0; i < this.candidates.size(); i++) {
			SkyTri t = this.candidates.get(i);
			int x0 = cell(t.minX - PLANE_TOL, originX), x1 = cell(t.maxX + PLANE_TOL, originX);
			int y0 = cell(t.minY - PLANE_TOL, originY), y1 = cell(t.maxY + PLANE_TOL, originY);
			int z0 = cell(t.minZ - PLANE_TOL, originZ), z1 = cell(t.maxZ + PLANE_TOL, originZ);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						int c = x + 4 * y + 16 * z;
						if (this.cells[c] == null) {
							this.cells[c] = new int[16];
						} else if (this.cellCount[c] == this.cells[c].length) {
							this.cells[c] = java.util.Arrays.copyOf(this.cells[c], this.cellCount[c] * 2);
						}
						this.cells[c][this.cellCount[c]++] = i;
					}
				}
			}
		}
		return !this.candidates.isEmpty();
	}

	private static int cell(double v, int origin) {
		return Math.max(0, Math.min(3, (int) Math.floor((v - origin) / 4.0)));
	}

	public int candidateCount() {
		return this.candidates.size();
	}

	/**
	 * True if the quad (4 corners, x y z each, counter-clockwise around its outward normal as
	 * Minecraft winds them) is fully covered by the union of the candidate triangles in its plane
	 * that face the same way. Non-planar or degenerate quads are never covered.
	 */
	public boolean covered(double[] q) {
		if (this.candidates.isEmpty()) {
			return false;
		}
		// The quad's own normal from its winding, and its plane (relative to corner 0 for precision).
		double ox = q[0], oy = q[1], oz = q[2];
		double ux = q[3] - ox, uy = q[4] - oy, uz = q[5] - oz;
		double vx = q[6] - ox, vy = q[7] - oy, vz = q[8] - oz;
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-9) {
			return false;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		if (Math.abs((q[9] - ox) * nx + (q[10] - oy) * ny + (q[11] - oz) * nz) > PLANE_TOL) {
			return false; // not flat
		}
		double minX = Math.min(Math.min(q[0], q[3]), Math.min(q[6], q[9])), maxX = Math.max(Math.max(q[0], q[3]), Math.max(q[6], q[9]));
		double minY = Math.min(Math.min(q[1], q[4]), Math.min(q[7], q[10])), maxY = Math.max(Math.max(q[1], q[4]), Math.max(q[7], q[10]));
		double minZ = Math.min(Math.min(q[2], q[5]), Math.min(q[8], q[11])), maxZ = Math.max(Math.max(q[2], q[5]), Math.max(q[8], q[11]));
		this.near.clear();
		if (++this.stamp == 0) {
			java.util.Arrays.fill(this.mark, 0);
			this.stamp = 1;
		}
		int cx0 = cell(minX - PLANE_TOL, this.originX), cx1 = cell(maxX + PLANE_TOL, this.originX);
		int cy0 = cell(minY - PLANE_TOL, this.originY), cy1 = cell(maxY + PLANE_TOL, this.originY);
		int cz0 = cell(minZ - PLANE_TOL, this.originZ), cz1 = cell(maxZ + PLANE_TOL, this.originZ);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cy = cy0; cy <= cy1; cy++) {
				for (int cz = cz0; cz <= cz1; cz++) {
					int c = cx + 4 * cy + 16 * cz;
					for (int j = 0; j < this.cellCount[c]; j++) {
						int i = this.cells[c][j];
						if (this.mark[i] != this.stamp) {
							this.mark[i] = this.stamp;
							SkyTri t = this.candidates.get(i);
							if (t.maxX >= minX - PLANE_TOL && t.minX <= maxX + PLANE_TOL && t.maxY >= minY - PLANE_TOL && t.minY <= maxY + PLANE_TOL
								&& t.maxZ >= minZ - PLANE_TOL && t.minZ <= maxZ + PLANE_TOL && inPlaneFacing(t, q, nx, ny, nz)) {
								this.near.add(t);
							}
						}
					}
				}
			}
		}
		if (this.near.isEmpty()) {
			return false;
		}
		// 2D: drop the normal's largest axis (an affine map of the plane; coverage is unchanged).
		int drop = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0 : Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
		int a0 = drop == 0 ? 1 : 0, a1 = drop == 2 ? 1 : 2;
		double[] o3 = { ox, oy, oz };
		double[] quad = new double[8];
		for (int k = 0; k < 4; k++) {
			quad[k * 2] = q[k * 3 + a0] - o3[a0];
			quad[k * 2 + 1] = q[k * 3 + a1] - o3[a1];
		}
		ccw(quad, 4);
		this.pieces.clear();
		this.pieces.add(quad);
		// The triangles in 2D; the common case first: one of them covers the whole quad.
		if (this.flat.length < this.near.size() * 6) {
			this.flat = new double[this.near.size() * 12];
		}
		int n = 0;
		for (SkyTri t : this.near) {
			double[] f = this.flat;
			int o = n * 6;
			f[o] = (a0 == 0 ? t.ax : t.ay) - o3[a0];
			f[o + 1] = (a1 == 1 ? t.ay : t.az) - o3[a1];
			f[o + 2] = (a0 == 0 ? t.bx : t.by) - o3[a0];
			f[o + 3] = (a1 == 1 ? t.by : t.bz) - o3[a1];
			f[o + 4] = (a0 == 0 ? t.cx : t.cy) - o3[a0];
			f[o + 5] = (a1 == 1 ? t.cy : t.cz) - o3[a1];
			if (ccw(f, o, 3)) { // else degenerate in this plane
				if (containsAll(f, o, quad)) {
					return true;
				}
				n++;
			}
		}
		double[] tri = new double[6];
		for (int i = 0; i < n; i++) {
			System.arraycopy(this.flat, i * 6, tri, 0, 6);
			this.next.clear();
			for (double[] piece : this.pieces) {
				subtract(piece, tri, this.next);
			}
			List<double[]> swap = this.pieces;
			this.pieces = this.next;
			this.next = swap;
			if (this.pieces.isEmpty()) {
				return true;
			}
			if (this.pieces.size() > MAX_PIECES) {
				return false;
			}
		}
		return false;
	}

	/** Faces the quad's way (not the opposite or another way) and every quad corner is on its plane. */
	private static boolean inPlaneFacing(SkyTri t, double[] q, double nx, double ny, double nz) {
		if (t.nx * nx + t.ny * ny + t.nz * nz < MIN_FACING) {
			return false;
		}
		for (int k = 0; k < 4; k++) {
			double d = (q[k * 3] - t.ax) * t.nx + (q[k * 3 + 1] - t.ay) * t.ny + (q[k * 3 + 2] - t.az) * t.nz;
			if (Math.abs(d) > PLANE_TOL) {
				return false;
			}
		}
		return true;
	}

	/** Orders a convex polygon (n points, x y pairs) counter-clockwise. False if it has no area. */
	private static boolean ccw(double[] p, int n) {
		return ccw(p, 0, n);
	}

	private static boolean ccw(double[] p, int off, int n) {
		double a = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			a += p[off + i * 2] * p[off + j * 2 + 1] - p[off + j * 2] * p[off + i * 2 + 1];
		}
		if (Math.abs(a) < 1e-12) {
			return false;
		}
		if (a < 0) {
			for (int i = 0, j = n - 1; i < j; i++, j--) {
				double x = p[off + i * 2], y = p[off + i * 2 + 1];
				p[off + i * 2] = p[off + j * 2];
				p[off + i * 2 + 1] = p[off + j * 2 + 1];
				p[off + j * 2] = x;
				p[off + j * 2 + 1] = y;
			}
		}
		return true;
	}

	private static double area2(double[] p, int n) {
		double a = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1];
		}
		return a;
	}

	/** True if every point of {@code p} is inside the (CCW) triangle, edges pushed out by EDGE_EPS. */
	private static boolean containsAll(double[] tri, int off, double[] p) {
		for (int e = 0; e < 3; e++) {
			double ax = tri[off + e * 2], ay = tri[off + e * 2 + 1];
			double ex = tri[off + (e + 1) % 3 * 2] - ax, ey = tri[off + (e + 1) % 3 * 2 + 1] - ay;
			double el = Math.sqrt(ex * ex + ey * ey);
			for (int i = 0; i < p.length; i += 2) {
				if ((ex * (p[i + 1] - ay) - ey * (p[i] - ax)) / el < -EDGE_EPS) {
					return false;
				}
			}
		}
		return true;
	}

	/** Adds the parts of the convex polygon {@code piece} outside the (CCW) triangle to {@code out}. */
	private static void subtract(double[] piece, double[] tri, List<double[]> out) {
		double tx0 = Math.min(tri[0], Math.min(tri[2], tri[4])), tx1 = Math.max(tri[0], Math.max(tri[2], tri[4]));
		double ty0 = Math.min(tri[1], Math.min(tri[3], tri[5])), ty1 = Math.max(tri[1], Math.max(tri[3], tri[5]));
		double px0 = Double.MAX_VALUE, px1 = -Double.MAX_VALUE, py0 = Double.MAX_VALUE, py1 = -Double.MAX_VALUE;
		for (int i = 0; i < piece.length; i += 2) {
			px0 = Math.min(px0, piece[i]);
			px1 = Math.max(px1, piece[i]);
			py0 = Math.min(py0, piece[i + 1]);
			py1 = Math.max(py1, piece[i + 1]);
		}
		if (tx1 <= px0 || tx0 >= px1 || ty1 <= py0 || ty0 >= py1) {
			out.add(piece); // apart, or touching along an edge: nothing to take away
			return;
		}
		double[] rest = piece;
		for (int e = 0; e < 3; e++) {
			double ax = tri[e * 2], ay = tri[e * 2 + 1];
			double bx = tri[(e + 1) % 3 * 2], by = tri[(e + 1) % 3 * 2 + 1];
			double ex = bx - ax, ey = by - ay;
			double el = Math.sqrt(ex * ex + ey * ey);
			// signed distance to the edge's line, positive inside; the edge is pushed out by EDGE_EPS
			double cx = -ey / el, cy = ex / el, c0 = -(cx * ax + cy * ay) + EDGE_EPS;
			double[] outside = clip(rest, cx, cy, c0, false);
			if (outside != null && Math.abs(area2(outside, outside.length / 2)) * 0.5 > AREA_EPS) {
				out.add(outside);
			}
			rest = clip(rest, cx, cy, c0, true);
			if (rest == null) {
				return;
			}
		}
		// what's left of the piece is inside the triangle: covered
	}

	/** The part of a convex polygon where {@code cx*x + cy*y + c0 >= 0} (inside) or {@code < 0}. */
	private static double[] clip(double[] p, double cx, double cy, double c0, boolean inside) {
		int n = p.length / 2;
		double[] out = new double[(n + 1) * 2];
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double di = cx * p[i * 2] + cy * p[i * 2 + 1] + c0, dj = cx * p[j * 2] + cy * p[j * 2 + 1] + c0;
			if (!inside) {
				di = -di;
				dj = -dj;
			}
			if (di >= 0) {
				out[m * 2] = p[i * 2];
				out[m * 2 + 1] = p[i * 2 + 1];
				m++;
			}
			if ((di >= 0) != (dj >= 0)) {
				double t = di / (di - dj);
				out[m * 2] = p[i * 2] + t * (p[j * 2] - p[i * 2]);
				out[m * 2 + 1] = p[i * 2 + 1] + t * (p[j * 2 + 1] - p[i * 2 + 1]);
				m++;
			}
		}
		if (m < 3) {
			return null;
		}
		return m * 2 == out.length ? out : java.util.Arrays.copyOf(out, m * 2);
	}
}
