package dev.gmodcraft.world;

import java.util.SplittableRandom;

/**
 * The deep column of the flat world types (W1; pure, unit-tested): grass block at floorY - 1, three
 * dirt, stone down to y 0, deepslate below (a dithered stone/deepslate band at y 0..7, as vanilla),
 * a jagged bedrock floor at y -64 (always) .. -60, air under it. No caves.
 *
 * <p>Ores and stone blobs are veins: each chunk owns a fixed list of vein origins (vanilla-ish counts,
 * sizes and y ranges, from the world seed and the chunk position); a vein's blocks lie within RADIUS_MAX
 * of its origin, so a chunk is filled from its own and its 8 neighbours' veins and the result at a block
 * never depends on which chunk was generated first. Ores and blobs replace only stone, deepslate and
 * the blob stones; an ore is the deepslate variant where it replaces deepslate or tuff, and always below y 0.
 *
 * <p>Block codes are small ints (no Minecraft classes here); SlotFlatGenerator maps them to states.
 */
public final class DeepColumns {
	public static final int BEDROCK_Y = -64;
	/** Below this y: always deepslate; at or above DEEPSLATE_TOP: always stone; between: dithered. */
	public static final int DEEPSLATE_BOTTOM = 0, DEEPSLATE_TOP = 8;
	/** The jagged bedrock band above BEDROCK_Y (vanilla: y -63 .. -60, falling chance). */
	public static final int BEDROCK_BAND = 5;

	public static final int AIR = 0, BEDROCK = 1, DIRT = 2, GRASS = 3, STONE = 4, DEEPSLATE = 5, GRAVEL = 6, ANDESITE = 7,
		DIORITE = 8, GRANITE = 9, TUFF = 10;
	/** Ore codes: ORE_BASE + 2 * ore + (deepslate variant ? 1 : 0). */
	public static final int ORE_BASE = 11;
	public static final int COAL = 0, IRON = 1, COPPER = 2, GOLD = 3, REDSTONE = 4, LAPIS = 5, DIAMOND = 6, ORES = 7;
	public static final int CODES = ORE_BASE + 2 * ORES;

	/**
	 * A vein kind: count origins per chunk; size blocks (ores: 55 % of size, about what a vanilla vein of
	 * that size leaves) picked in the cube +-radius around the origin,
	 * the origin's y uniform or triangular in [minY, maxY] (vanilla's height providers; origins outside
	 * the column are wasted, as vanilla's are). block: a stone code, or ORE_BASE + 2 * ore.
	 */
	public record Vein(int block, int count, int size, int radius, int minY, int maxY, boolean triangle) {
	}

	/** Vanilla 1.18+ overworld ore / blob placements, roughly (counts per chunk, sizes = vein sizes). */
	public static final Vein[] VEINS = {
		new Vein(GRAVEL, 14, 33, 3, -64, 128, false),
		new Vein(GRANITE, 2, 64, 3, 0, 60, false),
		new Vein(DIORITE, 2, 64, 3, 0, 60, false),
		new Vein(ANDESITE, 2, 64, 3, 0, 60, false),
		new Vein(TUFF, 2, 64, 3, -64, 0, false),
		new Vein(ORE_BASE + 2 * COAL, 20, 17, 2, 0, 192, true),
		new Vein(ORE_BASE + 2 * IRON, 10, 9, 1, -24, 56, true),
		new Vein(ORE_BASE + 2 * IRON, 10, 4, 1, -64, 72, false),
		new Vein(ORE_BASE + 2 * COPPER, 16, 10, 2, -16, 112, true),
		new Vein(ORE_BASE + 2 * GOLD, 4, 9, 1, -64, 32, true),
		new Vein(ORE_BASE + 2 * REDSTONE, 4, 8, 1, -64, 15, false),
		new Vein(ORE_BASE + 2 * REDSTONE, 8, 8, 1, -96, -32, true),
		new Vein(ORE_BASE + 2 * LAPIS, 2, 7, 1, -32, 32, true),
		new Vein(ORE_BASE + 2 * LAPIS, 2, 7, 1, -64, 64, false),
		new Vein(ORE_BASE + 2 * DIAMOND, 7, 4, 1, -144, 16, true),
	};
	public static final int RADIUS_MAX = 3;

	private DeepColumns() {
	}

	public static boolean isOre(int code) {
		return code >= ORE_BASE && code < CODES;
	}

	/** The ore index (COAL ..) of an ore code. */
	public static int oreOf(int code) {
		return (code - ORE_BASE) >> 1;
	}

	public static boolean isDeepOre(int code) {
		return isOre(code) && ((code - ORE_BASE) & 1) == 1;
	}

	/** The lowest y the column of this floor fills (BEDROCK_Y), or Integer.MAX_VALUE when it has none. */
	public static int bottom(int floorY) {
		return floorY - 1 >= BEDROCK_Y ? BEDROCK_Y : Integer.MAX_VALUE;
	}

