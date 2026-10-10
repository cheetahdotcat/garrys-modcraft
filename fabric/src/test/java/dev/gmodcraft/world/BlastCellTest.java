package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D4: a blast that breaks the Minecraft grass block under the map's floor (flat_everywhere: the floor
 * sits in the top block's cell) carves the map there in the same blast; rule digThinWalls off, a thin
 * wall isn't dug by a blast; a blast's reveal puts no block in a mostly empty cell of a wall.
 */
class BlastCellTest {
	private static final int GRASS = Proto.TRI_DIGGABLE | (Proto.DIG_GRASS << Proto.TRI_MATERIAL_SHIFT);
	private static final int COBBLE = Proto.TRI_DIGGABLE | (Proto.DIG_COBBLE << Proto.TRI_MATERIAL_SHIFT);

	private static SkyDig.Probe probe(List<SkyTri> tris, int x, int y, int z, int r) {
		return new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		}).around(x - r, y - r, z - r, x + r + 1, y + r + 1, z + r + 1);
	}

	private static SkyDigBlast.Host host(boolean thinRefused) {
		return new SkyDigBlast.Host() {
			@Override
			public boolean dug(int x, int y, int z) {
				return false;
			}

			@Override
			public boolean thinRefused(int x, int y, int z) {
				return thinRefused;
			}

			@Override
			public int underground(int surface, double depth) {
				return surface; // SkyDig.underground needs a running game; the material itself is enough here
			}
		};
	}

	@Test
	void realBlockUnderTheMapFloorIsCarvedInOneBlast() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 63.6, -40, 40, 64, 40, GRASS); // the map floor in the grass block's cell (y 63)
		int m = SkyDigBlast.material(probe(tris, 0, 63, 0, 4), host(false), 0, 63, 0, true);
		assertTrue(m > 0, "the floor's cell is diggable ground even with a Minecraft block in it");
		assertEquals(SkyDigBlast.CARVE, SkyDigBlast.carve(m, true), "the block is the blast's; the map is carved with it");
		assertEquals(SkyDigBlast.CARVE_AND_FILL, SkyDigBlast.carve(m, false), "no block: the geometry becomes one for the blast");
		assertEquals(SkyDigBlast.SKIP, SkyDigBlast.carve(0, true), "no map there: vanilla only");
		assertEquals(SkyDigBlast.SKIP, SkyDigBlast.carve(SkyDig.KEEP, true));
		assertEquals(0, SkyDigBlast.material(probe(tris, 0, 59, 0, 4), host(false), 0, 59, 0, true), "MC ground well under the floor: vanilla only");
	}

	@Test
	void landInTheTopBlockIsCarvedButNotTheGroundUnderIt() {
		int land = GRASS | Proto.TRI_TERRAIN;
		float h = 63.9f; // a displacement just under the top block's top (Z1)
		List<SkyTri> tris = new ArrayList<>();
		tris.add(new SkyTri(new float[] { -40, h, -40, -40, h, 40, 40, h, 40 }, 0, land));
		tris.add(new SkyTri(new float[] { -40, h, -40, 40, h, 40, 40, h, -40 }, 0, land));
		int top = SkyDigBlast.material(probe(tris, 0, 63, 0, 4), host(false), 0, 63, 0, true);
		assertTrue(top > 0, "the land passes through the grass block's cell: carved with it");
		assertEquals(SkyDigBlast.CARVE, SkyDigBlast.carve(top, true));
		assertEquals(0, SkyDigBlast.material(probe(tris, 0, 61, 0, 4), host(false), 0, 61, 0, true), "Minecraft dirt under the land stays Minecraft's");
	}

	@Test
	void handBreakingTheTopBlockDigsTheMapFloorInItsCell() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 63.6, -40, 40, 64, 40, GRASS); // the map floor in the grass block's cell (y 63)
		assertTrue(SkyDigBlast.carvesWithBlock(probe(tris, 0, 63, 0, 3), host(false), 0, 63, 0), "D5: one break digs block and floor");
		assertFalse(SkyDigBlast.carvesWithBlock(probe(tris, 0, 62, 0, 3), host(false), 0, 62, 0), "MC dirt under the floor stays Minecraft's");
		assertFalse(SkyDigBlast.carvesWithBlock(probe(tris, 0, 59, 0, 3), host(false), 0, 59, 0));
		assertFalse(SkyDigBlast.carvesWithBlock(probe(tris, 0, 63, 0, 3), host(true), 0, 63, 0), "rule digThinWalls off and a thin floor: kept");
		assertFalse(SkyDigBlast.carvesWithBlock(probe(tris, 0, 70, 0, 3), host(false), 0, 70, 0), "a block built in the open: no map there");
	}

	@Test
	void thinWallIsNotDugByABlastWhenTheRuleIsOff() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -40, 63.6, -40, 40, 64, 40, GRASS);
		box(tris, 0, 64, -10, 0.375, 70, 10, COBBLE); // a 6 u wall standing on the floor
		int on = SkyDigBlast.material(probe(tris, 0, 65, 0, 4), host(false), 0, 65, 0, false);
		assertTrue(on > 0, "rule on: the wall's cell is dug like before");
		int off = SkyDigBlast.material(probe(tris, 0, 65, 0, 4), host(true), 0, 65, 0, false);
		assertEquals(SkyDig.KEEP, off, "rule off: the thin wall stays and stops the blast");
		assertEquals(SkyDigBlast.SKIP, SkyDigBlast.carve(off, true));
		assertEquals(SkyDigBlast.SKIP, SkyDigBlast.carve(off, false));
		assertEquals(0, SkyDigBlast.material(probe(tris, 5, 75, 0, 4), host(true), 5, 75, 0, false), "open air stays nothing");
	}

	@Test
	void revealPutsNoBlockInAMostlyEmptyWallCell() {
		assertFalse(ThinWall.fillable(100, false), "a thin wall's cell: a block would poke out of it");
		assertFalse(ThinWall.fillable(ThinWall.THIN_MAX, false));
		assertTrue(ThinWall.fillable(ThinWall.THIN_MAX + 1, false), "mostly solid");
		assertTrue(ThinWall.fillable(100, true), "the land (a shell) fills as before");
		assertTrue(ThinWall.fillable(0, false), "nothing voxelized: deep inside");
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
