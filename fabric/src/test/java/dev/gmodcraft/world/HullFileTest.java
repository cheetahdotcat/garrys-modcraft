package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hull file reader (protocol/hull_format.md v1): header checks, runs, bad files, the depth rule. */
class HullFileTest {
	static final long HASH = 0x0123456789abcdefL;
	static final int GRASS = Proto.DIG_GRASS + 1, STONE = Proto.DIG_STONE + 1;

	/** Region (0, 56, 2048): grass on y 63 over stone y 56..62 in x 0..3; region (8, 56, 2048) all stone. */
	static Map<int[], byte[]> regions() {
		byte[] a = new byte[512];
		for (int y = 0; y < 8; y++) {
			for (int z = 0; z < 8; z++) {
				for (int x = 0; x < 4; x++) {
					a[x + 8 * (z + 8 * y)] = (byte) (y == 7 ? GRASS : STONE);
				}
			}
		}
		byte[] b = new byte[512];
		Arrays.fill(b, (byte) STONE);
		Map<int[], byte[]> m = new LinkedHashMap<>();
		m.put(new int[] { 0, 56, 2048 }, a);
		m.put(new int[] { 8, 56, 2048 }, b);
		return m;
	}

	static byte[] good() {
		return HullFile.write(HASH, 0, -1200, 2048, 0, regions());
	}

	@Test
	void readsHeaderAndRuns() throws Exception {
		HullFile f = HullFile.parse(good());
		assertEquals(HASH, f.hash);
		assertTrue(f.offsetsMatch(0, -1200, 2048));
		assertFalse(f.offsetsMatch(0, -1160, 2048), "a re-anchored slot");
		assertEquals(2, f.regions.size());
		assertEquals(4 * 8 * 8 + 512, f.solidCount);
		assertEquals(GRASS, f.code(0, 63, 2048));
		assertEquals(STONE, f.code(3, 56, 2055));
		assertEquals(0, f.code(4, 60, 2048), "air in the first region");
		assertEquals(STONE, f.code(15, 63, 2055));
		assertEquals(0, f.code(16, 60, 2048), "outside every region");
		assertEquals(0, f.code(0, 64, 2048));
		assertEquals(0, f.code(-1, 60, 2048));
	}

	@Test
	void depthRule() throws Exception {
		HullFile f = HullFile.parse(good());
		double[] seen = new double[1];
		HullFile.Underground u = (material, depth) -> {
			seen[0] = depth;
			return material == Proto.DIG_GRASS && depth < 3.5 ? Proto.DIG_DIRT : Proto.DIG_STONE;
		};
		assertEquals(Proto.DIG_GRASS, f.material(0, 63, 2048, u), "the surface keeps its material");
		assertEquals(-1, f.material(0, 64, 2048, u), "air");
		// stone one block under the grass: the rule gets its own material (stone) at depth 1.5
		assertEquals(Proto.DIG_STONE, f.material(0, 62, 2048, u));
		assertEquals(1.5, seen[0]);
		f.material(0, 56, 2048, u);
		assertEquals(HullFile.DEPTH_LOOK + 0.5, seen[0], "the look-up stops at DEPTH_LOOK");
	}

	@Test
	void grassAllTheWayDownTurnsToDirtThenStone() throws Exception {
		byte[] g = new byte[512];
		for (int y = 0; y < 8; y++) {
			g[8 * 8 * y] = (byte) GRASS; // one column x 0 z 0 of grass, y 0..7
		}
		Map<int[], byte[]> m = new LinkedHashMap<>();
		m.put(new int[] { 0, 0, 0 }, g);
		HullFile f = HullFile.parse(HullFile.write(1, 0, 0, 0, 0, m));
		HullFile.Underground u = (material, depth) -> material == Proto.DIG_GRASS && depth < 3.5 ? Proto.DIG_DIRT : Proto.DIG_STONE;
		assertEquals(Proto.DIG_GRASS, f.material(0, 7, 0, u));
		assertEquals(Proto.DIG_DIRT, f.material(0, 6, 0, u));
		assertEquals(Proto.DIG_DIRT, f.material(0, 5, 0, u), "depth 2.5");
		assertEquals(Proto.DIG_STONE, f.material(0, 4, 0, u), "depth 3.5");
	}

	@Test
	void uniformRegionsShareTheirArray() throws Exception {
		HullFile f = HullFile.parse(good());
		HullFile g = HullFile.parse(good());
		assertTrue(f.regions.get(HullFile.regionKey(8, 56, 2048)) == g.regions.get(HullFile.regionKey(8, 56, 2048)));
	}

	@Test
	void names() {
		HullFile.Name n = HullFile.Name.parse("gm_construct.0123456789abcdef.bin");
		assertNotNull(n);
		assertEquals("gm_construct", n.map());
		assertEquals(HASH, n.hash());
		assertEquals("gm_construct.0123456789abcdef.bin", n.fileName());
		assertEquals("gm.x.ffffffffffffffff.bin", HullFile.fileName("gm.x", -1L));
		assertNull(HullFile.Name.parse("gm_construct.0123456789abcdef.bin.tmp"));
		assertNull(HullFile.Name.parse("gm_construct.0123456789ABCDEF.bin"));
		assertNull(HullFile.Name.parse("gm_construct.123.bin"));
	}

