package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** v43 day-night sync: kHostEvSetDayTime's time of day -> the overworld clock's total ticks, and its rate. */
class DayNightTest {
	@Test
	void keepsTheDayCount() {
		assertEquals(3 * 24000L + 6000, DayNight.nearestTotal(3 * 24000L + 5990, 6000));
		assertEquals(3 * 24000L + 5990, DayNight.nearestTotal(3 * 24000L + 6000, 5990));
		assertEquals(6000, DayNight.nearestTotal(1000, 6000));
	}

	@Test
	void crossesMidnightTheShortWay() {
		// 23:50 MC -> 00:10 MC next day: forward over midnight, not back most of a day
		assertEquals(4 * 24000L + 100, DayNight.nearestTotal(3 * 24000L + 23900, 100));
		// just after midnight, a small correction back lands on the previous day
		assertEquals(3 * 24000L + 23950, DayNight.nearestTotal(4 * 24000L + 20, 23950));
		// never below 0
		assertEquals(0, DayNight.nearestTotal(10, 23950));
	}

	@Test
	void timeOfDayIsTakenModuloADay() {
		assertEquals(24000L + 500, DayNight.nearestTotal(24000L + 400, 24500));
		assertEquals(24000L + 23500, DayNight.nearestTotal(24000L + 23400, -500));
	}

	@Test
	void rateIsClamped() {
		assertEquals(0.0F, DayNight.clampRate(Double.NaN));
		assertEquals(0.0F, DayNight.clampRate(-3));
		assertEquals(0.0F, DayNight.clampRate(0));
		assertEquals(0.8333F, DayNight.clampRate(60.0 / 72.0), 1e-4F);
		assertEquals(1000.0F, DayNight.clampRate(1e9));
	}
}
