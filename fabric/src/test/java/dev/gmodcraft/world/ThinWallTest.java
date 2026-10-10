package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** v38: the rule digThinWalls' gate, ThinWall.thin, over a stubbed collision store. */
class ThinWallTest {
	/** Cells by "x,y,z": solid count and extents; dug and terrain cells. */
	private static final class Cells implements ThinWall.Lookup {
		final Map<String, Integer> count = new HashMap<>();
		final Map<String, int[]> ext = new HashMap<>();
		final Set<String> dug = new HashSet<>();
		final Set<String> land = new HashSet<>();

		static String k(int x, int y, int z) {
			return x + "," + y + "," + z;
		}

		Cells put(int x, int y, int z, int n, int ex, int ey, int ez) {
			this.count.put(k(x, y, z), n);
			this.ext.put(k(x, y, z), new int[] { ex, ey, ez });
			return this;
		}

		@Override
		public int solidCount(int x, int y, int z) {
			return this.count.getOrDefault(k(x, y, z), 0);
		}

		@Override
		public int[] extents(int x, int y, int z) {
			return this.ext.getOrDefault(k(x, y, z), new int[] { 8, 8, 8 });
		}

		@Override
		public boolean isDug(int x, int y, int z) {
			return this.dug.contains(k(x, y, z));
		}

		@Override
		public boolean terrain(int x, int y, int z) {
			return this.land.contains(k(x, y, z));
		}
	}

	@Test
	void aThinWallWithAirOnBothSidesIsThin() {
		// A 10 u wall (2 layers along x) through the cell, air on both sides.
		Cells c = new Cells().put(0, 0, 0, 128, 2, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
	}

	@Test
	void aSixteenUnitWallAtHalfACellIsStillThin() {
		// 16 u = 3.2 layers: voxelized to 4 layers at some alignments, 256 of 512.
		Cells c = new Cells().put(0, 0, 0, 256, 4, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
		// Split over two cells (2 + 2 layers): both thin.
		c.put(1, 0, 0, 128, 2, 8, 8).put(0, 0, 0, 128, 2, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
		assertTrue(ThinWall.thin(c, 1, 0, 0));
	}

	@Test
	void theEdgeOfAThickWallIsNot() {
		// 2 layers at the +x side, the wall's thick part in the next cell.
		Cells c = new Cells().put(0, 0, 0, 128, 2, 8, 8).put(1, 0, 0, 512, 8, 8, 8);
		assertFalse(ThinWall.thin(c, 0, 0, 0));
		// Behind it more than half solid is enough.
		c.put(1, 0, 0, 257, 5, 8, 8);
		assertFalse(ThinWall.thin(c, 0, 0, 0));
		c.put(1, 0, 0, 256, 4, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
	}

	@Test
	void aThickNeighbourOffTheThinAxisDoesNotHelp() {
		// A thin wall (thin along x) standing on a thick floor: the full cell below doesn't count.
		Cells c = new Cells().put(0, 0, 0, 128, 2, 8, 8).put(0, -1, 0, 512, 8, 8, 8).put(0, 0, 1, 512, 8, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
		// A floor slab (thin along y) on solid ground below is the ground's top: not thin.
		Cells f = new Cells().put(0, 0, 0, 128, 8, 2, 8).put(0, -1, 0, 512, 8, 8, 8);
		assertFalse(ThinWall.thin(f, 0, 0, 0));
	}

	@Test
	void aDugNeighbourOnTheThinAxisCountsAsTheThickPart() {
		// The thick wall's full cell was dug first: its edge left behind may still be dug.
		Cells c = new Cells().put(0, 0, 0, 128, 2, 8, 8);
		c.dug.add(Cells.k(1, 0, 0));
		assertFalse(ThinWall.thin(c, 0, 0, 0));
	}

	@Test
	void mostlySolidEmptyAndLandAreNotThin() {
		Cells c = new Cells().put(0, 0, 0, 300, 5, 8, 8).put(5, 0, 0, 0, 0, 0, 0).put(9, 0, 0, 64, 8, 1, 8);
		assertFalse(ThinWall.thin(c, 0, 0, 0), "more than half solid");
		assertFalse(ThinWall.thin(c, 5, 0, 0), "nothing known: fail open");
		assertTrue(ThinWall.thin(c, 9, 0, 0));
		c.land.add(Cells.k(9, 0, 0));
		assertFalse(ThinWall.thin(c, 9, 0, 0), "displacements are shells without volume");
	}

	@Test
	void aDiagonalChecksEveryNeighbour() {
		// Spans the cell on every axis (a slope or a diagonal wall): any thick face neighbour helps.
		Cells c = new Cells().put(0, 0, 0, 200, 8, 8, 8);
		assertTrue(ThinWall.thin(c, 0, 0, 0));
		c.put(0, -1, 0, 512, 8, 8, 8);
		assertFalse(ThinWall.thin(c, 0, 0, 0));
	}
}
