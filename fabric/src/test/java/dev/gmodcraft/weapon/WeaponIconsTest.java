package dev.gmodcraft.weapon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** kColWeaponIcon (protocol v18): the payload codec and its bounds, and the bounded store. */
class WeaponIconsTest {
	private static final long AT = 24; // not 0, and not 8-aligned: decode must not assume either

	private static MemorySegment payload(Arena a, int hash, int w, int h, int format, int pixelBytes) {
		MemorySegment s = a.allocate(AT + Proto.WEAPON_ICON_HEADER_BYTES + Math.max(0, pixelBytes) + 16, 1);
		s.set(ValueLayout.JAVA_INT_UNALIGNED, AT + Proto.WI_HASH, hash);
		s.set(ValueLayout.JAVA_SHORT_UNALIGNED, AT + Proto.WI_W, (short) w);
		s.set(ValueLayout.JAVA_SHORT_UNALIGNED, AT + Proto.WI_H, (short) h);
		s.set(ValueLayout.JAVA_INT_UNALIGNED, AT + Proto.WI_FORMAT, format);
		for (int i = 0; i < pixelBytes; i++) {
			s.set(ValueLayout.JAVA_BYTE, AT + Proto.WEAPON_ICON_HEADER_BYTES + i, (byte) (i * 7 + 3));
		}
		return s;
	}

	private static WeaponIcons.Icon decode(MemorySegment s, long payloadBytes) {
		return WeaponIcons.decode(s, AT, payloadBytes);
	}

	@Test
	void decodesAFullSizeIcon() {
		try (Arena a = Arena.ofConfined()) {
			int n = 64 * 64 * 4;
			MemorySegment s = payload(a, 0xC4131651, 64, 64, Proto.ICON_RGBA8, n);
			WeaponIcons.Icon icon = decode(s, Proto.WEAPON_ICON_HEADER_BYTES + n);
			assertNotNull(icon);
			assertEquals(0xC4131651, icon.hash());
			assertEquals(64, icon.w());
			assertEquals(64, icon.h());
			assertEquals(n, icon.rgba().length);
			assertEquals((byte) 3, icon.rgba()[0]);
			assertEquals((byte) (100 * 7 + 3), icon.rgba()[100]);
			assertEquals(Proto.WEAPON_ICON_MAX_BYTES, Proto.WEAPON_ICON_HEADER_BYTES + n);
		}
	}

	@Test
	void decodesASmallNonSquareIcon() {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment s = payload(a, 7, 16, 8, Proto.ICON_RGBA8, 16 * 8 * 4);
			WeaponIcons.Icon icon = decode(s, Proto.WEAPON_ICON_HEADER_BYTES + 16 * 8 * 4);
			assertNotNull(icon);
			assertEquals(16, icon.w());
			assertEquals(8, icon.h());
		}
	}

	@Test
	void refusesEverythingOutOfBounds() {
		try (Arena a = Arena.ofConfined()) {
			long hdr = Proto.WEAPON_ICON_HEADER_BYTES;
			assertNull(decode(payload(a, 0, 4, 4, Proto.ICON_RGBA8, 64), hdr + 64), "hash 0");
			assertNull(decode(payload(a, 1, 0, 4, Proto.ICON_RGBA8, 0), hdr), "w 0");
			assertNull(decode(payload(a, 1, 4, 0, Proto.ICON_RGBA8, 0), hdr), "h 0");
			assertNull(decode(payload(a, 1, 65, 1, Proto.ICON_RGBA8, 65 * 4), hdr + 65 * 4), "w 65");
			assertNull(decode(payload(a, 1, 1, 65, Proto.ICON_RGBA8, 65 * 4), hdr + 65 * 4), "h 65");
			assertNull(decode(payload(a, 1, 0xFFFF, 0xFFFF, Proto.ICON_RGBA8, 64), hdr + 64), "huge (u16 wrap)");
			assertNull(decode(payload(a, 1, 4, 4, 2, 64), hdr + 64), "unknown format");
			assertNull(decode(payload(a, 1, 4, 4, Proto.ICON_RGBA8, 64), hdr + 63), "one byte short");
			assertNull(decode(payload(a, 1, 4, 4, Proto.ICON_RGBA8, 64), hdr + 68), "trailing bytes");
			assertNull(decode(payload(a, 1, 4, 4, Proto.ICON_RGBA8, 64), hdr - 1), "shorter than the header");
			assertNull(decode(payload(a, 1, 4, 4, Proto.ICON_RGBA8, 64), Proto.WEAPON_ICON_MAX_BYTES + 8), "over the maximum");
			MemorySegment tiny = payload(a, 1, 4, 4, Proto.ICON_RGBA8, 64);
			assertNull(WeaponIcons.decode(tiny, tiny.byteSize() - 8, hdr + 64), "past the end of the segment");
		}
	}

	@Test
	void repeatsOfOneHashStayOneEntry() {
		WeaponIcons.clear();
		byte[] px = new byte[64 * 64 * 4];
		for (int i = 0; i < 10_000; i++) {
			WeaponIcons.put(new WeaponIcons.Icon(42, 64, 64, px));
		}
		assertEquals(1, WeaponIcons.dirtyCount(), "one pending upload, not 10 000");
		assertEquals(1, WeaponIcons.count());
		assertNotNull(WeaponIcons.pollFresh());
		assertNull(WeaponIcons.pollFresh());
		// a flood of distinct hashes is capped at MAX_ICONS, pending uploads too
		for (int i = 1; i <= 10_000; i++) {
			WeaponIcons.put(new WeaponIcons.Icon(1000 + i, 1, 1, new byte[4]));
		}
		assertTrue(WeaponIcons.count() <= WeaponIcons.MAX_ICONS && WeaponIcons.dirtyCount() <= WeaponIcons.MAX_ICONS);
		assertTrue(WeaponIcons.rejected() >= 10_000 - WeaponIcons.MAX_ICONS);
		WeaponIcons.clear();
		assertEquals(0, WeaponIcons.dirtyCount());
	}

	@Test
	void storeIsBoundedAndReplaces() {
		WeaponIcons.clear();
		byte[] px = new byte[4];
		assertTrue(WeaponIcons.put(new WeaponIcons.Icon(1, 1, 1, px)));
		assertNotNull(WeaponIcons.pollFresh());
		assertTrue(WeaponIcons.put(new WeaponIcons.Icon(1, 1, 1, new byte[] { 9, 9, 9, 9 })), "a class's newer icon replaces it");
		assertArrayEquals(new byte[] { 9, 9, 9, 9 }, WeaponIcons.get(1).rgba());
		for (int i = 2; i <= WeaponIcons.MAX_ICONS; i++) {
			WeaponIcons.put(new WeaponIcons.Icon(i, 1, 1, px));
		}
		assertEquals(WeaponIcons.MAX_ICONS, WeaponIcons.count());
		assertFalse(WeaponIcons.put(new WeaponIcons.Icon(WeaponIcons.MAX_ICONS + 1, 1, 1, px)), "a new class over the cap is refused");
		assertTrue(WeaponIcons.put(new WeaponIcons.Icon(5, 1, 1, px)), "a known class still updates at the cap");
		WeaponIcons.clear();
		assertNull(WeaponIcons.get(1));
		assertNull(WeaponIcons.pollFresh());
	}
}
