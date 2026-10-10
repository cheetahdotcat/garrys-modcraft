package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * D1: a cell dug into a building wall, its floor, back and sides map solid (brushes, mostly not
 * diggable, the back two brushes meeting on a seam), its top and front open. Every solid side gets a
 * dig wall (SkyDig.Probe#wallMaterial over the face's sample points, as the client's DigWalls samples them).
 */
class DigWallMaterialTest {
	private static final int NOT_DIGGABLE = 0;
	private static final int GRAVEL = Proto.TRI_DIGGABLE | (Proto.DIG_GRAVEL << Proto.TRI_MATERIAL_SHIFT);

	/** A box brush's 12 triangles, wound so their normals face out. */
	static void box(List<SkyTri> out, double x0, double y0, double z0, double x1, double y1, double z1, int flags) {
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

	/** The scene: dug cell (0,0,0) in a wall one block high; open above, its mouth on +z (the street). */
	private static List<SkyTri> scene() {
		List<SkyTri> tris = new ArrayList<>();
		box(tris, -6, -3, -6, 7, 0, 6, NOT_DIGGABLE); // the floor (sidewalk, not diggable)
		box(tris, -6, 0, -6, 0, 1, 1, NOT_DIGGABLE); // left of the hole
		box(tris, 1, 0, -6, 7, 1, 1, NOT_DIGGABLE); // right of it
		box(tris, 0, 0, -1, 1, 1, 0, GRAVEL); // behind it: a diggable brush...
		box(tris, 0, 0, -6, 1, 1, -1, NOT_DIGGABLE); // ...on a seam with a building one
		return tris;
	}

	/** GMod's collision voxels for the scene: the brushes, less the dug cell. */
	private static boolean solid(double x, double y, double z) {
		if (x <= -6 || x >= 7 || z <= -6 || z >= 6 || y <= -3) {
			return false;
		}
		boolean dug = x >= 0 && x <= 1 && z >= 0 && z <= 1 && y >= 0 && y <= 1;
		return y < 0 || (y < 1 && z < 1 && !dug);
	}

	/** DigWalls' sample points on one face of cell (0,0,0): materials of its 4x4 pieces. */
	private static List<Integer> face(List<SkyTri> tris, SkyDig.Voxels voxels, Direction dir) {
		SkyDig.Probe probe = new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		});
		int nx = dir.getStepX(), ny = dir.getStepY(), nz = dir.getStepZ();
		probe.around(nx, ny, nz, nx + 1, ny + 1, nz + 1);
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double u = (i + 0.5) / 4, v = (j + 0.5) / 4;
				double[] p = switch (dir) {
					case NORTH -> new double[] { u, v, 0 };
					case SOUTH -> new double[] { 1 - u, v, 1 };
					case WEST -> new double[] { 0, v, 1 - u };
					case EAST -> new double[] { 1, v, u };
					case UP -> new double[] { u, 1, v };
					case DOWN -> new double[] { u, 0, v };
				};
				out.add(probe.wallMaterial(voxels, p[0] + nx * 0.02, p[1] + ny * 0.02, p[2] + nz * 0.02));
			}
		}
		return out;
	}

	@Test
	void floorBackAndSidesOfAHoleInABuildingAreWalled() {
		List<SkyTri> tris = scene();
		for (Direction dir : new Direction[] { Direction.DOWN, Direction.NORTH, Direction.WEST, Direction.EAST }) {
			for (int m : face(tris, DigWallMaterialTest::solid, dir)) {
				assertTrue(m > SkyDig.AIR, dir + ": a piece without a wall");
			}
		}
		// Not diggable: stone. The back's diggable brush: its own material.
		assertEquals(Proto.DIG_STONE, (int) face(tris, DigWallMaterialTest::solid, Direction.DOWN).get(0));
		assertEquals(Proto.DIG_GRAVEL, (int) face(tris, DigWallMaterialTest::solid, Direction.NORTH).get(5));
	}

	@Test
	void openSidesStayOpen() {
		List<SkyTri> tris = scene();
		for (Direction dir : new Direction[] { Direction.UP, Direction.SOUTH }) {
			for (int m : face(tris, DigWallMaterialTest::solid, dir)) {
				assertEquals(SkyDig.AIR, m, dir + ": a wall in the open");
			}
		}
	}

	@Test
	void withoutVoxelsANonDiggableFloorWasOpen() {
		// The bug: the surfaces alone call a building's floor KEEP, which drew nothing.
		for (int m : face(scene(), null, Direction.DOWN)) {
			assertEquals(SkyDig.AIR, m);
		}
	}
}
