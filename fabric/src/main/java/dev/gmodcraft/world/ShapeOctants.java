package dev.gmodcraft.world;

import dev.gmodcraft.link.Proto;
import java.util.List;

/**
 * v26 kBlkShapes: a block collision shape's octants (half-block cubes, protocol kBlockShapeBytes). An
 * octant counts when the shape's boxes fill at least half of its volume. Bit dx + 2 dy + 4 dz (d = 0:
 * the low half of that MC axis). 0 means "a full cube": a full shape, or a tall one that fills no
 * octant (fences, panes, doors: kept full cubes, as before v26). Low shapes: up to a quarter block high
 * (carpets, snow layers 1-3, pressure plates) {@link #NONE}: no GMod collision (Minecraft players step
 * over them anyway); up to half a block, a bottom slab. Pure (no MC classes), so tests run without a game.
 */
public final class ShapeOctants {
	public static final int FULL = 0xFF;
	/** No GMod collision at all (the block isn't sent as solid). */
	public static final int NONE = -1;

	private ShapeOctants() {
	}

	/** boxes: { minX, minY, minZ, maxX, maxY, maxZ } in block units (0..1, may reach a little outside). */
	public static int of(List<double[]> boxes) {
		double maxY = 0;
		for (double[] b : boxes) {
			maxY = Math.max(maxY, b[4]);
		}
		if (!boxes.isEmpty() && maxY <= 0.25 + 1e-9) {
			return NONE;
		}
		int bits = 0;
		for (int o = 0; o < 8; o++) {
			double ox = (o & 1) * 0.5, oy = ((o >> 1) & 1) * 0.5, oz = ((o >> 2) & 1) * 0.5;
			double vol = 0;
			for (double[] b : boxes) {
				double dx = Math.min(b[3], ox + 0.5) - Math.max(b[0], ox);
				double dy = Math.min(b[4], oy + 0.5) - Math.max(b[1], oy);
				double dz = Math.min(b[5], oz + 0.5) - Math.max(b[2], oz);
				if (dx > 0 && dy > 0 && dz > 0) {
					vol += dx * dy * dz;
				}
			}
			if (vol >= 0.0625 - 1e-9) { // half of an octant's 0.125
				bits |= 1 << o;
			}
		}
		if (bits == FULL) {
			return 0;
		}
		if (maxY <= 0.5 + 1e-9 && !boxes.isEmpty()) {
			return Proto.SHAPE_BOTTOM_SLAB; // low and thin (a 6/16 daylight sensor, ...): a bottom slab
		}
		return bits;
	}
}
