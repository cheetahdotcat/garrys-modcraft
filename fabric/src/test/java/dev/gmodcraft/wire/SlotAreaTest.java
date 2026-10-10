package dev.gmodcraft.wire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlotAreaTest {
	@Test
	void centredOnTheOrigin() {
		// P7 live run: a bridge at (16, -7) in the slot with origin (0, 0) was treated as outside.
		assertTrue(SlotArea.contains(0, 0, 16, -7));
		assertTrue(SlotArea.contains(0, 0, -410, 410));
		assertTrue(SlotArea.contains(0, 0, -1024, -1024));
		assertTrue(SlotArea.contains(0, 0, 1023, 1023));
		assertFalse(SlotArea.contains(0, 0, 1024, 0));
		assertFalse(SlotArea.contains(0, 0, 0, -1025));
	}

	@Test
	void otherSlots() {
		assertTrue(SlotArea.contains(2048, -4096, 2048 - 5, -4096 + 7));
		assertFalse(SlotArea.contains(2048, -4096, 0, -4096)); // the neighbouring slot's centre
		assertFalse(SlotArea.contains(0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
	}
}
