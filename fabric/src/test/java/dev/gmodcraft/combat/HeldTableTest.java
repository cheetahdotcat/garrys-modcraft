package dev.gmodcraft.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.combat.HeldTable.HeldRecord;
import dev.gmodcraft.link.Proto;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeldTableTest {
	private static final long MS = 1_000_000L;

	private static HeldRecord rec(int id) {
		return new HeldRecord(id, Proto.HELD_BY_PHYSGUN, 7L, 10.5, 70.0, -3.5, 0.1F, 0.2F, -0.3F, 90.0F, Float.NaN);
	}

	@Test
	void appliesWhatTheHostWrote() {
		HeldTable t = new HeldTable();
		assertEquals(List.of(1, 2), List.copyOf(t.update(1, 2, List.of(rec(1), rec(2)), 0).keySet()));
		assertEquals(List.of(2), List.copyOf(t.update(1, 4, List.of(rec(2)), 50 * MS).keySet()), "1 dropped out: released");
	}

	@Test
	void tornReadKeepsTheLastTable() {
		HeldTable t = new HeldTable();
		t.update(1, 2, List.of(rec(1)), 0);
		assertEquals(List.of(1), List.copyOf(t.update(1, -1, List.of(), 50 * MS).keySet()));
	}

	@Test
	void neverWrittenOrStaleOrNewSessionHoldsNothing() {
		HeldTable t = new HeldTable();
		assertTrue(t.update(1, 0, List.of(rec(1)), 0).isEmpty(), "seq 0: never written");
		t.update(1, 2, List.of(rec(1)), 0);
		assertEquals(1, t.update(1, 2, List.of(rec(1)), (Proto.HELD_STALE_MS - 10) * MS).size(), "same seq, still fresh");
		assertTrue(t.update(1, 2, List.of(rec(1)), (Proto.HELD_STALE_MS + 10) * MS).isEmpty(), "host stopped writing: released");
		assertTrue(t.update(1, -1, List.of(), (Proto.HELD_STALE_MS + 60) * MS).isEmpty(), "torn after stale: still nothing");
		HeldTable u = new HeldTable();
		u.update(1, 2, List.of(rec(1)), 0);
		assertTrue(u.update(2, -1, List.of(), 10 * MS).isEmpty(), "a new link session forgets the old table");
	}

	@Test
	void badRecordsAreIgnored() {
		HeldTable t = new HeldTable();
		List<HeldRecord> bad = List.of(new HeldRecord(0, 1, 0, 0, 0, 0, 0, 0, 0, Float.NaN, Float.NaN), new HeldRecord(5, 0, 0, 0, 0, 0, 0, 0, 0, Float.NaN, Float.NaN),
			new HeldRecord(6, 1, 0, Double.NaN, 0, 0, 0, 0, 0, Float.NaN, Float.NaN), new HeldRecord(7, 1, 0, 0, 0, 0, 0, 50, 0, Float.NaN, Float.NaN), new HeldRecord(8, 1, 0, 4e7, 0, 0, 0, 0, 0, Float.NaN, Float.NaN));
		assertTrue(t.update(1, 2, bad, 0).isEmpty());
	}

	@Test
	void puntImpulseIsClamped() {
		assertArrayEquals(new double[] { 1, 0.5, 0 }, HeldTable.clampImpulse(1, 0.5, 0, 3), 1e-9);
		double[] v = HeldTable.clampImpulse(30, 0, 40, 5);
		assertArrayEquals(new double[] { 3, 0, 4 }, v, 1e-9);
		assertNull(HeldTable.clampImpulse(Double.NaN, 0, 0, 3));
	}

	@Test
	void turnTakesTheShortWay() {
		assertEquals(361.0F, HeldTable.nearestTurn(359.0F, 1.0F), 1e-4F, "359 -> 1 is +2");
		assertEquals(-1.0F, HeldTable.nearestTurn(1.0F, 359.0F), 1e-4F, "1 -> 359 is -2 (lands at -1)");
		assertEquals(720.0F + 90.0F, HeldTable.nearestTurn(720.0F + 80.0F, 90.0F), 1e-3F, "unwrapped yRot stays near");
		assertEquals(-90.0F, HeldTable.nearestTurn(-80.0F, 270.0F), 1e-4F);
		assertTrue(rec(1).hasYaw());
		assertTrue(!rec(1).hasPitch(), "NaN: Minecraft keeps its own");
		HeldTable t = new HeldTable();
		assertEquals(1, t.update(1, 2, List.of(new HeldRecord(3, 1, 0, 0, 64, 0, 0, 0, 0, Float.NaN, Float.NaN)), 0).size(), "no facing is still held");
	}
}
