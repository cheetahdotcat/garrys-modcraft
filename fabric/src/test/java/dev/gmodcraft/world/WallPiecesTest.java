package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * DigWalls' face geometry. Live 2026-10-09 (hull world, gm_construct): holes in the garage floor, the
 * basement floor and a roof showed white (the sky, blown out in a dark room) where the hole's floor and
 * thin strips along brush edges should have been walls.
 */
class WallPiecesTest {
	private static final int STONE = 3;

	/** The face's quad from onFace's corners, as DigWalls emits it, after orient. */
	private static double[] newell(List<double[]> w) {
		double nx = 0, ny = 0, nz = 0;
		for (int i = 0; i < w.size(); i++) {
			double[] p = w.get(i), q = w.get((i + 1) % w.size());
			nx += (p[1] - q[1]) * (p[2] + q[2]);
			ny += (p[2] - q[2]) * (p[0] + q[0]);
			nz += (p[0] - q[0]) * (p[1] + q[1]);
		}
		return new double[] { nx, ny, nz };
	}

	@Test
	void everyWallFacesIntoTheHole() {
		for (Direction dir : Direction.values()) {
			List<double[]> world = new ArrayList<>();
			List<double[]> uv = new ArrayList<>();
			double[][] c = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
			for (double[] k : c) {
				world.add(WallPieces.onFace(dir, 5, 64, 7, k[0], k[1]));
				uv.add(k);
			}
			Direction normal = dir.getOpposite();
			boolean reversed = WallPieces.orient(world, uv, normal);
			// onFace's own order is right for the sides and the ceiling, backwards for the floor
			assertEquals(dir == Direction.DOWN, reversed, "reversed for " + dir);
			double[] n = newell(world);
			assertTrue(n[0] * normal.getStepX() + n[1] * normal.getStepY() + n[2] * normal.getStepZ() > 0, "faces " + normal + " for " + dir);
			assertEquals(world.size(), uv.size());
		}
	}

