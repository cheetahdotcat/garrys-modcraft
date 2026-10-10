package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P6i: what happens when a GMod prop or door (the dynamic layer) moves onto the local player, and
 * the push-out for the case vanilla + TriCollider don't handle (the body wholly inside a prop).
 */
class PushOutTest {
	private static final double R = 0.3, H = 1.8, STEP = 0.6;

	private static SkyTri tri(int flags, double... v) {
		float[] f = new float[9];
		for (int i = 0; i < 9; i++) {
			f[i] = (float) v[i];
		}
		return new SkyTri(f, 0, flags);
	}

	private static void quad(List<SkyTri> out, int flags, double... p) {
		out.add(tri(flags, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]));
		out.add(tri(flags, p[0], p[1], p[2], p[6], p[7], p[8], p[9], p[10], p[11]));
	}

	/** The six faces of an axis-aligned box. */
	private static void box(List<SkyTri> out, int flags, double x0, double y0, double z0, double x1, double y1, double z1) {
		quad(out, flags, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(out, flags, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
		quad(out, flags, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(out, flags, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
		quad(out, flags, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
		quad(out, flags, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
	}

	/** Solid voxels: the given boxes (x0, y0, z0, x1, y1, z1 each). */
	private static PushOut.BoxTest solids(double[]... boxes) {
		return (x0, y0, z0, x1, y1, z1) -> {
			for (double[] b : boxes) {
				if (x1 > b[0] && x0 < b[3] && y1 > b[1] && y0 < b[4] && z1 > b[2] && z0 < b[5]) {
					return true;
				}
			}
			return false;
		};
	}

	private static final int DYN = Proto.TRI_DYNAMIC;
	private static final PushOut.BoxTest NONE = (x0, y0, z0, x1, y1, z1) -> false;

	@Test
	void thinDoorThroughTheBodyIsPushedAsideByTriCollider() {
		// A door (thin vertical slab at x = 0.1, 2 blocks tall) swung through a standing player at x = 0.
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5); // floor
		box(t, DYN, 0.08, 0, -1, 0.12, 2, 1);
		double[] m = TriCollider.resolve(t, 0, 0, 0, R, H, STEP, true, 0, -0.0784, 0);
		assertNotEquals(0.0, m[0], "the door's face cuts into the cylinder: pushed sideways");
		assertTrue(Math.abs(m[0]) >= 0.17, "out of the door: " + m[0]);
	}

	@Test
	void bodyInsideABigPropIsNotPushedByTriCollider() {
		// What vanilla + the smooth collider do with a 2-block crate set down over the player: no
		// face touches the cylinder, nothing pushes, the player is shut in.
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5);
		box(t, DYN, -1, 0, -1, 1, 2, 1);
		double[] m = TriCollider.resolve(t, 0, 0, 0, R, H, STEP, true, 0, -0.0784, 0);
		assertEquals(0.0, m[0]);
		assertEquals(0.0, m[2]);
		assertTrue(PushOut.trapped(t, solids(new double[] { -1, 0, -1, 1, 2, 1 }), 0, 0, 0, R, H, STEP));
	}

	@Test
	void shutInPlayerGoesToTheNearestFreeSpot() {
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5);
		box(t, DYN, -1, 0, -1, 1, 1.5, 1);
		PushOut.BoxTest solid = solids(new double[] { -1, 0, -1, 1, 1.5, 1 });
		// Off-centre towards +x: out of the +x side is nearest (top of the crate is 1.5 up).
		double[] p = PushOut.find(t, solid, NONE, 0.6, 0, 0, R, H, STEP, PushOut.MAX_DIST);
		assertNotNull(p);
		assertEquals(1.35, p[0], 1e-9, "just clear of the +x face (1 + radius, on the 0.25 grid from 0.6)");
		assertEquals(0.0, p[1], 1e-9);
		assertEquals(0.0, p[2], 1e-9);
		assertFalse(PushOut.trapped(t, solid, p[0], p[1], p[2], R, H, STEP));
	}

	@Test
	void neverThroughAMapWall() {
		// A static wall at x = 1.2 right behind the crate's +x face; the free side is -x.
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5);
		box(t, DYN, -1, 0, -1, 1, 3, 1);
		quad(t, 0, 1.2, 0, -5, 1.2, 0, 5, 1.2, 4, 5, 1.2, 4, -5);
		PushOut.BoxTest solid = solids(new double[] { -1, 0, -1, 1, 3, 1 }, new double[] { 1.2, 0, -5, 1.4, 4, 5 });
		double[] p = PushOut.find(t, solid, NONE, 0.5, 0, 0, R, H, STEP, PushOut.MAX_DIST);
		assertNotNull(p);
		assertTrue(p[0] < 1.2, "stays on this side of the map wall: " + p[0]);
	}

	@Test
	void tooDeepInsideStaysPut() {
		List<SkyTri> t = new ArrayList<>();
		box(t, DYN, -5, 0, -5, 5, 5, 5);
		PushOut.BoxTest solid = solids(new double[] { -5, 0, -5, 5, 5, 5 });
		assertNull(PushOut.find(t, solid, NONE, 0, 0, 0, R, H, STEP, PushOut.MAX_DIST));
	}

	@Test
	void staticGeometryAloneIsNotTrapped() {
		// Inside map solid with no host entity around: not this mechanism's business (the GMod
		// server's inside-solid recovery handles that).
		List<SkyTri> t = new ArrayList<>();
		box(t, 0, -1, 0, -1, 1, 2, 1);
		assertFalse(PushOut.trapped(t, solids(new double[] { -1, 0, -1, 1, 2, 1 }), 0, 0, 0, R, H, STEP));
	}

	@Test
	void standingNextToAPropIsNotTrapped() {
		List<SkyTri> t = new ArrayList<>();
		box(t, DYN, 0.31, 0, -1, 2, 1, 1);
		assertFalse(PushOut.trapped(t, solids(new double[] { 0.25, 0, -1, 2, 1, 1 }), 0, 0, 0, R, H, STEP),
			"a prop flush against the body (its voxels a fraction into the hull) isn't a trap");
	}

	@Test
	void neverThroughAMinecraftBlockWall() {
		// Shut in a crate at x -1..1 (3 tall: no way up); just past its +x face a wall of Minecraft
		// blocks (x 1.2..2.0, z -3..3) with free room beyond it (a parallel tunnel). No map triangles
		// there: only the path test sees the wall. The way out must not go through it.
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5);
		box(t, DYN, -1, 0, -1, 1, 3, 1);
		double[] wall = { 1.2, 0, -3, 2.0, 3, 3 };
		PushOut.BoxTest solid = solids(new double[] { -1, 0, -1, 1, 3, 1 }, wall);
		PushOut.BoxTest blocks = solids(wall);
		double[] past = PushOut.find(t, solid, NONE, 0.9, 0, 0, R, H, STEP, PushOut.MAX_DIST);
		assertNotNull(past);
		assertTrue(past[0] > 2.0, "without the path test the nearest spot is past the wall: " + past[0]);
		double[] p = PushOut.find(t, solid, blocks, 0.9, 0, 0, R, H, STEP, PushOut.MAX_DIST);
		assertNotNull(p);
		assertEquals(0.9, p[0], 1e-9, "out sideways instead, this side of the wall");
		assertEquals(1.5, Math.abs(p[2]), 1e-9);
	}

	@Test
	void aSpotWithSupportIsPreferred() {
		// Shut in a tall crate (x -1..1) standing at the edge of a floor that ends at x = 1 (void
		// beyond). The nearest free spot is just past +x (x 1.4), over the void; the nearest one with
		// floor under it is out the side (z +-1.5).
		List<SkyTri> t = new ArrayList<>();
		quad(t, 0, -5, 0, -5, 1, 0, -5, 1, 0, 5, -5, 0, 5);
		box(t, DYN, -1, 0, -1, 1, 3, 1);
		PushOut.BoxTest solid = solids(new double[] { -1, 0, -1, 1, 3, 1 });
		double[] p = PushOut.find(t, solid, NONE, 0.9, 0, 0, R, H, STEP, PushOut.MAX_DIST);
		assertNotNull(p);
		assertFalse(PushOut.supported(t, solid, 1.4, 0, 0, R), "the nearest spot has nothing under it");
		assertTrue(PushOut.supported(t, solid, p[0], p[1], p[2], R), "lands with support: " + p[0] + ", " + p[1] + ", " + p[2]);
		assertEquals(0.9, p[0], 1e-9);
		assertEquals(1.5, Math.abs(p[2]), 1e-9);
	}
}