	static ByteBuffer le(byte[] b) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
	}

	static void bad(byte[] data, String why) {
		HullFile.BadFile e = assertThrows(HullFile.BadFile.class, () -> HullFile.parse(data), why);
		assertNotNull(e.getMessage());
	}

	@Test
	void badHeaders() {
		bad(new byte[10], "shorter than the header");
		byte[] b = good();
		b[0] = 'X';
		bad(b, "magic");
		b = good();
		le(b).putInt(8, 2);
		bad(b, "version 2");
		b = good();
		le(b).putInt(12, 80);
		bad(b, "header bytes");
		b = good();
		le(b).putInt(36, 3);
		bad(b, "one region more than the file has");
		b = good();
		le(b).putInt(36, 0x7fffffff);
		bad(b, "a huge region count");
		b = good();
		le(b).putInt(40, 7);
		bad(b, "solid count off");
	}

	@Test
	void badRegions() {
		// first region at offset 64: i32 x, y, z, u16 runs
		byte[] b = good();
		le(b).putInt(64, 4);
		bad(b, "x not a multiple of 8");
		b = good();
		le(b).putInt(68, 57);
		bad(b, "y off the grid");
		// second region at the same place as the first
		Map<int[], byte[]> twice = new LinkedHashMap<>();
		byte[] s = new byte[512];
		Arrays.fill(s, (byte) STONE);
		twice.put(new int[] { 0, 0, 0 }, s);
		twice.put(new int[] { 0, 0, 0 }, s);
		bad(HullFile.write(1, 0, 0, 0, 0, twice), "a region twice");
		// a code past the last dig material
		Map<int[], byte[]> m = new LinkedHashMap<>();
		byte[] c = new byte[512];
		c[5] = (byte) (HullFile.MAX_CODE + 1);
		m.put(new int[] { 0, 0, 0 }, c);
		bad(HullFile.write(1, 0, 0, 0, 0, m), "code 23");
		c[5] = (byte) HullFile.MAX_CODE; // kDigBedrock + 1: the last valid code
		m = new LinkedHashMap<>();
		m.put(new int[] { 0, 0, 0 }, c);
		assertEquals(1, assertDoesNotThrow(HullFile.write(1, 0, 0, 0, 0, m)).solidCount);
	}

	static HullFile assertDoesNotThrow(byte[] data) {
		try {
			return HullFile.parse(data);
		} catch (HullFile.BadFile e) {
			throw new AssertionError(e);
		}
	}

	/** One region by hand: runs given as (code, length) pairs. */
	static byte[] oneRegion(int solid, int... runs) {
		ByteBuffer body = ByteBuffer.allocate(14 + runs.length / 2 * 3).order(ByteOrder.LITTLE_ENDIAN);
		body.putInt(0).putInt(0).putInt(0).putShort((short) (runs.length / 2));
		for (int i = 0; i < runs.length; i += 2) {
			body.put((byte) runs[i]).putShort((short) runs[i + 1]);
		}
		ByteBuffer h = ByteBuffer.allocate(64 + body.capacity()).order(ByteOrder.LITTLE_ENDIAN);
		h.put(HullFile.MAGIC).putInt(1).putInt(64).putLong(1).putInt(0).putInt(0).putInt(0).putInt(1).putInt(solid).putInt(0);
		h.position(64);
		h.put(body.array());
		return h.array();
	}

	@Test
	void runLengths() throws Exception {
		assertEquals(200, HullFile.parse(oneRegion(200, STONE, 200, 0, 312)).solidCount);
		assertEquals(200, HullFile.parse(oneRegion(200, STONE, 200, 0, 0, 0, 312)).solidCount, "an empty run is harmless");
		bad(oneRegion(200, STONE, 200, 0, 311), "runs short of 512");
		bad(oneRegion(200, STONE, 200, 0, 313), "runs past 512");
		bad(oneRegion(512, STONE, 0xFFFF), "one run far past 512");
		byte[] cut = oneRegion(200, STONE, 200, 0, 312);
		bad(Arrays.copyOf(cut, cut.length - 1), "file ends in the runs");
		bad(Arrays.copyOf(cut, 70), "file ends in a region header");
		byte[] extra = Arrays.copyOf(cut, cut.length + 3);
		bad(extra, "bytes after the last region");
	}

	@Test
	void emptyFileIsFine() throws Exception {
		HullFile f = HullFile.parse(HullFile.write(5, 1, 2, 3, HullFile.FLAG_STATIC_PROPS, new LinkedHashMap<>()));
		assertEquals(0, f.regions.size());
		assertEquals(HullFile.FLAG_STATIC_PROPS, f.flags);
	}

	@Test
	void slotCells() {
		assertEquals(net.minecraft.world.level.ChunkPos.pack(0, 0), HullFile.slotCell(-1024, 1023));
		assertEquals(net.minecraft.world.level.ChunkPos.pack(-1, 0), HullFile.slotCell(-1025, 0));
		assertEquals(net.minecraft.world.level.ChunkPos.pack(1, -1), HullFile.slotCell(1024, -1025));
	}
}