	/** Which of a grid of face points the drawn pieces cover. */
	private static boolean[][] coverage(WallPieces.Decide d, int n, List<double[]> pieces) {
		WallPieces.sample(d, (u0, v0, u1, v1, m, depth) -> pieces.add(new double[] { u0, v0, u1, v1, m }));
		boolean[][] cov = new boolean[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double u = (i + 0.5) / n, v = (j + 0.5) / n;
				for (double[] p : pieces) {
					if (u >= p[0] && u <= p[2] && v >= p[1] && v <= p[3]) {
						cov[i][j] = true;
					}
				}
			}
		}
		return cov;
	}

	private static void assertSolidCovered(double top, double maxOver) {
		List<double[]> pieces = new ArrayList<>();
		int n = 400;
		boolean[][] cov = coverage((u, v) -> v < top ? STONE : SkyDig.AIR, n, pieces);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double v = (j + 0.5) / n;
				if (v < top) {
					assertTrue(cov[i][j], "solid at v " + v + " (top " + top + ") has a wall");
				} else if (v > top + maxOver) {
					assertTrue(!cov[i][j], "open air at v " + v + " (top " + top + ") has no wall");
				}
			}
		}
		for (double[] p : pieces) {
			assertEquals(STONE, (int) p[4]);
		}
	}

	@Test
	void brushTopInsideAPieceLeavesNoGap() {
		// the roof's top at 0.6 of the cell (z 240 in cell 216..256): the old middle-of-piece rule left
		// 0.5 .. 0.6 open; the basement floor's top 2 units under the cell's top (0.95): 0.875 .. 0.95
		assertSolidCovered(0.6, 1.0 / 32 + WallPieces.GROW);
		assertSolidCovered(0.95, 1.0 / 32 + WallPieces.GROW);
		assertSolidCovered(0.4, 1.0 / 32 + WallPieces.GROW);
		assertSolidCovered(0.75, WallPieces.GROW); // on a piece edge: exact (but for the overlap)
	}

	/** How far past the edge a cut piece may reach (blocks): one bisection step. */
	private static final double CUT = 1.0 / (32 << WallPieces.BISECT) + 1e-9;

	@Test
	void smallestPiecesStopAtTheEdge() {
		// live 2026-10-09 (hull world, roof hole, top at 0.6): smallest pieces drawn whole when any corner was
		// solid, plus GROW, stood up to 1.65 units out of the roof along the rim. Now cut back to the edge.
		for (double top : new double[] { 0.6, 0.95, 0.4, 0.37, 0.013 }) {
			assertSolidCovered(top, CUT);
		}
	}

	@Test
	void cutPiecesFollowASlopeAndACorner() {
		// a sloped surface (the ramp at gm_construct's wall foot) and a solid corner: covered, nothing past
		// the edge by more than one cut step plus the slope across one smallest piece
		List<double[]> pieces = new ArrayList<>();
		int n = 300;
		WallPieces.Decide slope = (u, v) -> v < 0.3 + 0.4 * u ? STONE : SkyDig.AIR;
		boolean[][] cov = coverage(slope, n, pieces);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double u = (i + 0.5) / n, v = (j + 0.5) / n, edge = 0.3 + 0.4 * u;
				if (v < edge) {
					assertTrue(cov[i][j], "solid at " + u + ", " + v);
				} else if (v > edge + 0.4 / 32 + CUT + WallPieces.GROW * 0.4) {
					assertTrue(!cov[i][j], "open at " + u + ", " + v);
				}
			}
		}
		pieces.clear();
		WallPieces.Decide corner = (u, v) -> u < 0.41 && v < 0.63 ? STONE : SkyDig.AIR;
		cov = coverage(corner, n, pieces);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double u = (i + 0.5) / n, v = (j + 0.5) / n;
				if (u < 0.41 && v < 0.63) {
					assertTrue(cov[i][j], "solid at " + u + ", " + v);
				} else if (u > 0.41 + CUT || v > 0.63 + CUT) {
					assertTrue(!cov[i][j], "open at " + u + ", " + v);
				}
			}
		}
	}

	@Test
	void wholeAndEmptyFacesStayCheap() {
		List<double[]> pieces = new ArrayList<>();
		int[] calls = { 0 };
		int probes = WallPieces.sample((u, v) -> {
			calls[0]++;
			return STONE;
		}, (u0, v0, u1, v1, m, depth) -> pieces.add(new double[] { u0, v0, u1, v1 }));
		assertEquals(16, pieces.size(), "a solid face: the 16 quarter pieces");
		assertEquals(41, calls[0], "5 x 5 corners + 16 middles, each probed once");
		assertEquals(41, probes);
		pieces.clear();
		assertEquals(41, WallPieces.sample((u, v) -> SkyDig.AIR, (u0, v0, u1, v1, m, depth) -> pieces.add(new double[0])));
		assertEquals(0, pieces.size());
	}

	/** Probes per face for a few edge shapes (the review's model of the first version: 640 / 1140 / 1760). */
	@Test
	void edgesStayBounded() {
		WallPieces.Piece none = (u0, v0, u1, v1, m, depth) -> {
		};
		int straight = WallPieces.sample((u, v) -> v < 0.6 ? STONE : SkyDig.AIR, none);
		int diagonal = WallPieces.sample((u, v) -> u + v < 1.03 ? STONE : SkyDig.AIR, none);
		int circle = WallPieces.sample((u, v) -> (u - .5) * (u - .5) + (v - .5) * (v - .5) < 0.16 ? STONE : SkyDig.AIR, none);
		System.out.printf("WallPieces probes per face: straight %d, diagonal %d, circle %d%n", straight, diagonal, circle);
		// 2026-10-10: + BISECT probes per crossed lattice edge (cut pieces): 275 / 477 / 777
		assertTrue(straight <= 300, "straight " + straight);
		assertTrue(diagonal <= 550, "diagonal " + diagonal);
		assertTrue(circle <= 850, "circle " + circle);
	}

	@Test
	void depthComesFromThePointThatDecided() {
		// the face's texture depends on the depth (SkyDig.underground): it must be the deciding point's
		List<Double> depths = new ArrayList<>();
		double[] last = { 0 };
		WallPieces.sample(new WallPieces.Decide() {
			@Override
			public int at(double u, double v) {
				last[0] = v < 0.5 ? 2.0 : Double.POSITIVE_INFINITY;
				return v < 0.5 ? STONE : SkyDig.AIR;
			}

			@Override
			public double depth() {
				return last[0];
			}
		}, (u0, v0, u1, v1, m, depth) -> depths.add(depth));
		assertTrue(!depths.isEmpty());
		for (double d : depths) {
			assertEquals(2.0, d);
		}
	}

	@Test
	void mirrorFaceCountsAsDrawn() {
		List<double[]> pieces = new ArrayList<>();
		coverage((u, v) -> v < 0.3 ? SkyDig.Probe.MIRROR_FACE : v < 0.6 ? STONE : SkyDig.AIR, 40, pieces);
		double below = 0, mid = 0;
		for (double[] p : pieces) {
			if ((int) p[4] == SkyDig.Probe.MIRROR_FACE) {
				below += (p[2] - p[0]) * (p[3] - p[1]);
			} else {
				mid += (p[2] - p[0]) * (p[3] - p[1]);
			}
		}
		assertTrue(below + mid >= 0.6, "covered " + (below + mid));
		assertTrue(below >= 0.25 && mid >= 0.25, below + " / " + mid);
	}
}
