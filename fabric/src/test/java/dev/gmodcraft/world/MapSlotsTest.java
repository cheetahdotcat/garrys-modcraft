package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import dev.gmodcraft.link.Proto;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapSlotsTest {
	/** A slot as a v1 table (or one without "version") reads: no vertical offset, kAnchorLegacy. */
	private static MapSlots.Slot legacy(String map, int worldId, int x, int z) {
		return new MapSlots.Slot(map, worldId, x, z, 0, 0.0F, Proto.ANCHOR_LEGACY, null);
	}

	@Test
	void validTable() {
		List<MapSlots.Slot> slots = MapSlots.parse("{\"version\":1,\"slots\":[{\"map\":\"gm_construct\",\"worldId\":5,\"slotX\":0,\"slotZ\":0},"
			+ "{\"map\":\"gm_flatgrass\",\"worldId\":6,\"slotX\":1,\"slotZ\":0}]}", "test");
		assertEquals(List.of(legacy("gm_construct", 5, 0, 0), legacy("gm_flatgrass", 6, 1, 0)), slots);
	}

	@Test
	void nullEntryIsSkipped() {
		List<MapSlots.Slot> slots = MapSlots.parse("{\"slots\":[null,{\"map\":\"gm_construct\",\"worldId\":5,\"slotX\":-1,\"slotZ\":2}]}", "test");
		assertEquals(List.of(legacy("gm_construct", 5, -1, 2)), slots);
	}

	@Test
	void entryWithoutMapKeepsItsSlot() {
		// A missing "map": no NPE later, and its slot stays taken. An empty object identifies nothing: skipped.
		List<MapSlots.Slot> slots = MapSlots.parse("{\"slots\":[{},{\"worldId\":7,\"slotX\":0,\"slotZ\":0}]}", "test");
		assertEquals(List.of(legacy("", 7, 0, 0)), slots);
		assertArrayEquals(new int[] { -1, -1 }, MapSlots.nextFree(slots));
	}

	@Test
	void emptyOrNullTables() {
		assertTrue(MapSlots.parse("", "test").isEmpty());
		assertTrue(MapSlots.parse("{}", "test").isEmpty());
		assertTrue(MapSlots.parse("{\"slots\":null}", "test").isEmpty());
	}

	@Test
	void malformedJsonThrows() {
		assertThrows(JsonParseException.class, () -> MapSlots.parse("{\"version\":1,\"slots\":[{\"map\":", "test"));
		assertThrows(JsonParseException.class, () -> MapSlots.parse("{\"slots\":[1]}", "test"));
	}

	// ---- v2 (P8 WP1, protocol v21) -------------------------------------------------------------------

	@Test
	void v1EntryReadsAsNoOffset() {
		// A v1 file's slot keeps oyUnits 0 even when the entry has stray v2 fields with a zero source.
		MapSlots.Slot s = MapSlots.parse("{\"version\":1,\"slots\":[{\"map\":\"gm_construct\",\"worldId\":5,\"slotX\":0,\"slotZ\":0}]}", "t").get(0);
		assertEquals(0, s.oyUnits());
		assertEquals(Proto.ANCHOR_LEGACY, s.anchorSrc());
	}

	@Test
	void v2RoundTrip() {
		List<MapSlots.Slot> table = List.of(
			new MapSlots.Slot("gm_construct", 5, 0, 0, 2704, -144.0F, Proto.ANCHOR_CURATED, List.of(-15360.0F, -15360.0F, 15872.0F, 15360.0F)),
			new MapSlots.Slot("gm_flatgrass", 6, 1, 0, 14848, -12288.0F, Proto.ANCHOR_SPAWNS, null),
			legacy("gm_old", 7, -1, 0),
			new MapSlots.Slot("gm_new", 8, 0, 1, 0, 0.0F, Proto.ANCHOR_TIMEOUT, null));
		String json = MapSlots.format(table);
		assertTrue(json.contains("\"version\": 2") && json.contains("\"floorY\": 64"), json);
		assertEquals(table, MapSlots.parse(json, "roundtrip"));
	}

	@Test
	void offsetPutsTheFloorOnY64() {
		// gm_construct's main floor z -144 (measured): oyUnits 2704, and the floor lands on y 64 exactly.
		int oy = MapSlots.offsetFor(-144.0F, -1024.0F, 15359.2F);
		assertEquals(2704, oy);
		assertEquals(64.0, (-144 + oy) / 40.0, 0.0);
		// gm_flatgrass z -12288 (z range -16128 .. 15872): fits the mirror dimension unclamped.
		assertEquals(14848, MapSlots.offsetFor(-12288.0F, -16128.0F, 15872.0F));
		// A fractional floor rounds to whole Source units.
		assertEquals(64 * 40 - 13, MapSlots.offsetFor(12.6F, Float.NaN, Float.NaN));
	}

	@Test
	void offsetIsClampedToTheDimension() {
		// A map reaching z 40000 can't put its floor (z 0) on 64: the top would leave the dimension
		// (y < 1024): oy is clamped to 1024 * 40 - 40000 = 960.
		assertEquals(960, MapSlots.offsetFor(0.0F, -100.0F, 40000.0F));
		// ... and from below.
		assertEquals(-1024 * 40 + 45000, MapSlots.offsetFor(0.0F, -45000.0F, 100.0F));
		// No room at all (taller than the dimension): unclamped.
		assertEquals(2560, MapSlots.offsetFor(0.0F, -50000.0F, 50000.0F));
	}
}
