package dev.gmodcraft.link;

/**
 * The overlay's v13 pixel format (Proto.OV_BGRA | Proto.OV_SRGB_ENCODED): Minecraft renders RGBA8
 * premultiplied; the host (ToGL) wants BGRA8 with every colour channel through the sRGB encode
 * curve and alpha untouched. Pure functions, so they can be unit-tested away from the client.
 */
public final class OverlayPixels {
	private OverlayPixels() {
	}

	/** channel 0..255 -> sRGB-encoded 0..255 (protocol kOvSrgbEncoded), as a lookup table. */
	private static final byte[] ENCODE = new byte[256];

	static {
		for (int i = 0; i < 256; i++) {
			ENCODE[i] = (byte) srgbEncode(i);
		}
	}

	/** The header's curve: round(255 * f(c / 255)), f(x) = x <= 0.0031308 ? 12.92x : 1.055 x^(1/2.4) - 0.055. */
	public static int srgbEncode(int c) {
		double x = c / 255.0;
		double y = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055;
		return (int) Math.max(0, Math.min(255, Math.round(y * 255.0)));
	}

	/** One RGBA8 pixel (little-endian int: R in the low byte) -> BGRA8 (B in the low byte), colour sRGB-encoded. */
	public static int convert(int rgba) {
		if (rgba == 0) {
			return 0; // fully transparent: most of a HUD
		}
		int r = ENCODE[rgba & 0xFF] & 0xFF;
		int g = ENCODE[(rgba >>> 8) & 0xFF] & 0xFF;
		int b = ENCODE[(rgba >>> 16) & 0xFF] & 0xFF;
		return b | (g << 8) | (r << 16) | (rgba & 0xFF000000);
	}
}
