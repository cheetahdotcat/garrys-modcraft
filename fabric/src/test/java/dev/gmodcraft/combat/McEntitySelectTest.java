package dev.gmodcraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B1: which Minecraft entities get GMod proxies. */
class McEntitySelectTest {
	private static double[] p(double x, double y, double z) {
		return new double[] { x, y, z };
	}

	@Test
	void inRangeOfAnyCentreNearestFirst() {
		List<double[]> pts = List.of(p(10, 0, 0), p(100, 0, 0), p(3, 0, 0), p(205, 0, 0), p(270, 0, 0));
		List<double[]> centres = List.of(p(0, 0, 0), p(200, 0, 0));
		assertEquals(List.of(2, 3, 0), McEntitySelect.select(pts, centres, 64, 256), "near either centre, nearest first; 100 and 270 are out");
	}

	@Test
	void noCentresNothing() {
		assertEquals(List.of(), McEntitySelect.select(List.of(p(0, 0, 0)), List.of(), 64, 256));
	}

	@Test
	void capKeepsTheNearest() {
		List<double[]> pts = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			pts.add(p(300 - i, 0, 0)); // farther first in input order
		}
		List<Integer> out = McEntitySelect.select(pts, List.of(p(0, 0, 0)), 1000, 256);
		assertEquals(256, out.size());
		assertEquals(299, out.get(0), "the nearest (x = 1) first");
		assertEquals(44, out.get(255), "x = 256 is the last kept");
	}

	@Test
	void rangeIsInclusive3d() {
		assertEquals(List.of(0), McEntitySelect.select(List.of(p(0, 64, 0)), List.of(p(0, 0, 0)), 64, 1));
		assertEquals(List.of(), McEntitySelect.select(List.of(p(0, 64.01, 0)), List.of(p(0, 0, 0)), 64, 1));
	}
}
