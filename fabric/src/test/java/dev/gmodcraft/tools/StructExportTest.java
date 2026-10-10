package dev.gmodcraft.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.gmodcraft.link.Proto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** S2 (v38): the export text (palette + runs) and the corner parser. */
class StructExportTest {
	@Test
	void encodesPaletteAndRuns() {
		// 3x2x1: bottom row stone stone dirt, top row air air stone
		StructExport.Encoded e = StructExport.encode(3, 2, 1, (x, y, z) -> y == 0 ? (x < 2 ? "minecraft:stone" : "minecraft:dirt") : (x == 2 ? "minecraft:stone" : null));
		assertNotNull(e);
		assertEquals("GMCS 1 3 2 1 2\nminecraft:stone\nminecraft:dirt\n1*2 2 0*2 1\n", new String(e.text(), StandardCharsets.UTF_8));
		assertEquals(4, e.blocks());
		assertEquals(2, e.palette());
	}

	@Test
	void orderIsXThenZThenY() {
		// only the cell (1, 0, 1) of a 2x2x2 box: index x + 2z + 4y = 3
		StructExport.Encoded e = StructExport.encode(2, 2, 2, (x, y, z) -> x == 1 && y == 0 && z == 1 ? "minecraft:glass" : null);
		assertNotNull(e);
		assertEquals("GMCS 1 2 2 2 1\nminecraft:glass\n0*3 1 0*4\n", new String(e.text(), StandardCharsets.UTF_8));
	}

	@Test
	void allAirIsOneRun() {
		StructExport.Encoded e = StructExport.encode(32, 32, 32, (x, y, z) -> null);
		assertNotNull(e);
		assertEquals(0, e.blocks());
		assertEquals("GMCS 1 32 32 32 0\n0*32768\n", new String(e.text(), StandardCharsets.UTF_8));
	}

	@Test
	void worstCaseFitsTheLimit() {
		// a checkerboard of two kinds at the largest box: one run per cell
		StructExport.Encoded e = StructExport.encode(Proto.STRUCT_MAX_EDGE, Proto.STRUCT_MAX_EDGE, Proto.STRUCT_MAX_EDGE,
			(x, y, z) -> ((x + y + z) & 1) == 0 ? "minecraft:stone" : "minecraft:oak_planks");
		assertNotNull(e);
		assertEquals(32768, e.blocks());
	}

	@Test
	void tooLongTextIsNull() {
		// every cell its own long state: far over kStructTextMaxBytes
		assertNull(StructExport.encode(32, 32, 32, (x, y, z) -> "minecraft:stone_with_a_very_long_name_" + x + "_" + y + "_" + z));
	}

	@Test
	void cornerParses() {
		assertArrayEquals(new int[] { 1, -2, 300 }, StructExport.corner(" 1 -2   300 "));
		assertNull(StructExport.corner("1 2"));
		assertNull(StructExport.corner("1 2 x"));
		assertNull(StructExport.corner(null));
	}
}
