package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** W2: the mask gmodcraft:underground lays over vanilla terrain. */
class UndergroundColumnsTest {
	private static final int FLOOR = 64;

	@Test
	void maskPerColumn() {
		for (boolean foot : new boolean[] { true, false }) {
			for (int y = FLOOR; y < 1024; y++) {
				assertEquals(DeepColumns.AIR, UndergroundColumns.at(FLOOR, foot, y), "air from the floor up at " + y);
			}
			for (int y = -1024; y < DeepColumns.BEDROCK_Y; y++) {
				assertEquals(DeepColumns.AIR, UndergroundColumns.at(FLOOR, foot, y), "air under the bedrock at " + y);
			}
			assertEquals(DeepColumns.BEDROCK, UndergroundColumns.at(FLOOR, foot, -64));
			for (int y = -63; y < FLOOR - 4; y++) {
				assertEquals(UndergroundColumns.KEEP, UndergroundColumns.at(FLOOR, foot, y), "vanilla at " + y);
			}
		}
		for (int y = FLOOR - 4; y < FLOOR; y++) {
			assertEquals(DeepColumns.STONE, UndergroundColumns.at(FLOOR, true, y), "the cap at " + y);
		}
		assertEquals(DeepColumns.GRASS, UndergroundColumns.at(FLOOR, false, FLOOR - 1));
		for (int y = FLOOR - 4; y < FLOOR - 1; y++) {
			assertEquals(DeepColumns.DIRT, UndergroundColumns.at(FLOOR, false, y), "dirt at " + y);
		}
	}

	@Test
	void fillMasksAndAddsVeinsBelowTheCap() {
		int lo = -64, height = FLOOR - lo;
		int[] codes = new int[height * 256];
		boolean[] foot = new boolean[256];
		for (int i = 0; i < 256; i++) {
			foot[i] = (i & 15) < 8;  // west half under a map
		}
		for (int y = lo; y < FLOOR; y++) {
			for (int i = 0; i < 256; i++) {
				// vanilla: deepslate below 0, stone above, a cave column (KEEP: air) at x 15, z 15
				codes[(y - lo) * 256 + i] = i == 255 ? UndergroundColumns.KEEP : y < 0 ? DeepColumns.DEEPSLATE : DeepColumns.STONE;
			}
		}
		UndergroundColumns.fill(1234L, FLOOR, 3, -7, foot, lo, height, codes);
		int ores = 0;
		for (int y = lo; y < FLOOR; y++) {
			for (int i = 0; i < 256; i++) {
				int c = codes[(y - lo) * 256 + i];
				if (y == -64) {
					assertEquals(DeepColumns.BEDROCK, c);
				} else if (y >= FLOOR - 4) {
					int want = foot[i] ? DeepColumns.STONE : y == FLOOR - 1 ? DeepColumns.GRASS : DeepColumns.DIRT;
					assertEquals(want, c, "cap / surface at y " + y + " column " + i);
				} else if (i == 255) {
					assertEquals(UndergroundColumns.KEEP, c, "a vein never fills the cave");
				} else {
					assertTrue(c == UndergroundColumns.KEEP || c != DeepColumns.STONE && c != DeepColumns.DEEPSLATE, "only changed cells are set: " + c);
					if (DeepColumns.isOre(c)) {
						ores++;
						assertTrue(y <= FLOOR - 5);
					}
				}
			}
		}
		assertTrue(ores > 0, "ores below the cap");
	}
}
