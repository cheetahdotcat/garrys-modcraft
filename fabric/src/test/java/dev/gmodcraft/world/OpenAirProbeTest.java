package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D3: open sky over a brush floor (gm_bigcity's plaza: a 0.4-block grass slab, no displacement) is
 * air for SkyDig.Probe#test, at any height (it was rock from 2.5 blocks up, so blasts and reveals
 * made floating stone there). With no geometry in the column at all it's still deep rock.
 */
class OpenAirProbeTest {
	private static final int NOT_DIGGABLE = 0;
	private static final int GRASS = Proto.TRI_DIGGABLE | (Proto.DIG_GRASS << Proto.TRI_MATERIAL_SHIFT);
	private static final int GRAVEL = Proto.TRI_DIGGABLE | (Proto.DIG_GRAVEL << Proto.TRI_MATERIAL_SHIFT);

	private static SkyDig.Probe probe(List<SkyTri> tris) {
		return new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		});
	}

	@Test
	void openSkyOverBrushPlazaIsAir() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 63.6, -40, 40, 64, 40, GRASS); // the plaza slab
		box(tris, 30, 64, -40, 40, 120, 40, NOT_DIGGABLE); // a building off to the side
		SkyDig.Probe probe = probe(tris);
		for (int y = 64; y <= 140; y++) {
			probe.around(0, y, 0, 1, y + 1, 1);
			for (double s : new double[] { 1.0 / 6.0, 0.5, 5.0 / 6.0 }) {
				assertEquals(SkyDig.AIR, probe.test(0.5, y + s, 0.5), "open air at y " + (y + s));
			}
		}
		probe.around(0, 63, 0, 1, 64, 1);
		assertTrue(probe.test(0.5, 63.8, 0.5) > SkyDig.AIR, "inside the slab");
	}

	@Test
	void underACeilingOverAFloorIsAir() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 60, -40, 40, 64, 40, GRAVEL);
		box(tris, -40, 80, -40, 40, 81, 40, NOT_DIGGABLE); // a roof far above
		SkyDig.Probe probe = probe(tris);
		probe.around(0, 70, 0, 1, 71, 1);
		assertEquals(SkyDig.AIR, probe.test(0.5, 70.5, 0.5));
	}

	@Test
	void deepInsideABigBrushIsStillRock() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 0, -40, 40, 100, 40, GRAVEL); // a thick rock: its top is above the point
		SkyDig.Probe probe = probe(tris);
		probe.around(0, 50, 0, 1, 51, 1);
		assertEquals(Proto.DIG_GRAVEL, probe.test(0.5, 50.5, 0.5));
	}

	@Test
	void cleanupTakesOnlyBlocksInOpenAir() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 63.6, -40, 40, 64, 20, GRASS); // the plaza slab, up to the rock
		box(tris, -40, 0, 20, 40, 100, 40, GRAVEL); // a thick rock wall behind it
		SkyDig.Surfaces s = (b, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= b.minX && t.minX <= b.maxX && t.maxY >= b.minY && t.minY <= b.maxY && t.maxZ >= b.minZ && t.minZ <= b.maxZ) {
					out.add(t);
				}
			}
		};
		assertTrue(SkyDig.Probe.floating(s, 0, 68, 0), "stone a blast left over the plaza");
		assertTrue(SkyDig.Probe.floating(s, 0, 80, 0));
		assertFalse(SkyDig.Probe.floating(s, 0, 64, 0), "a cell on the plaza (a hole filled back in)");
		assertFalse(SkyDig.Probe.floating(s, 0, 65, 0), "next to the plaza's surface");
		assertFalse(SkyDig.Probe.floating(s, 0, 63, 0), "in the slab");
		assertFalse(SkyDig.Probe.floating(s, 0, 50, 30), "revealed deep inside the rock");
		assertFalse(SkyDig.Probe.floating(s, 0, 70, 19), "against the rock's face");
	}

	@Test
	void noGeometryInTheColumnIsRock() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, 100, 0, 100, 110, 10, 110, GRAVEL); // nowhere near
		SkyDig.Probe probe = probe(tris);
		probe.around(0, 50, 0, 1, 51, 1);
		assertEquals(Proto.DIG_STONE, probe.test(0.5, 50.5, 0.5), "interiors: no ground anywhere is deep rock");
	}

	/** A box brush's 12 triangles, wound so their normals face out. */
	private static void box(List<SkyTri> out, double x0, double y0, double z0, double x1, double y1, double z1, int flags) {
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = new double[] { (i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1 };
		}
		int[][] quads = { { 0, 4, 6, 2 }, { 1, 3, 7, 5 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 2, 3, 1 }, { 4, 5, 7, 6 } };
		double mx = (x0 + x1) / 2, my = (y0 + y1) / 2, mz = (z0 + z1) / 2;
		for (int[] q : quads) {
			for (int[] tri : new int[][] { { q[0], q[1], q[2] }, { q[0], q[2], q[3] } }) {
				float[] v = new float[9];
				for (int k = 0; k < 3; k++) {
					for (int a = 0; a < 3; a++) {
						v[k * 3 + a] = (float) c[tri[k]][a];
					}
				}
				SkyTri t = new SkyTri(v, 0, flags);
				double ox = t.ax - mx, oy = t.ay - my, oz = t.az - mz;
				if (t.nx * ox + t.ny * oy + t.nz * oz < 0) { // faces in: swap two corners
					float[] w = v.clone();
					System.arraycopy(v, 3, w, 6, 3);
					System.arraycopy(v, 6, w, 3, 3);
					t = new SkyTri(w, 0, flags);
				}
				out.add(t);
			}
		}
	}
}

