package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hull world: a hole dug through gm_construct's sidewalk. The hull file makes one block layer of it
 * (cells y 63, mirror blocks); the map's slab is only 63.6..64.0 thick with nothing under it. The
 * wall of dug cell (8, 63, 27) toward the mirror block (9, 63, 27) was drawn only where the slab is,
 * so the lower part of the hole's wall showed the hole's clear colour. The wall pieces (DigWalls'
 * 4x4 samples, just inside the neighbour) decide per piece; each piece is drawn at most once.
 */
class MirrorWallTest {
	private static final int NOT_DIGGABLE = 0;

	private record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
	}

	private static final Box SLAB = new Box(-17.6, 63.6, 25.6, 12.4, 64.0, 47.6);

	private static SkyDig.Voxels voxels(List<Box> boxes) {
		return (x, y, z) -> {
			if (x >= 8 && x < 9 && z >= 27 && z < 28 && y >= 63 && y < 64) {
				return false; // the dug cell
			}
			for (Box b : boxes) {
				if (x >= b.x0 && x <= b.x1 && z >= b.z0 && z <= b.z1 && y >= b.y0 && y <= b.y1) {
					return true;
				}
			}
			return false;
		};
	}

	private static SkyDig.Probe probe(List<Box> boxes) {
		List<SkyTri> tris = new ArrayList<>();
		for (Box b : boxes) {
			DigWallMaterialTest.box(tris, b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, NOT_DIGGABLE);
		}
		return new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		});
	}

	/** The 16 pieces of the east wall of dug cell (8, y, 27): just inside neighbour (9, y, 27). */
	private static List<Integer> eastWall(List<Box> boxes, int y, boolean mirror) {
		SkyDig.Probe probe = probe(boxes);
		probe.around(9, y, 27, 10, y + 1, 28);
		SkyDig.Voxels v = voxels(boxes);
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				out.add(probe.wallMaterial(v, mirror, 9.02, y + (j + 0.5) / 4, 27 + (i + 0.5) / 4));
			}
		}
		return out;
	}

	private static long count(List<Integer> pieces, java.util.function.IntPredicate p) {
		return pieces.stream().mapToInt(Integer::intValue).filter(p).count();
	}

	@Test
	void wallTowardMirrorBlockIsWholeUnderAThinSlab() {
		List<Integer> wall = eastWall(List.of(SLAB), 63, true);
		// slab part (63.625, 63.875): the map's own material; under it (63.125, 63.375): the mirror block
		assertEquals(8, count(wall, m -> m > SkyDig.AIR), "the slab's part " + wall);
		assertEquals(8, count(wall, m -> m == SkyDig.Probe.MIRROR_FACE), "the void under the slab " + wall);
		assertEquals(0, count(wall, m -> m == SkyDig.AIR), "no gap " + wall);
	}

	@Test
	void slabBuriedInTheFloorDoesNotOpenTheWallsTop() {
		// gm_construct's garage floor (x 1240, y -40): the floor brush z -160..-144 (63.6..64.0) holds a second
		// brush z -173..-152 (63.275..63.8). For the wall's top pieces (63.875) the buried brush's top is the
		// nearest surface, and the D6 sliver rule took them for open air over a floor: the hole's top band was
		// missing and the sky showed (live 2026-10-09, white band round the hole in a dark room).
		Box buried = new Box(-17.6, 63.275, 25.6, 12.4, 63.8, 47.6);
		List<Integer> wall = eastWall(List.of(SLAB, buried), 63, true);
		assertEquals(0, count(wall, m -> m == SkyDig.AIR), "no gap " + wall);
		for (int i = 0; i < 4; i++) {
			assertTrue(wall.get(i * 4 + 3) > SkyDig.AIR, "top piece " + i + ": the floor's own material " + wall);
		}
	}

	@Test
	void slicedSidewalkSliverStillOpen() {
		// D6 itself: a point 1/8 block over the slab's top with open air above it is not solid
		SkyDig.Probe probe = probe(List.of(SLAB));
		probe.around(9, 64, 27, 10, 65, 28);
		SkyDig.Voxels sliver = (x, y, z) -> y >= 63.6 && y <= 64.125;
		assertEquals(SkyDig.AIR, probe.wallMaterial(sliver, 9.02, 64.06, 27.5));
		assertFalse(probe.underTopFace(9.02, 64.06, 27.5, 1.0));
		assertTrue(probe.underTopFace(9.02, 63.875, 27.5, 1.0), "inside the slab, under its top");
	}

	@Test
	void wallTowardPlainAirStaysAsBefore() {
		// not a mirror block (the mirror world, or MC air): only where the map is
		List<Integer> wall = eastWall(List.of(SLAB), 63, false);
		assertEquals(8, count(wall, m -> m > SkyDig.AIR));
		assertEquals(8, count(wall, m -> m == SkyDig.AIR));
		assertEquals(0, count(wall, m -> m == SkyDig.Probe.MIRROR_FACE));
	}

	@Test
	void openAirOverAFloorGetsNoMirrorFace() {
		// A thin wall (x 9.4..9.6) standing on the slab: the hull makes cell (9, 64, 27) a mirror block.
		// Beside the wall, over the slab's top, is open air: no face there.
		Box wall = new Box(9.4, 64.0, 20.0, 9.6, 70.0, 35.0);
		SkyDig.Probe probe = probe(List.of(SLAB, wall));
		probe.around(9, 64, 27, 10, 65, 28);
		SkyDig.Voxels v = voxels(List.of(SLAB, wall));
		assertEquals(SkyDig.AIR, probe.wallMaterial(v, true, 9.02, 64.5, 27.5), "in front of the wall, over the floor");
		assertTrue(probe.overFloor(9.02, 64.5, 27.5));
		assertFalse(probe.overFloor(9.02, 63.3, 27.5), "under the slab is the void, not open air");
	}

	@Test
	void openAirOverTheLandGetsNoMirrorFace() {
		// Land (a terrain height field) at 63.7 across the cell: the part above it is open air.
		List<SkyTri> land = new ArrayList<>();
		land.add(new SkyTri(new float[] { 0, 63.7f, 0, 0, 63.7f, 40, 40, 63.7f, 0 }, 0, dev.gmodcraft.link.Proto.TRI_TERRAIN));
		land.add(new SkyTri(new float[] { 40, 63.7f, 0, 0, 63.7f, 40, 40, 63.7f, 40 }, 0, dev.gmodcraft.link.Proto.TRI_TERRAIN));
		SkyDig.Probe probe = new SkyDig.Probe((box, out) -> out.addAll(land));
		probe.around(9, 63, 27, 10, 64, 28);
		assertEquals(SkyDig.AIR, probe.wallMaterial(null, true, 9.02, 63.875, 27.5));
		assertTrue(probe.wallMaterial(null, true, 9.02, 63.125, 27.5) > SkyDig.AIR, "under the land: the land's material");
	}

	/** The host's voxels with the voxelizer's slack: each box reaches {@code slack} further on every side. */
	private static SkyDig.Voxels slackVoxels(List<Box> boxes, double slack) {
		return (x, y, z) -> {
			for (Box b : boxes) {
				if (x >= b.x0 - slack && x <= b.x1 + slack && z >= b.z0 - slack && z <= b.z1 + slack && y >= b.y0 - slack && y <= b.y1 + slack) {
					return true;
				}
			}
			return false;
		};
	}

	@Test
	void noStripOverTheHoleAlongAWallFoot() {
		// gm_construct, sidewalk at the foot of the wall at MC z 48 (live 2026-10-09, hull world): the hole's
		// top face (dug cell (0, 63, 47) toward the air cell above) got a 1/8-block strip of pieces along the
		// wall, at the sidewalk's height: the voxels there are solid by the slack of both the sidewalk's top and
		// the wall's face, and the floor-only sliver rule (D6) gave up because the wall's slack is solid above.
		Box walk = new Box(-17.6, 63.6, 25.6, 12.4, 64.0, 48.0);
		Box wall = new Box(-12.8, 63.6, 48.0, 6.4, 66.55, 48.4);
		List<Box> boxes = List.of(walk, wall);
		SkyDig.Probe probe = probe(boxes);
		probe.around(0, 64, 47, 1, 65, 48);
		SkyDig.Voxels v = slackVoxels(boxes, 0.125);
		for (double z = 47.01; z < 48; z += 0.02) {
			for (double x : new double[] { 0.1, 0.5, 0.9 }) {
				assertEquals(SkyDig.AIR, probe.wallMaterial(v, false, x, 64.02, z), "over the hole at x " + x + " z " + z);
			}
		}
		// the wall's side face toward it (dug cell (0, 64, 47) toward (0, 64, 48)): inside the wall, still solid
		probe.around(0, 64, 48, 1, 65, 49);
		assertTrue(probe.wallMaterial(v, false, 0.5, 64.5, 48.02) > SkyDig.AIR);
		// in front of the wall, over the sidewalk: open (the wall's slack reaches 1/8 block out)
		probe.around(0, 64, 47, 1, 65, 48);
		for (double z = 47.5; z < 48; z += 0.02) {
			assertEquals(SkyDig.AIR, probe.wallMaterial(v, false, 0.98, 64.5, z), "in front of the wall at z " + z);
		}
	}

	@Test
	void touchingBrushesBehindTheWallStaySolid() {
		// gm_construct's wall foot (map collision, replayed offline 2026-10-10): the sidewalk's last strip
		// (z 47.6..48) touches the wall's foot (z 48..48.4), which touches a big block behind it (z 48.4..63.8,
		// reaching past any single ray). Just behind z 48 the nearest surface is the strip's end face (the point
		// is in front of it); the wall's face toward the hole must stay whole (a first-hit ray left it open).
		Box strip = new Box(-12.8, 63.6, 47.6, 12.4, 64.0, 48.0);
		Box foot = new Box(-12.8, 63.6, 48.0, 6.4, 66.55, 48.4);
		Box behind = new Box(-19.2, 63.6, 48.4, 6.4, 73.6, 63.8);
		List<Box> boxes = List.of(SLAB, strip, foot, behind);
		SkyDig.Probe probe = probe(boxes);
		probe.around(0, 63, 48, 1, 64, 49);
		SkyDig.Voxels v = slackVoxels(boxes, 0.125);
		for (double y = 63.62; y < 64; y += 0.05) {
			assertTrue(probe.wallMaterial(v, true, 0.5, y, 48.02) > SkyDig.AIR, "the wall's foot at y " + y);
		}
	}

	@Test
	void insideAWallFootStaysSolid() {
		// a thin wall (x 9.4..9.6) standing on the slab: a point inside it just over the slab's top is nearest the
		// slab's top (in front of it), but a ray up from it meets the wall's top from behind: solid
		Box wall = new Box(9.4, 64.0, 20.0, 9.6, 66.0, 35.0);
		List<Box> boxes = List.of(SLAB, wall);
		SkyDig.Probe probe = probe(boxes);
		probe.around(9, 64, 27, 10, 65, 28);
		assertTrue(probe.wallMaterial(slackVoxels(boxes, 0.0), false, 9.5, 64.05, 27.5) > SkyDig.AIR, "inside the wall's foot");
		assertEquals(SkyDig.AIR, probe.wallMaterial(slackVoxels(boxes, 0.125), false, 9.3, 64.05, 27.5), "beside it, in the slack");
	}

	@Test
	void slackUnderASlabStillSealsTowardAMirrorBlock() {
		// the void under the sidewalk: the slab bottom's slack is open air now, and the mirror fallback seals it
		SkyDig.Probe probe = probe(List.of(SLAB));
		probe.around(9, 63, 27, 10, 64, 28);
		SkyDig.Voxels v = slackVoxels(List.of(SLAB), 0.125);
		for (double y = 63.01; y < 63.6; y += 0.02) {
			int m = probe.wallMaterial(v, true, 9.02, y, 27.5);
			assertTrue(m == SkyDig.Probe.MIRROR_FACE || m > SkyDig.AIR, "sealed at y " + y + ": " + m);
		}
	}

	@Test
	void whichNeighboursGetAWall() {
		// dug cell next to an undug mirror block: a wall
		assertTrue(SkyDig.Probe.wallBetween(true, false, false, true, true));
		// dug cell next to air (the map decides, as in the mirror world)
		assertTrue(SkyDig.Probe.wallBetween(true, false, false, false, false));
		// next to a plain Minecraft block: it draws its own face
		assertFalse(SkyDig.Probe.wallBetween(true, false, false, true, false));
		// next to another dug cell (the mirror block mined too): open
		assertFalse(SkyDig.Probe.wallBetween(true, false, true, false, false));
		assertFalse(SkyDig.Probe.wallBetween(true, false, true, true, true));
		// an undug mirror block next to an undug mirror block or the map: nothing (only dug cells have walls)
		assertFalse(SkyDig.Probe.wallBetween(false, true, false, true, true));
		assertFalse(SkyDig.Probe.wallBetween(false, true, false, false, false));
		// the dug cell filled again with a block: gone
		assertFalse(SkyDig.Probe.wallBetween(true, true, false, true, true));
	}
}
