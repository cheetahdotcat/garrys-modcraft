package dev.gmodcraft.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AmmoDisplayTest {
	@Test
	void text() {
		assertEquals("12|90", AmmoDisplay.text(12, 90));
		assertEquals("0|0", AmmoDisplay.text(0, 0));
		assertEquals("5", AmmoDisplay.text(5, -1));
		assertEquals("3", AmmoDisplay.text(-1, 3), "clipless (RPG, grenades): the reserve");
		assertEquals("", AmmoDisplay.text(-1, -1), "no ammo at all (physgun, toolgun)");
	}

	@Test
	void bar() {
		assertEquals(13, AmmoDisplay.barPixels(18, 18));
		assertEquals(0, AmmoDisplay.barPixels(0, 18));
		assertEquals(7, AmmoDisplay.barPixels(9, 18));
		assertEquals(13, AmmoDisplay.barPixels(30, 18), "an overfilled clip stays full");
		assertEquals(-1, AmmoDisplay.barPixels(-1, 18));
		assertEquals(-1, AmmoDisplay.barPixels(5, 0));
		assertEquals(0xFF00FF00, AmmoDisplay.barColor(18, 18));
		assertEquals(0xFFFF0000, AmmoDisplay.barColor(0, 18));
		assertEquals(0xFFFFFF00, AmmoDisplay.barColor(9, 18));
	}
}
