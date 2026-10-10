package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D6: a hole dug through gm_construct's sidewalk had a stone lid over it, seen from inside. The
 * sidewalk is brush 128 (Source x -704..496, y -1904..-1024, z -160..-144; with the map's slot
 * origin (0, 0) and oyUnits 2704 that's MC x -17.6..12.4, z 25.6..47.6, y 63.6..64.0), next to it
 * brush 346 (y -1024..-896, z -156..-144: MC z 22.4..25.6, y 63.9..64.0). The user's dug cells:
 * x 7..10, z 25..29, y 63. The slab's top lies on the 63/64 cell boundary; the module's voxelizer
 * gives half a sub-voxel of slack along a flat face's normal, so cell 64 holds a 1/8 sliver
 * (64.0..64.125) of it: the ceiling face of the dug cells sampled that sliver as solid.
 */
class DigLidTest {
	private static final int NOT_DIGGABLE = 0;
	private static final double SLIVER = 0.125;

	private record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
	}

	private static List<Box> sidewalk() {
		return List.of(new Box(-17.6, 63.6, 25.6, 12.4, 64.0, 47.6), new Box(-35.08, 63.9, 22.4, 46.4, 64.0, 25.6));
	}

	private static boolean dug(double x, double y, double z) {
		return x >= 7 && x < 11 && z >= 25 && z < 30 && y >= 63 && y < 64;
	}

	/** The module's voxels: each brush plus the sliver over its top, less the dug cells. */
	private static SkyDig.Voxels voxels(List<Box> boxes) {
		return (x, y, z) -> {
			if (dug(x, y, z)) {
				return false;
			}
			for (Box b : boxes) {
				if (x >= b.x0 && x <= b.x1 && z >= b.z0 && z <= b.z1 && y >= b.y0 && y <= b.y1 + SLIVER) {
					return true;
				}
			}
			return false;
		};
	}

	private static List<SkyTri> tris(List<Box> boxes) {
		List<SkyTri> out = new ArrayList<>();
		for (Box b : boxes) {
			DigWallMaterialTest.box(out, b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, NOT_DIGGABLE);
		}
		return out;
	}

	/** DigWalls' 4x4 samples on the ceiling of dug cell (x, 63, z): just inside cell (x, 64, z). */
	private static List<Integer> ceiling(List<Box> boxes, int x, int z) {
		List<SkyTri> tris = tris(boxes);
		SkyDig.Probe probe = new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		});
		probe.around(x, 64, z, x + 1, 65, z + 1);
		SkyDig.Voxels v = voxels(boxes);
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				out.add(probe.wallMaterial(v, x + (i + 0.5) / 4, 64 + 0.02, z + (j + 0.5) / 4));
			}
		}
		return out;
	}

	@Test
	void sliverOfTheSidewalkIsSolidForTheModule() {
		assertTrue(voxels(sidewalk()).solid(8.5, 64.02, 27.5), "the test's voxels must have the lid sliver (else it proves nothing)");
	}

	@Test
	void noLidOverAHoleThroughTheSidewalk() {
		for (int x = 7; x <= 10; x++) {
			for (int z = 25; z <= 29; z++) {
				for (int m : ceiling(sidewalk(), x, z)) {
					assertEquals(SkyDig.AIR, m, "a lid piece over dug cell " + x + ",63," + z);
				}
			}
		}
	}

	@Test
	void aThickOverhangKeepsItsCeiling() {
		List<Box> boxes = new ArrayList<>(sidewalk());
		boxes.add(new Box(6, 64, 24, 12, 66, 31)); // a 2-block brush over the hole (a ledge, a building's floor)
		for (int m : ceiling(boxes, 8, 27)) {
			assertEquals(Proto.DIG_STONE, m, "the overhang's ceiling");
		}
	}
}
