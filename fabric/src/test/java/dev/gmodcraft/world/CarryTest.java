package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarryTest {
	private static final double EPS = 1e-9;

	// shared/convert.lua (D-003): Source -> MC, slot origin 0 / oy 0.
	private static double[] toMc(double sx, double sy, double sz) {
		return new double[] { sx / 40.0, sz / 40.0, -sy / 40.0 };
	}

	private static double mcYawRad(double srcYawDeg) {
		return Math.toRadians(((270.0 - srcYawDeg) % 360.0 + 360.0) % 360.0);
	}

	@Test
	void translatesAndTurnsAboutThePivotLikeSource() {
		// A platform at Source (400, 800, 120), yaw 30, moves by (+20, -10, +5) and turns +15 degrees
		// (counter-clockwise seen from above in Source); a rider stands at Source (440, 830, 160).
		double sx = 400, sy = 800, sz = 120, yaw0 = 30, turn = 15;
		double rx = 440, ry = 830, rz = 160;
		// The rider in Source after the motion: turned about the old origin, then moved with it.
		double t = Math.toRadians(turn);
		double ox = rx - sx, oy = ry - sy;
		double ex = sx + 20 + ox * Math.cos(t) - oy * Math.sin(t);
		double ey = sy - 10 + ox * Math.sin(t) + oy * Math.cos(t);
		double ez = rz + 5;
		double[] want = toMc(ex, ey, ez);

		Carry c = new Carry();
		double[] p0 = toMc(sx, sy, sz), p1 = toMc(sx + 20, sy - 10, sz + 5);
		assertNull(c.advance(7, 1, p0[0], p0[1], p0[2], mcYawRad(yaw0), 0, 0, 0, 0), "first tick: nothing to compare with");
		double[] m = c.advance(7, 2, p1[0], p1[1], p1[2], mcYawRad(yaw0 + turn), 0, 0, 0, 0);
		double[] r = toMc(rx, ry, rz);
		double[] got = Carry.carryPoint(r[0], r[1], r[2], m);
		assertArrayEquals(want, got, 1e-9);
		assertEquals(-t, m[6], EPS, "a Source turn is the opposite MC yaw turn");
	}

	@Test
	void yawWrapsAcrossTheSeam() {
		Carry c = new Carry();
		c.advance(3, 1, 0, 0, 0, Math.toRadians(359), 0, 0, 0, 0);
		double[] m = c.advance(3, 2, 0, 0, 0, Math.toRadians(1), 0, 0, 0, 0);
		assertEquals(Math.toRadians(2), m[6], 1e-12);
	}

	@Test
	void ratesStandInForMissingSamplesThenCatchUp() {
		Carry c = new Carry();
		c.advance(5, 1, 10, 64, 10, 0, 0, 0.125, 0, 0);
		// No new sample: the rate moves the platform 0.125 up per tick, for MAX_EXTRAPOLATE ticks.
		double[] m = c.advance(5, 1, 10, 64, 10, 0, 0, 0.125, 0, 0);
		assertEquals(64.125, m[4], EPS);
		// The next sample is where the platform really is: the motion corrects to it (no drift).
		m = c.advance(5, 2, 10, 64.2, 10, 0, 0, 0.125, 0, 0);
		assertEquals(64.125, m[1], EPS);
		assertEquals(64.2, m[4], EPS);
		for (int i = 0; i < Carry.MAX_EXTRAPOLATE; i++) {
			c.advance(5, 2, 10, 64.2, 10, 0, 0, 0.125, 0, 0);
		}
		m = c.advance(5, 2, 10, 64.2, 10, 0, 0, 0.125, 0, 0);
		assertEquals(m[1], m[4], EPS, "GMod stalled: the platform counts as still");
		// Another entity starts over.
		assertNull(c.advance(6, 9, 0, 0, 0, 0, 0, 0, 0, 0));
		assertNull(c.advance(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		assertEquals(0, c.ent());
	}

	@Test
	void pinsFeetToTheTopUnlessJumpingOrFarAway() {
		double top = 70.0;
		assertTrue(Carry.shouldPin(true, 69.92, -0.0784, top), "fell a gravity step into the platform: lifted");
		assertTrue(Carry.shouldPin(true, 70.2, 0, top), "platform went down under the feet: pulled down");
		assertFalse(Carry.shouldPin(true, 70.3, 0.33, top), "jumped: the pin ends");
		assertFalse(Carry.shouldPin(true, 71.0, -0.2, top), "high over the platform: falling, not pinned");
		assertFalse(Carry.shouldPin(true, 69.0, -0.2, top), "deep under the top: not this platform's floor");
		assertFalse(Carry.shouldPin(false, 70.0, 0, top), "top unknown (hysteresis): no pin");
	}

	@Test
	void pinnedRiderOnATurningPlatformKeepsItsSpot() {
		// 90 turns of 4 degrees about a pivot: the rider comes back where it started (no drift).
		Carry c = new Carry();
		double px = 100.5, pz = -20.25, x = 102.0, z = -19.0, yaw = 0;
		c.advance(9, 0, px, 64, pz, yaw, 0, 0, 0, 0);
		for (int i = 1; i <= 90; i++) {
			yaw += Math.toRadians(4);
			double[] m = c.advance(9, i, px, 64, pz, yaw, 0, 0, 0, 0);
			double[] p = Carry.carryPoint(x, 65, z, m);
			x = p[0];
			z = p[2];
		}
		assertEquals(102.0, x, 1e-9);
		assertEquals(-19.0, z, 1e-9);
	}
}
