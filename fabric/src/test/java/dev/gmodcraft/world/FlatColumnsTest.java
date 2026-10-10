package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** P8 WP3: where gmodcraft:slot_flat puts its layers. */
class FlatColumnsTest {
	@Test
	void unallocatedOriginsAreVoidNearby() {
		FlatColumns.Snapshot s = FlatColumns.EMPTY;
		assertTrue(FlatColumns.isVoid(s, 0, 0));
		assertTrue(FlatColumns.isVoid(s, 416, -416));
		assertFalse(FlatColumns.isVoid(s, 417, 0));
		assertFalse(FlatColumns.isVoid(s, 1023, 1023));
		assertTrue(FlatColumns.isVoid(s, 2048 + 100, -2048 - 100));  // the next slot's origin
		assertFalse(FlatColumns.isVoid(s, 1024, 0));  // slot 1's edge: 1024 from its origin
	}

	@Test
	void slotOfMatchesTheSlotArea() {
		assertEquals(0, FlatColumns.slotOf(-1024));
		assertEquals(0, FlatColumns.slotOf(1023));
		assertEquals(1, FlatColumns.slotOf(1024));
		assertEquals(-1, FlatColumns.slotOf(-1025));
	}

	@Test
	void footprintBoxFlipsY() {
		// Source x -4000 .. 8000, y -400 .. 2000 at slot (1, -1): origin (2048, -2048)
		FlatColumns.Box b = FlatColumns.boxFor(1, -1, List.of(-4000.0F, -400.0F, 8000.0F, 2000.0F));
		assertEquals(2048 - 100 - 16, b.minX());
		assertEquals(2048 + 200 + 16, b.maxX());
		assertEquals(-2048 - 50 - 16, b.minZ());  // mc.z = -y / 40 + oz: y max -> z min
		assertEquals(-2048 + 10 + 16, b.maxZ());
		FlatColumns.Box unknown = FlatColumns.boxFor(0, 0, null);
		assertEquals(-416, unknown.minX());
		assertEquals(416, unknown.maxZ());
		assertEquals(unknown, FlatColumns.boxFor(0, 0, List.of(1.0F, 2.0F)));
		assertEquals(unknown, FlatColumns.boxFor(0, 0, List.of(10.0F, 0.0F, -10.0F, 5.0F)));
	}

	@Test
	void allocatedSlotsUseTheirFootprint() {
		MapSlots.Slot small = new MapSlots.Slot("gm_small", 1, 0, 0, 0, 0.0F, 1, List.of(-400.0F, -400.0F, 400.0F, 400.0F));
		MapSlots.Slot legacy = new MapSlots.Slot("gm_old", 2, 1, 0);
		FlatColumns.Snapshot s = FlatColumns.snapshot(List.of(small, legacy));
		assertTrue(FlatColumns.isVoid(s, 0, 0));
		assertTrue(FlatColumns.isVoid(s, 10 + 16, -10 - 16));
		assertFalse(FlatColumns.isVoid(s, 10 + 17, 0));  // flat right next to a small map
		assertTrue(FlatColumns.isVoid(s, 2048 + 416, 0));  // a slot without a footprint: +-416
		assertFalse(FlatColumns.isVoid(s, 2048 + 417, 0));
	}
}
