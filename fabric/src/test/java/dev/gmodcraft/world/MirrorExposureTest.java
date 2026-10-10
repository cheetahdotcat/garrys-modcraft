package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Hull world: which mirror blocks border a dug cell (sent to GMod as solid, full MC collision). */
class MirrorExposureTest {
	private static long[] bits(int... cells) {
		long[] b = new long[64];
		for (int i = 0; i < cells.length; i += 3) {
			int bit = cells[i] + 16 * cells[i + 2] + 256 * cells[i + 1];
			b[bit >> 6] |= 1L << (bit & 63);
		}
		return b;
	}

	private static long[][] near() {
		return new long[7][];
	}

	@Test
	void nothingDugNothingExposed() {
		assertFalse(MirrorExposure.any(null));
		assertFalse(MirrorExposure.any(near()));
		long[][] n = near();
		n[MirrorExposure.EAST] = new long[64];
		assertFalse(MirrorExposure.any(n), "an all-zero neighbour has nothing dug");
		assertFalse(MirrorExposure.exposed(n, 15, 3, 3));
	}

	@Test
	void faceNeighboursInsideTheSection() {
		long[][] n = near();
		n[MirrorExposure.SELF] = bits(5, 6, 7);  // a dug cell (x 5, y 6, z 7)
		assertTrue(MirrorExposure.any(n));
		// the hole's floor, ceiling and four walls
		assertTrue(MirrorExposure.exposed(n, 5, 5, 7), "floor below");
		assertTrue(MirrorExposure.exposed(n, 5, 7, 7), "above");
		assertTrue(MirrorExposure.exposed(n, 4, 6, 7));
		assertTrue(MirrorExposure.exposed(n, 6, 6, 7));
		assertTrue(MirrorExposure.exposed(n, 5, 6, 6));
		assertTrue(MirrorExposure.exposed(n, 5, 6, 8));
		// diagonals and farther cells don't border it
		assertFalse(MirrorExposure.exposed(n, 4, 5, 7), "edge diagonal");
		assertFalse(MirrorExposure.exposed(n, 6, 7, 8), "corner diagonal");
		assertFalse(MirrorExposure.exposed(n, 5, 4, 7), "two below");
	}

	@Test
	void acrossSectionBorders() {
		long[][] n = near();
		n[MirrorExposure.DOWN] = bits(2, 15, 3);   // dug at the top of the section below
		n[MirrorExposure.UP] = bits(4, 0, 4);      // at the bottom of the one above
		n[MirrorExposure.WEST] = bits(15, 8, 9);
		n[MirrorExposure.EAST] = bits(0, 1, 1);
		n[MirrorExposure.NORTH] = bits(10, 10, 15);
		n[MirrorExposure.SOUTH] = bits(11, 11, 0);
		assertTrue(MirrorExposure.exposed(n, 2, 0, 3), "floor cell at y 0 over a dug cell below the section");
		assertTrue(MirrorExposure.exposed(n, 4, 15, 4));
		assertTrue(MirrorExposure.exposed(n, 0, 8, 9));
		assertTrue(MirrorExposure.exposed(n, 15, 1, 1));
		assertTrue(MirrorExposure.exposed(n, 10, 10, 0));
		assertTrue(MirrorExposure.exposed(n, 11, 11, 15));
		// a neighbour section's bit only counts on the shared face
		assertFalse(MirrorExposure.exposed(n, 2, 1, 3), "one cell up from the border");
		assertFalse(MirrorExposure.exposed(n, 1, 8, 9));
		assertFalse(MirrorExposure.exposed(n, 15, 8, 9), "the west section's dug cell is at x 15 of ITS section, not ours");
	}
}
