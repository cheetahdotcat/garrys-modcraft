package dev.gmodcraft.world;

import static dev.gmodcraft.world.DeepColumns.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** W1: the deep flat column (layers, jagged bedrock, deepslate band) and its ore distribution. */
class DeepColumnsTest {
	static final long SEED = 1234567L;
	static final int FLOOR = 64;
	static final int LO = BEDROCK_Y - 2, HEIGHT = FLOOR + 2 - LO;  // y -66 .. 65

	static int[] chunk(long seed, int cx, int cz) {
		int[] out = new int[HEIGHT * 256];
		fillChunk(seed, FLOOR, cx, cz, null, LO, HEIGHT, out);
		return out;
	}

	static int at(int[] c, int x, int y, int z) {
		return c[(y - LO) * 256 + z * 16 + x];
	}

	@Test
	void columnLayers() {
		for (int x = -40; x < 40; x += 7) {
			for (int z = -40; z < 40; z += 5) {
				assertEquals(AIR, baseAt(SEED, FLOOR, x, 64, z));
				assertEquals(GRASS, baseAt(SEED, FLOOR, x, 63, z));
				assertEquals(DIRT, baseAt(SEED, FLOOR, x, 62, z));
				assertEquals(DIRT, baseAt(SEED, FLOOR, x, 60, z));
				assertEquals(STONE, baseAt(SEED, FLOOR, x, 59, z));
				assertEquals(STONE, baseAt(SEED, FLOOR, x, 8, z));
				assertEquals(DEEPSLATE, baseAt(SEED, FLOOR, x, -1, z));
				assertEquals(DEEPSLATE, baseAt(SEED, FLOOR, x, -59, z));
				assertEquals(BEDROCK, baseAt(SEED, FLOOR, x, -64, z));
				assertEquals(AIR, baseAt(SEED, FLOOR, x, -65, z));
			}
		}
		// a low floor: the top layers win over stone and the bedrock band, y -64 stays bedrock
		assertEquals(GRASS, baseAt(SEED, -60, 0, -61, 0));
		assertEquals(DIRT, baseAt(SEED, -60, 0, -63, 0));
		assertEquals(BEDROCK, baseAt(SEED, -60, 0, -64, 0));
		assertEquals(Integer.MAX_VALUE, bottom(-64));
		assertEquals(BEDROCK_Y, bottom(-63));
	}

	@Test
	void jaggedBedrockAndDeepslateBand() {
		int n = 0;
		int[] bedrock = new int[5];
		int[] deep = new int[8];
		for (int x = 0; x < 64; x++) {
			for (int z = 0; z < 64; z++) {
				n++;
				for (int i = 1; i <= 4; i++) {
					bedrock[i] += baseAt(SEED, FLOOR, x, BEDROCK_Y + i, z) == BEDROCK ? 1 : 0;
				}
				assertFalse(baseAt(SEED, FLOOR, x, BEDROCK_Y + 5, z) == BEDROCK);
				for (int y = 0; y < 8; y++) {
					int b = baseAt(SEED, FLOOR, x, y, z);
					assertTrue(b == STONE || b == DEEPSLATE);
					deep[y] += b == DEEPSLATE ? 1 : 0;
				}
			}
		}
		for (int i = 1; i <= 4; i++) {  // vanilla's falling chance: 0.8, 0.6, 0.4, 0.2
			assertEquals((5 - i) / 5.0, bedrock[i] / (double) n, 0.04, "bedrock at y " + (BEDROCK_Y + i));
		}
		for (int y = 0; y < 8; y++) {
			assertEquals((8 - y) / 8.0, deep[y] / (double) n, 0.04, "deepslate at y " + y);
		}
	}

	@Test
	void deterministicAndSeeded() {
		assertArrayEquals(chunk(SEED, 3, -7), chunk(SEED, 3, -7));
		assertFalse(Arrays.equals(chunk(SEED, 3, -7), chunk(SEED + 1, 3, -7)));
		assertFalse(Arrays.equals(chunk(SEED, 3, -7), chunk(SEED, 4, -7)));
	}

