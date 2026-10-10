package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SkyGroundTest {
	/** Flat diggable ground of this material at height y over [-10, 10] in x and z. */
	private static List<SkyTri> ground(double y, int material, int extraFlags) {
		int flags = Proto.TRI_DIGGABLE | Proto.TRI_TERRAIN | extraFlags | (material << Proto.TRI_MATERIAL_SHIFT);
		List<SkyTri> out = new ArrayList<>();
		out.add(new SkyTri(new float[] { -10, (float) y, -10, 10, (float) y, -10, 10, (float) y, 10 }, 0, flags));
		out.add(new SkyTri(new float[] { -10, (float) y, -10, 10, (float) y, 10, -10, (float) y, 10 }, 0, flags));
		return out;
	}

	/** A triangle source over a fixed list that counts its queries. */
	private static final class Source implements SkyGround.Tris {
		List<SkyTri> tris;
		int queries;

		Source(List<SkyTri> tris) {
			this.tris = tris;
		}

		@Override
		public void near(AABB box, List<SkyTri> out) {
			this.queries++;
			for (SkyTri t : this.tris) {
				if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
					out.add(t);
				}
			}
		}
	}

	private static int[] cell(BlockPos p) {
		return new int[] { p.getX(), p.getY(), p.getZ() };
	}

	// ---- placementCell -> surface cell ---------------------------------------------------------

	@Test
	void surfaceCellLowInCell() {
		// Ground at 10.3: the crosshair's placement cell is the surface cell itself.
		SkyRay.Hit hit = SkyRay.cast(ground(10.3, Proto.DIG_GRASS, 0), 0.5, 12.0, 0.5, 0.5, 8.0, 0.5);
		assertArrayEquals(new int[] { 0, 10, 0 }, SkyRay.placementCell(hit));
		assertArrayEquals(new int[] { 0, 10, 0 }, cell(SkyGround.surfaceCell(hit.x(), hit.y(), hit.z())));
	}

	@Test
	void surfaceCellHighInCell() {
		// Ground at 10.7: the placement cell is the one above; the surface cell is the one below it.
		SkyRay.Hit hit = SkyRay.cast(ground(10.7, Proto.DIG_GRASS, 0), 0.5, 12.0, 0.5, 0.5, 8.0, 0.5);
		assertArrayEquals(new int[] { 0, 11, 0 }, SkyRay.placementCell(hit));
		assertArrayEquals(new int[] { 0, 10, 0 }, cell(SkyGround.surfaceCell(hit.x(), hit.y(), hit.z())));
	}

	@Test
	void surfaceCellOnWholeBlockHeight() {
		// Ground exactly at 11.0: the cell under it, not the air cell starting there.
		assertArrayEquals(new int[] { 3, 10, -2 }, cell(SkyGround.surfaceCell(3.2, 11.0, -1.5)));
	}

	// ---- the ground a cell stands for ----------------------------------------------------------

	@Test
	void groundUnderSomethingRestingOnTheMap() {
		// What snow layers, spawn rules and friction read: the cell below the placement cell (snow,
		// a mob's feet) and floor(feet - 0.5) (friction) for both alignments.
		for (double h : new double[] { 10.3, 10.7, 10.0, 10.95 }) {
			SkyGround g = new SkyGround(new Source(ground(h, Proto.DIG_GRASS, 0)), 8);
			SkyRay.Hit hit = SkyRay.cast(ground(h, Proto.DIG_GRASS, 0), 0.5, 12.0, 0.5, 0.5, 8.0, 0.5);
			int[] place = SkyRay.placementCell(hit);
			SkyGround.Ground below = g.at(place[0], place[1] - 1, place[2]);
			assertNotNull(below, "below placement cell, ground at " + h);
			assertEquals(Proto.DIG_GRASS, below.material());
			assertEquals((int) Math.floor(h), below.surfaceY());
			assertNotNull(g.at(0, (int) Math.floor(h - 0.500001), 0), "friction cell, ground at " + h);
		}
	}

	@Test
	void spawnCheckReadsMaterial() {
		// Animal.checkAnimalSpawnRules reads pos.below(); a mob stands on the map's sand at 64.4
		// (spawn pos = its feet cell, 64): the cell below answers sand, not AIR.
		SkyGround g = new SkyGround(new Source(ground(64.4, Proto.DIG_SAND, 0)), 8);
		BlockPos spawn = BlockPos.containing(0.5, 64.4, 0.5);
		SkyGround.Ground below = g.at(spawn.below().getX(), spawn.below().getY(), spawn.below().getZ());
		assertNotNull(below);
		assertEquals(Proto.DIG_SAND, below.material());
		// Higher up in open air there's no ground.
		assertNull(g.at(0, 70, 0));
		// Far below the surface (a cell deep in the ground) neither: nothing rests there.
		assertNull(g.at(0, 60, 0));
	}

	@Test
	void noMaterialMeansStoneAndPropsAreNotGround() {
		assertEquals(Proto.DIG_STONE, new SkyGround(new Source(ground(5.5, Proto.DIG_NONE, 0)), 8).at(0, 4, 0).material());
		assertNull(new SkyGround(new Source(ground(5.5, Proto.DIG_GRASS, Proto.TRI_DYNAMIC)), 8).at(0, 4, 0));
	}

	@Test
	void wallsAreNotGround() {
		List<SkyTri> wall = new ArrayList<>();
		int flags = Proto.TRI_DIGGABLE | (Proto.DIG_GRASS << Proto.TRI_MATERIAL_SHIFT);
		wall.add(new SkyTri(new float[] { 0.5f, 0, -5, 0.5f, 10, -5, 0.5f, 10, 5 }, 0, flags));
		assertNull(new SkyGround(new Source(wall), 8).at(0, 4, 0));
	}

	@Test
	void highestSurfaceInWindowWins() {
		List<SkyTri> tris = ground(10.2, Proto.DIG_STONE, 0);
		tris.addAll(ground(11.1, Proto.DIG_SNOW, 0));
		assertEquals(Proto.DIG_SNOW, new SkyGround(new Source(tris), 8).at(0, 10, 0).material());
	}

	// ---- the cache -----------------------------------------------------------------------------

	@Test
	void cachedUntilRegionChanges() {
		Source src = new Source(ground(10.3, Proto.DIG_GRASS, 0));
		SkyGround g = new SkyGround(src, 8);
		assertEquals(Proto.DIG_GRASS, g.at(0, 9, 0).material());
		assertEquals(Proto.DIG_GRASS, g.at(0, 9, 0).material());
		assertNull(g.at(0, 20, 0));
		assertNull(g.at(0, 20, 0));
		assertEquals(2, src.queries); // one per cell, misses cached too
		assertEquals(2, g.cachedCells());

		// The host changes the ground (snow now); the region holding y 8..15 is re-sent.
		src.tris = ground(10.3, Proto.DIG_SNOW, 0);
		assertEquals(Proto.DIG_GRASS, g.at(0, 9, 0).material()); // stale until told
		g.regionChanged(0, 8, 0);
		assertEquals(Proto.DIG_SNOW, g.at(0, 9, 0).material());
		assertEquals(3, src.queries);
	}

	@Test
	void regionChangeDropsTheRegionBelow() {
		// Cell y 7 (region 0) looks up to y 9, into region 1: a change to region 1 drops it too.
		Source src = new Source(ground(8.5, Proto.DIG_GRASS, 0));
		SkyGround g = new SkyGround(src, 8);
		assertEquals(Proto.DIG_GRASS, g.at(0, 7, 0).material());
		src.tris = ground(8.5, Proto.DIG_SAND, 0);
		g.regionChanged(0, 8, 0);
		assertEquals(Proto.DIG_SAND, g.at(0, 7, 0).material());
		// Other regions keep their answers.
		g.at(40, 7, 40);
		int before = src.queries;
		g.regionChanged(0, 8, 0);
		g.at(40, 7, 40);
		assertEquals(before, src.queries);
	}

	@Test
	void clearDropsEverything() {
		Source src = new Source(ground(10.3, Proto.DIG_GRASS, 0));
		SkyGround g = new SkyGround(src, 8);
		g.at(0, 9, 0);
		g.clear();
		assertEquals(0, g.cachedCells());
	}
}