	/** The column's block at (x, y, z) before veins: air above floorY - 1 and under BEDROCK_Y. */
	public static int baseAt(long seed, int floorY, int x, int y, int z) {
		if (y >= floorY || y < BEDROCK_Y) {
			return AIR;
		}
		if (y == BEDROCK_Y) {
			return BEDROCK;
		}
		int d = floorY - 1 - y;
		if (d == 0) {
			return GRASS;
		}
		if (d <= 3) {
			return DIRT;
		}
		if (y < BEDROCK_Y + BEDROCK_BAND && unit(hash(seed, x, y, z, 1)) < (BEDROCK_Y + BEDROCK_BAND - y) / (double) BEDROCK_BAND) {
			return BEDROCK;
		}
		if (y >= DEEPSLATE_TOP) {
			return STONE;
		}
		if (y < DEEPSLATE_BOTTOM) {
			return DEEPSLATE;
		}
		return unit(hash(seed, x, y, z, 2)) < (DEEPSLATE_TOP - y) / (double) (DEEPSLATE_TOP - DEEPSLATE_BOTTOM) ? DEEPSLATE : STONE;
	}

	/** Can a vein of this kind replace that block? */
	static boolean replaceable(int vein, int at) {
		if (at == STONE || at == DEEPSLATE) {
			return true;
		}
		return isOre(vein) && at >= ANDESITE && at <= TUFF;
	}

	/**
	 * Fills chunk (cx, cz) for y in [lo, lo + height): out[((y - lo) * 16 + z) * 16 + x] gets the block
	 * code (block x/z within the chunk). voidCol[z * 16 + x] (may be null): that column stays air.
	 */
	public static void fillChunk(long seed, int floorY, int cx, int cz, boolean[] voidCol, int lo, int height, int[] out) {
		int bx = cx << 4, bz = cz << 4;
		for (int y = lo; y < lo + height; y++) {
			int row = (y - lo) * 256;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					out[row + z * 16 + x] = voidCol != null && voidCol[z * 16 + x] ? AIR : baseAt(seed, floorY, bx + x, y, bz + z);
				}
			}
		}
		applyVeins(seed, floorY, cx, cz, lo, height, out);
	}

	/**
	 * Lays the veins over chunk (cx, cz)'s codes for y in [lo, lo + height) (out as in fillChunk): they
	 * replace only stone, deepslate (and, ores, the blob stones), never above floorY - 5. W2 runs them
	 * over vanilla terrain (other blocks as a code no vein replaces, e.g. -1).
	 */
	public static void applyVeins(long seed, int floorY, int cx, int cz, int lo, int height, int[] out) {
		int bx = cx << 4, bz = cz << 4;
		int stoneTop = floorY - 5;  // the highest y a vein can touch (under the dirt)
		for (int k = 0; k < VEINS.length; k++) {
			Vein v = VEINS[k];
			for (int sx = cx - 1; sx <= cx + 1; sx++) {
				for (int sz = cz - 1; sz <= cz + 1; sz++) {
					SplittableRandom r = new SplittableRandom(hash(seed, sx, k, sz, 3));
					for (int i = 0; i < v.count(); i++) {
						SplittableRandom vr = r.split();
						int ox = (sx << 4) + vr.nextInt(16), oz = (sz << 4) + vr.nextInt(16);
						int span = v.maxY() - v.minY() + 1;
						int oy = v.triangle() ? v.minY() + (vr.nextInt(span) + vr.nextInt(span)) / 2 : v.minY() + vr.nextInt(span);
						int rad = v.radius();
						if (ox + rad < bx || ox - rad > bx + 15 || oz + rad < bz || oz - rad > bz + 15
							|| oy + rad < Math.max(lo, BEDROCK_Y) || oy - rad > Math.min(lo + height - 1, stoneTop)) {
							continue;
						}
						int picks = isOre(v.block()) ? Math.max(1, (v.size() * 11 + 10) / 20) : v.size();
						for (int b = 0; b < picks; b++) {
							int x = ox + vr.nextInt(2 * rad + 1) - rad - bx;
							int y = oy + vr.nextInt(2 * rad + 1) - rad;
							int z = oz + vr.nextInt(2 * rad + 1) - rad - bz;
							if (x < 0 || x > 15 || z < 0 || z > 15 || y < lo || y >= lo + height) {
								continue;
							}
							int at = (y - lo) * 256 + z * 16 + x;
							int cur = out[at];
							if (!replaceable(v.block(), cur)) {
								continue;
							}
							out[at] = isOre(v.block()) && (cur == DEEPSLATE || cur == TUFF || y < DEEPSLATE_BOTTOM) ? v.block() + 1 : v.block();
						}
					}
				}
			}
		}
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static long hash(long seed, int x, int y, int z, int salt) {
		long h = mix(seed + 0x9E3779B97F4A7C15L * salt);
		h = mix(h ^ (x * 0xC2B2AE3D27D4EB4FL));
		h = mix(h ^ (y * 0x165667B19E3779F9L));
		return mix(h ^ (z * 0x27D4EB2F165667C5L));
	}

	/** A hash's top 53 bits as a double in [0, 1). */
	static double unit(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}
}
