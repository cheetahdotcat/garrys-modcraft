package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The overlay's kOvBGRA | kOvSrgbEncoded conversion, against values worked out by hand from the header's formula. */
class OverlayPixelsTest {
	@Test
	void encodeCurveMatchesTheHeaderFormula() {
		assertEquals(0, OverlayPixels.srgbEncode(0));
		assertEquals(255, OverlayPixels.srgbEncode(255));
		// 1/255 = 0.00392 > 0.0031308: 1.055 * 0.00392^(1/2.4) - 0.055 = 0.0498 -> 12.7 -> 13
		assertEquals(13, OverlayPixels.srgbEncode(1));
		// 128/255 = 0.50196: 1.055 * 0.7504 - 0.055 = 0.7367 -> 187.9 -> 188
		assertEquals(188, OverlayPixels.srgbEncode(128));
		// 64/255 = 0.25098: 1.055 * 0.5619 - 0.055 = 0.5378 -> 137.1 -> 137
		assertEquals(137, OverlayPixels.srgbEncode(64));
		for (int c = 1; c < 256; c++) {
			assertTrue(OverlayPixels.srgbEncode(c) >= OverlayPixels.srgbEncode(c - 1), "monotonic at " + c);
			assertTrue(OverlayPixels.srgbEncode(c) >= c, "encoding never darkens (" + c + ")");
		}
	}

	@Test
	void convertSwizzlesToBgraAndKeepsAlpha() {
		assertEquals(0, OverlayPixels.convert(0));
		// opaque red, R in the low byte -> B,G,R,A: red lands in byte 2
		assertEquals(0xFFFF0000, OverlayPixels.convert(0xFF0000FF));
		// R 128, G 1, B 0, A 200 -> B 0, G 13, R 188, A 200 (alpha untouched)
		int rgba = 128 | (1 << 8) | (0 << 16) | (200 << 24);
		assertEquals(0 | (13 << 8) | (188 << 16) | (200 << 24), OverlayPixels.convert(rgba));
	}
}
