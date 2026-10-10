package dev.gmodcraft.world;

import java.util.Collections;
import java.util.List;
import net.minecraft.core.Direction;

/**
 * The face geometry of a dug cell's walls (client DigWalls), engine-free so it is unit-tested.
 *
 * <p>A wall face is decided piece by piece: whatever the host has just behind the face at a point
 * (SkyDig.Probe.wallMaterial). The face starts as STEPS x STEPS pieces, each decided by its corners and
 * its middle; a piece whose points don't all agree (a brush's edge runs through it: the roof's top at
 * 0.6 of the cell, a floor slab's top 2 units over a piece's edge) is split in four, down to 1/32 block,
 * and the smallest pieces are drawn when any of their corners is solid. Deciding by the middle alone
 * left strips up to a quarter block wide where the map's solid has no wall in front of it, and the hole
 * showed what lies behind the hidden map face there: the sky.
 *
 * <p>Every point is a node of one 33 x 33 lattice per face (corners of neighbouring pieces and the
 * middles of split pieces are the same nodes), probed at most once: a face with no edge in it costs
 * 41 probes, a straight edge across it 176, a diagonal 291, a circle 477 (first version: 80 / 640 / 1140 / 1760;
 * before that, middles only: 16).
 *
 * <p>A smallest piece with both solid and open corners is cut back to the edge: along each of its sides
 * that runs from a solid corner to an open one, the crossing is found by bisection ({@link #BISECT}
 * probes per lattice edge, shared by the two pieces on it), and the piece reaches up to the first open
 * point found, not past it. Drawing the whole smallest piece (and growing it by {@link #GROW}) put a
 * sliver up to 1.65 units out of the map's surface along every hole rim (live 2026-10-09, hull world:
 * lines round a roof hole). A cut side doesn't grow: past it is open air. A straight edge across a face now
 * costs 275 probes, a diagonal 477, a circle 777.
 */
public final class WallPieces {
	private WallPieces() {
	}

	/** Pieces per face edge before any split. */
	public static final int STEPS = 4;
	/** Lattice nodes per face edge minus one: the smallest piece is 1/N block (1.25 units). */
	static final int N = 32;
	private static final int TOP = N / STEPS; // a whole piece, in lattice steps
	/** The face's own edges are probed this far inside it (blocks): a brush face lying on the cell's edge decides cleanly. */
	static final double EDGE = 0.001;
	/**
	 * How far every drawn piece reaches past its own square (blocks; 0.4 units). Pieces don't share
	 * vertices with each other (a split piece next to a whole one) nor with the map face's cut edge at
	 * the hole's rim, and the rasterizer left one-pixel cracks along those edges where the sky showed
	 * (a white line round a hole in a dark room). The overlap covers them; it sticks out of the face
	 * by less than half a unit.
	 */
	public static final double GROW = 0.01;
	/** Bisection steps per lattice edge for a cut piece: the edge is found to 1/(N * 2^BISECT) block (0.16 units). */
	static final int BISECT = 3;

	/** What the wall shows at face point (u, v), both 0..1: a material (> 0), {@link SkyDig.Probe#MIRROR_FACE}, or AIR / KEEP for nothing. */
	public interface Decide {
		int at(double u, double v);

		/** How deep the last {@link #at} point lies behind its surface (SkyDig.underground), read right after it. */
		default double depth() {
			return 0.5;
		}
	}

	/** One piece to draw: [u0, u1] x [v0, v1] of the face, what it shows and how deep the point that decided it lies. */
	public interface Piece {
		void put(double u0, double v0, double u1, double v1, int material, double depth);
	}

	public static boolean drawn(int material) {
		return material > SkyDig.AIR || material == SkyDig.Probe.MIRROR_FACE;
	}

	/** The lattice of one face: which nodes were probed, and their answers (reused per thread). */
	private static final class Grid {
		final int[] material = new int[(N + 1) * (N + 1)];
		final double[] depth = new double[(N + 1) * (N + 1)];
		final int[] stamp = new int[(N + 1) * (N + 1)];
		/** The pieces found so far (i << 16 | j << 8 | size), drawn once every node is known. */
		final int[] found = new int[N * N];
		int foundCount;
		/** Per lattice edge (node index * 2 + axis, 0 = along u): where solid turns open, as a fraction from the lower node. */
		final double[] cross = new double[(N + 1) * (N + 1) * 2];
		final int[] crossStamp = new int[(N + 1) * (N + 1) * 2];
		int face;
		Decide decide;
		Piece out;
		int probes;

		int at(int i, int j) {
			int k = i * (N + 1) + j;
			if (this.stamp[k] != this.face) {
				this.stamp[k] = this.face;
				this.probes++;
				this.material[k] = this.decide.at(coord(i), coord(j));
				this.depth[k] = this.decide.depth();
			}
			return k;
		}

