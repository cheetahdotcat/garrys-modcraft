package dev.gmodcraft.world;

/**
 * The mask of gmodcraft:underground (W2; pure, unit-tested): vanilla overworld terrain is generated
 * first (noise, surface, caves, aquifers, noise ore veins, deepslate), then every column is masked:
 * <ul>
 * <li>y &gt;= floorY: air (the maps stand on floorY);</li>
 * <li>inside a map's footprint (FlatColumns.isVoid): floorY - 4 .. floorY - 1 stone (the cap);</li>
 * <li>outside: the flat_everywhere surface there (grass at floorY - 1, three dirt);</li>
 * <li>below: vanilla, plus W1's ore and stone-blob veins (DeepColumns, never above floorY - 5), since
 * vanilla's ores are biome decoration, which this world type leaves out;</li>
 * <li>bedrock at y -64 always, air under it, whatever the dimension's depth.</li>
 * </ul>
 * Codes are DeepColumns' block codes; KEEP = leave the vanilla block.
 */
public final class UndergroundColumns {
	public static final int KEEP = -1;
	public static final int CAP = 4;

	private UndergroundColumns() {
	}

	/** The masked block at y of a column (footprint: inside a map's footprint), or KEEP. */
	public static int at(int floorY, boolean footprint, int y) {
		if (y < DeepColumns.BEDROCK_Y) {
			return DeepColumns.AIR;
		}
		if (y == DeepColumns.BEDROCK_Y) {
			return DeepColumns.BEDROCK;
		}
		if (y >= floorY) {
			return DeepColumns.AIR;
		}
		int d = floorY - 1 - y;
		if (d >= CAP) {
			return KEEP;
		}
		if (footprint) {
			return DeepColumns.STONE;
		}
		return d == 0 ? DeepColumns.GRASS : DeepColumns.DIRT;
	}

	/**
	 * Masks chunk (cx, cz) for y in [lo, lo + height) (lo &lt;= floorY - 1 expected): codes holds the
	 * vanilla blocks as DeepColumns codes (STONE, DEEPSLATE, the blob stones; anything else KEEP),
	 * indexed ((y - lo) * 16 + z) * 16 + x; footprint[z * 16 + x]. Afterwards codes holds the block to
	 * set, or KEEP where the vanilla block stays.
	 */
	public static void fill(long seed, int floorY, int cx, int cz, boolean[] footprint, int lo, int height, int[] codes) {
		int[] before = codes.clone();
		DeepColumns.applyVeins(seed, floorY, cx, cz, lo, height, codes);
		for (int y = lo; y < lo + height; y++) {
			int row = (y - lo) * 256;
			for (int i = 0; i < 256; i++) {
				int m = at(floorY, footprint[i], y);
				if (m != KEEP) {
					codes[row + i] = m;
				} else if (codes[row + i] == before[row + i]) {
					codes[row + i] = KEEP;  // no vein here: the vanilla block stays
				}
			}
		}
	}
}
