package dev.gmodcraft.wire;

import dev.gmodcraft.link.Proto;

/**
 * The area of a map slot. Its origin (ox, oz) is where the GMod map's own (0, 0) lands, so the map
 * lies around it (a Source map spans at most +-16384 units = +-410 blocks): the slot is the
 * kSlotBlocks square CENTRED on the origin, [o - kSlotBlocks/2, o + kSlotBlocks/2).
 */
public final class SlotArea {
	private SlotArea() {
	}

	public static boolean contains(int ox, int oz, int x, int z) {
		int half = Proto.SLOT_BLOCKS / 2;
		long dx = (long) x - ox, dz = (long) z - oz;
		return dx >= -half && dx < half && dz >= -half && dz < half;
	}
}