		/**
		 * How far a piece may grow past its solid corner node (i, j) toward (di, dj) (one of the four axis
		 * directions): {@link #GROW}, or less when the node one step that way was probed and is open (the
		 * edge lies between them). No node is probed for this: one next to an edge is a corner of the cut
		 * piece there, probed before any whole piece is drawn.
		 */
		double grow(int i, int j, int di, int dj) {
			int bi = i + di, bj = j + dj;
			if (bi < 0 || bj < 0 || bi > N || bj > N) {
				return GROW;
			}
			int kb = bi * (N + 1) + bj;
			if (this.stamp[kb] != this.face || drawn(this.material[kb])) {
				return GROW;
			}
			int axis = di != 0 ? 0 : 1;
			boolean up = di + dj > 0;
			double c = up ? this.crossing(i, j, axis) : this.crossing(bi, bj, axis);
			return Math.min(GROW, (up ? c : 1 - c) / N);
		}

		/**
		 * The lattice edge from node (i, j) one step along u (axis 0) or v (axis 1), one end solid and the
		 * other open: how far from (i, j), as a fraction of the step, the first open point lies (bisection).
		 */
		double crossing(int i, int j, int axis) {
			int key = (i * (N + 1) + j) * 2 + axis;
			if (this.crossStamp[key] == this.face) {
				return this.cross[key];
			}
			int i1 = axis == 0 ? i + 1 : i, j1 = axis == 0 ? j : j + 1;
			boolean lowSolid = drawn(this.material[i * (N + 1) + j]);
			double u0 = coord(i), v0 = coord(j), u1 = coord(i1), v1 = coord(j1);
			double solid = lowSolid ? 0.0 : 1.0, open = lowSolid ? 1.0 : 0.0;
			for (int b = 0; b < BISECT; b++) {
				double t = (solid + open) / 2;
				this.probes++;
				if (drawn(this.decide.at(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t))) {
					solid = t;
				} else {
					open = t;
				}
			}
			this.crossStamp[key] = this.face;
			this.cross[key] = open;
			return open;
		}
	}

	private static final ThreadLocal<Grid> GRID = ThreadLocal.withInitial(Grid::new);

	static double coord(int k) {
		return k == 0 ? EDGE : k == N ? 1 - EDGE : (double) k / N;
	}

	/** The face's pieces. Returns how many points were probed. */
	public static int sample(Decide decide, Piece out) {
		Grid g = GRID.get();
		if (++g.face == 0) { // the stamp wrapped: forget every node
			java.util.Arrays.fill(g.stamp, 0);
			java.util.Arrays.fill(g.crossStamp, 0);
			g.face = 1;
		}
		g.decide = decide;
		g.out = out;
		g.probes = 0;
		try {
			g.foundCount = 0;
			for (int i = 0; i < N; i += TOP) {
				for (int j = 0; j < N; j += TOP) {
					piece(g, i, j, TOP);
				}
			}
			for (int w = 0; w < g.foundCount; w++) {
				emit(g, g.found[w] >> 16, (g.found[w] >> 8) & 0xFF, g.found[w] & 0xFF);
			}
			return g.probes;
		} finally {
			g.decide = null;
			g.out = null;
		}
	}

	private static void piece(Grid g, int i, int j, int s) {
		int k0 = g.at(i, j), k1 = g.at(i + s, j), k2 = g.at(i + s, j + s), k3 = g.at(i, j + s);
		int kc = s > 1 ? g.at(i + s / 2, j + s / 2) : -1;
		int[] m = g.material;
		boolean d0 = drawn(m[k0]), d1 = drawn(m[k1]), d2 = drawn(m[k2]), d3 = drawn(m[k3]), dc = kc >= 0 && drawn(m[kc]);
		boolean any = d0 || d1 || d2 || d3 || dc;
		if (!any) {
			return;
		}
		boolean all = d0 && d1 && d2 && d3 && (kc < 0 || dc);
		if (!all && s > 1) {
			int h = s / 2;
			piece(g, i, j, h);
			piece(g, i + h, j, h);
			piece(g, i, j + h, h);
			piece(g, i + h, j + h, h);
			return;
		}
		g.found[g.foundCount++] = (i << 16) | (j << 8) | s; // drawn at the end, once every node near it is known
	}

