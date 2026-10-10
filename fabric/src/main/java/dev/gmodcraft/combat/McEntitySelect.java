package dev.gmodcraft.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which Minecraft entities get a GMod proxy (v27 McEntities, B1): the ones within range of any
 * centre (the Minecraft players GMod players play as), nearest first, at most {@code cap}. Pure:
 * positions in, indices out.
 */
public final class McEntitySelect {
	private McEntitySelect() {
	}

	/**
	 * Indices into {@code points} (x, y, z each) within {@code range} of at least one centre, ordered by
	 * the distance to the nearest centre (ties: by index), at most {@code cap}.
	 */
	public static List<Integer> select(List<double[]> points, List<double[]> centres, double range, int cap) {
		double r2 = range * range;
		List<double[]> hits = new ArrayList<>(); // { index, distSq }
		for (int i = 0; i < points.size(); i++) {
			double[] p = points.get(i);
			double best = Double.MAX_VALUE;
			for (double[] c : centres) {
				double dx = p[0] - c[0], dy = p[1] - c[1], dz = p[2] - c[2];
				best = Math.min(best, dx * dx + dy * dy + dz * dz);
			}
			if (best <= r2) {
				hits.add(new double[] { i, best });
			}
		}
		hits.sort(Comparator.<double[]>comparingDouble(h -> h[1]).thenComparingDouble(h -> h[0]));
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < hits.size() && out.size() < cap; i++) {
			out.add((int) hits.get(i)[0]);
		}
		return out;
	}
}