	@Test
	void voidColumnsStayEmpty() {
		boolean[] voids = new boolean[256];
		Arrays.fill(voids, 0, 128, true);
		int[] out = new int[HEIGHT * 256];
		fillChunk(SEED, FLOOR, 0, 0, voids, LO, HEIGHT, out);
		for (int y = LO; y < LO + HEIGHT; y++) {
			for (int i = 0; i < 128; i++) {
				assertEquals(AIR, out[(y - LO) * 256 + i]);
			}
			assertTrue(y < BEDROCK_Y || y >= FLOOR || out[(y - LO) * 256 + 200] != AIR);
		}
	}

	@Test
	void oreDistribution() {
		int chunks = 0;
		long[] count = new long[ORES];
		int[] minY = new int[ORES], maxY = new int[ORES];
		Arrays.fill(minY, Integer.MAX_VALUE);
		Arrays.fill(maxY, Integer.MIN_VALUE);
		long blobs = 0;
		for (int cx = -6; cx < 6; cx++) {
			for (int cz = -6; cz < 6; cz++) {
				chunks++;
				int[] c = chunk(SEED, cx, cz);
				for (int y = LO; y < LO + HEIGHT; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int b = at(c, x, y, z);
							if (y >= FLOOR - 4 || y <= BEDROCK_Y) {  // grass, dirt, the bedrock floor and the air stay as they are
								assertTrue(b == baseAt(SEED, FLOOR, (cx << 4) + x, y, (cz << 4) + z), "untouched at y " + y);
							}
							if (b >= GRAVEL && b <= TUFF) {
								blobs++;
							}
							if (!isOre(b)) {
								continue;
							}
							int o = oreOf(b);
							count[o]++;
							minY[o] = Math.min(minY[o], y);
							maxY[o] = Math.max(maxY[o], y);
							if (y >= DEEPSLATE_TOP) {
								assertFalse(isDeepOre(b), "deepslate ore at y " + y);
							}
							if (y < DEEPSLATE_BOTTOM) {
								assertTrue(isDeepOre(b), "stone ore at y " + y);
							}
						}
					}
				}
			}
		}
		double[] perChunk = new double[ORES];
		for (int o = 0; o < ORES; o++) {
			perChunk[o] = count[o] / (double) chunks;
			System.out.printf("ore %d: %.1f/chunk, y %d .. %d%n", o, perChunk[o], minY[o], maxY[o]);
		}
		System.out.printf("blob stones: %.1f/chunk%n", blobs / (double) chunks);
		// vanilla-ish blocks per chunk (y -64 .. 59): coal most, then iron, copper; diamond a handful
		assertBetween(perChunk[COAL], 20, 80);
		assertBetween(perChunk[IRON], 30, 100);
		assertBetween(perChunk[COPPER], 25, 90);
		assertBetween(perChunk[GOLD], 5, 30);
		assertBetween(perChunk[REDSTONE], 12, 60);
		assertBetween(perChunk[LAPIS], 4, 30);
		assertBetween(perChunk[DIAMOND], 2, 12);
		assertTrue(perChunk[COAL] > perChunk[GOLD] && perChunk[IRON] > perChunk[DIAMOND] && perChunk[GOLD] > perChunk[DIAMOND]);
		// y ranges (+- a vein's radius)
		assertTrue(minY[COAL] >= -2 && maxY[DIAMOND] <= 17 && maxY[GOLD] <= 33 && maxY[REDSTONE] <= 16 && minY[COPPER] >= -18);
		for (int o = 0; o < ORES; o++) {
			assertTrue(minY[o] > BEDROCK_Y - 1 && maxY[o] <= FLOOR - 5, "ore " + o + " inside the stone");
		}
		assertTrue(blobs / (double) chunks > 50);
	}

	static void assertBetween(double v, double lo, double hi) {
		assertTrue(v >= lo && v <= hi, v + " not in [" + lo + ", " + hi + "]");
	}
}