	/**
	 * Draws a piece found by {@link #piece} (every lattice node is known by now). A whole piece's sides grow
	 * by {@link #GROW}, but stop short of an edge found just past them (GROW must not reach into open
	 * air). A smallest piece across an edge (corners d0 (u0, v0), d1 (u1, v0), d2 (u1, v1), d3 (u0, v1)) is
	 * cut back toward its solid corners where a whole side is open; that side doesn't grow, the others grow
	 * as a whole piece's do (they meet the neighbouring pieces along the edge).
	 */
	private static void emit(Grid g, int i, int j, int s) {
		int[] m = g.material;
		int k0 = g.at(i, j), k1 = g.at(i + s, j), k2 = g.at(i + s, j + s), k3 = g.at(i, j + s);
		int kc = s > 1 ? g.at(i + s / 2, j + s / 2) : -1;
		boolean d0 = drawn(m[k0]), d1 = drawn(m[k1]), d2 = drawn(m[k2]), d3 = drawn(m[k3]), dc = kc >= 0 && drawn(m[kc]);
		int k = dc ? kc : d0 ? k0 : d1 ? k1 : d2 ? k2 : k3;
		double step = 1.0 / N;
		double u0 = (double) i / N, v0 = (double) j / N, u1 = (double) (i + s) / N, v1 = (double) (j + s) / N;
		double lo = u0, hi = u1, bo = v0, to = v1;
		// crossing(): the open point nearest the solid along a lattice edge, as a fraction from its lower node
		if (!d0 && !d3) { // the u0 side is open
			lo = u0 + step * Math.min(d1 ? g.crossing(i, j, 0) : 1, d2 ? g.crossing(i, j + 1, 0) : 1);
		}
		if (!d1 && !d2) { // the u1 side is open
			hi = u0 + step * Math.max(d0 ? g.crossing(i, j, 0) : 0, d3 ? g.crossing(i, j + 1, 0) : 0);
		}
		if (!d0 && !d1) { // the v0 side is open
			bo = v0 + step * Math.min(d3 ? g.crossing(i, j, 1) : 1, d2 ? g.crossing(i + 1, j, 1) : 1);
		}
		if (!d3 && !d2) { // the v1 side is open
			to = v0 + step * Math.max(d0 ? g.crossing(i, j, 1) : 0, d1 ? g.crossing(i + 1, j, 1) : 0);
		}
		lo -= side(g, d0, i, j, d3, i, j + s, -1, 0);
		bo -= side(g, d0, i, j, d1, i + s, j, 0, -1);
		hi += side(g, d1, i + s, j, d2, i + s, j + s, 1, 0);
		to += side(g, d3, i, j + s, d2, i + s, j + s, 0, 1);
		g.out.put(lo, bo, hi, to, m[k], g.depth[k]);
	}

	/** How far a side with corners a (ai, aj) and b (bi, bj) grows toward (di, dj): none when both are open. */
	private static double side(Grid g, boolean a, int ai, int aj, boolean b, int bi, int bj, int di, int dj) {
		if (!a && !b) {
			return 0.0;
		}
		return Math.min(a ? g.grow(ai, aj, di, dj) : GROW, b ? g.grow(bi, bj, di, dj) : GROW);
	}

	/** {@link #onFace} into {@code out} (no allocation: the per-probe path). */
	public static void onFace(Direction dir, int x, int y, int z, double u, double v, double[] out) {
		switch (dir) {
			case NORTH -> { out[0] = x + u; out[1] = y + v; out[2] = z; }
			case SOUTH -> { out[0] = x + 1 - u; out[1] = y + v; out[2] = z + 1; }
			case WEST -> { out[0] = x; out[1] = y + v; out[2] = z + 1 - u; }
			case EAST -> { out[0] = x + 1; out[1] = y + v; out[2] = z + u; }
			case UP -> { out[0] = x + u; out[1] = y + 1; out[2] = z + v; }
			case DOWN -> { out[0] = x + u; out[1] = y; out[2] = z + v; }
		}
	}

	/** A point on the face between a dug cell (x, y, z) and its neighbour in dir: (u across, v up) for side faces; (u = x, v = z) for floors and ceilings. */
	public static double[] onFace(Direction dir, int x, int y, int z, double u, double v) {
		return switch (dir) {
			case NORTH -> new double[] { x + u, y + v, z };
			case SOUTH -> new double[] { x + 1 - u, y + v, z + 1 };
			case WEST -> new double[] { x, y + v, z + 1 - u };
			case EAST -> new double[] { x + 1, y + v, z + u };
			case UP -> new double[] { x + u, y + 1, z + v };
			case DOWN -> new double[] { x + u, y, z + v };
		};
	}

	/**
	 * Puts a planar convex polygon's corners (and their uvs, same order) in the order that faces
	 * {@code normal}: counter-clockwise seen from that side, like every other quad the exporter sends
	 * (GMod culls the other side). Returns whether they were reversed. The floor of a hole (dir DOWN)
	 * came out the other way round from {@link #onFace}'s order and was never drawn: the hole's
	 * bottom showed the sky.
	 */
	public static boolean orient(List<double[]> world, List<double[]> uv, Direction normal) {
		double nx = 0, ny = 0, nz = 0;
		int n = world.size();
		for (int i = 0; i < n; i++) { // Newell's normal
			double[] p = world.get(i), q = world.get((i + 1) % n);
			nx += (p[1] - q[1]) * (p[2] + q[2]);
			ny += (p[2] - q[2]) * (p[0] + q[0]);
			nz += (p[0] - q[0]) * (p[1] + q[1]);
		}
		if (nx * normal.getStepX() + ny * normal.getStepY() + nz * normal.getStepZ() >= 0) {
			return false;
		}
		Collections.reverse(world);
		Collections.reverse(uv);
		return true;
	}
}
