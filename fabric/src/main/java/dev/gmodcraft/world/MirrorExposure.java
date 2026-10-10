package dev.gmodcraft.world;

import org.jspecify.annotations.Nullable;

/**
 * Hull world: the mirror blocks of a section that border a dug cell. GMod has the map's own geometry
 * for mirror blocks, except in the space the hull fill puts them in (under terrain, inside the map's
 * solid) where the map has no collision; there a hole's floor and walls exist only in Minecraft.
 * BlockDeltas sends these mirror blocks to GMod as solid cubes, and BlockCollisionsMixin gives them a
 * full collision for Minecraft's entities where the host's geometry has none.
 *
 * <p>Dug bits per section are the SkyDig layout: bit x + 16 z + 256 y, 64 longs, null for none.
 */
public final class MirrorExposure {
	private MirrorExposure() {
	}

	/** Neighbour order in the array passed around: the section itself, then -x, +x, -y, +y, -z, +z. */
	public static final int SELF = 0, WEST = 1, EAST = 2, DOWN = 3, UP = 4, NORTH = 5, SOUTH = 6;

	/** Any dug cell in the section or a face-neighbour section (else nothing in it can border one). */
	public static boolean any(@Nullable long[][] near) {
		if (near == null) {
			return false;
		}
		for (long[] bits : near) {
			if (bits != null) {
				for (long w : bits) {
					if (w != 0L) {
						return true;
					}
				}
			}
		}
		return false;
	}

	static boolean dug(@Nullable long[] bits, int x, int y, int z) {
		if (bits == null) {
			return false;
		}
		int bit = x + 16 * z + 256 * y;
		return ((bits[bit >> 6] >>> (bit & 63)) & 1L) != 0;
	}

	/** Does section-local cell (x, y, z) have a dug face neighbour (across section borders too)? */
	public static boolean exposed(long[][] near, int x, int y, int z) {
		long[] self = near[SELF];
		return (x > 0 ? dug(self, x - 1, y, z) : dug(near[WEST], 15, y, z))
			|| (x < 15 ? dug(self, x + 1, y, z) : dug(near[EAST], 0, y, z))
			|| (y > 0 ? dug(self, x, y - 1, z) : dug(near[DOWN], x, 15, z))
			|| (y < 15 ? dug(self, x, y + 1, z) : dug(near[UP], x, 0, z))
			|| (z > 0 ? dug(self, x, y, z - 1) : dug(near[NORTH], x, y, 15))
			|| (z < 15 ? dug(self, x, y, z + 1) : dug(near[SOUTH], x, y, 0));
	}
}
