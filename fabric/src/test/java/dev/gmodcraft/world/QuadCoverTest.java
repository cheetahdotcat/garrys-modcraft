package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Z1: Minecraft quads hidden under Skyrim's coplanar, same-facing triangles. */
class QuadCoverTest {
	/** A block's top face at height y over [x0, x0+1] x [z0, z0+1], CCW seen from above (as Minecraft winds it). */
	private static double[] top(double x0, double y, double z0) {
		return new double[] { x0, y, z0, x0, y, z0 + 1, x0 + 1, y, z0 + 1, x0 + 1, y, z0 };
	}

	/** An upward-facing triangle (CCW from above: normal +y). */
	private static SkyTri up(double ax, double az, double bx, double bz, double cx, double cz, double y) {
		float[] v = { (float) ax, (float) y, (float) az, (float) bx, (float) y, (float) bz, (float) cx, (float) y, (float) cz };
		SkyTri t = new SkyTri(v, 0, 0);
		if (t.ny < 0) { // wind it the other way
			v = new float[] { (float) ax, (float) y, (float) az, (float) cx, (float) y, (float) cz, (float) bx, (float) y, (float) bz };
			t = new SkyTri(v, 0, 0);
		}
		return t;
	}

	private static boolean covered(double[] quad, SkyTri... tris) {
		QuadCover c = new QuadCover();
		c.begin(List.of(tris), 0, 0, 0);
		return c.covered(quad);
	}

	@Test
	void quadUnderOneTriangleIsCulled() {
		assertTrue(covered(top(10, 64, 20), up(0, 0, 0, 100, 100, 0, 64)));
	}

	@Test
	void quadStraddlingTwoCoplanarTrianglesIsCulled() {
		// The diagonal x + z = 31 splits the floor right through the quad [10, 11] x [20, 21].
		SkyTri a = up(0, 0, 0, 31, 31, 0, 64);
		SkyTri b = up(31, 0, 0, 31, 31, 31, 64);
		assertFalse(covered(top(10, 64, 20), a), "the first triangle alone covers only part");
		assertFalse(covered(top(10, 64, 20), b), "the second triangle alone covers only part");
		assertTrue(covered(top(10, 64, 20), a, b));
	}

	@Test
	void halfCoveredQuadIsKept() {
		// The floor ends at x = 10.5, halfway across the quad.
		SkyTri a = up(0, 0, 0, 100, 10.5, 0, 64);
		SkyTri b = up(10.5, 0, 0, 100, 10.5, 100, 64);
		assertFalse(covered(top(10, 64, 20), a, b));
	}

	@Test
	void quadOffThePlaneIsKept() {
		double off = 0.6 / 40.0; // 0.6 Source units
		assertFalse(covered(top(10, 64 + off, 20), up(0, 0, 0, 100, 100, 0, 64)));
		assertFalse(covered(top(10, 64 - off, 20), up(0, 0, 0, 100, 100, 0, 64)));
		double near = 0.4 / 40.0; // within the tolerance
		assertTrue(covered(top(10, 64 + near, 20), up(0, 0, 0, 100, 100, 0, 64)));
	}

	@Test
	void oppositeFacingQuadIsKept() {
		double[] q = top(10, 64, 20);
		// The same face wound the other way: a block's bottom face against a floor's top.
		double[] down = { q[9], q[10], q[11], q[6], q[7], q[8], q[3], q[4], q[5], q[0], q[1], q[2] };
		assertFalse(covered(down, up(0, 0, 0, 100, 100, 0, 64)));
	}

	@Test
	void wallQuadUnderAWallIsCulledAndDynamicTrianglesAreIgnored() {
		// A block's west face (normal -x) at x = 5, flush with a map wall facing -x (two triangles).
		double[] west = { 5, 10, 3, 5, 10, 4, 5, 11, 4, 5, 11, 3 };
		float[] a = { 5, 0, 0, 5, 0, 50, 5, 50, 50 }, b = { 5, 0, 0, 5, 50, 50, 5, 50, 0 };
		assertTrue(covered(west, new SkyTri(a, 0, 0), new SkyTri(b, 0, 0)));
		int dyn = dev.gmodcraft.link.Proto.TRI_DYNAMIC;
		assertFalse(covered(west, new SkyTri(a, 0, dyn), new SkyTri(b, 0, dyn)), "props and doors move: never cull against them");
	}
}
