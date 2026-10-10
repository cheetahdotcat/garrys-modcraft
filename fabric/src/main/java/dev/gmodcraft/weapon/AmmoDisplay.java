package dev.gmodcraft.weapon;

/**
 * What the hotbar shows on the held GMod weapon's slot (H3, from HostState's ammo fields): a
 * durability-style bar for the clip and "clip|reserve" text. No Minecraft classes (unit-tested).
 */
public final class AmmoDisplay {
	/** The bar is the vanilla durability bar's size: 13 px. */
	public static final int BAR_WIDTH = 13;

	private AmmoDisplay() {
	}

	/** "clip|reserve", "clip" without a reserve, "reserve" for clipless weapons, "" without ammo. */
	public static String text(int clip1, int ammo1) {
		if (clip1 >= 0 && ammo1 >= 0) {
			return clip1 + "|" + ammo1;
		}
		if (clip1 >= 0) {
			return Integer.toString(clip1);
		}
		return ammo1 >= 0 ? Integer.toString(ammo1) : "";
	}

	/** Filled bar pixels (0..BAR_WIDTH), or -1 when there is no clip to show. */
	public static int barPixels(int clip1, int maxClip1) {
		if (clip1 < 0 || maxClip1 <= 0) {
			return -1;
		}
		return Math.round(BAR_WIDTH * Math.min(1.0F, clip1 / (float) maxClip1));
	}

	/** Bar colour (ARGB): green when full, through yellow, to red when empty, like durability. */
	public static int barColor(int clip1, int maxClip1) {
		float f = maxClip1 > 0 ? Math.max(0.0F, Math.min(1.0F, clip1 / (float) maxClip1)) : 0.0F;
		int r = Math.round(255 * Math.min(1.0F, 2.0F * (1.0F - f)));
		int g = Math.round(255 * Math.min(1.0F, 2.0F * f));
		return 0xFF000000 | (r << 16) | (g << 8);
	}
}
