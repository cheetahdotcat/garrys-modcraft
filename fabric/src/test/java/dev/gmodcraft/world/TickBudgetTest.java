package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickBudgetTest {
	private static final long BUDGET_NS = BlockDeltas.TICK_BUDGET_NS;

	private static LongLinkedOpenHashSet queue(int n) {
		LongLinkedOpenHashSet q = new LongLinkedOpenHashSet();
		for (long i = 0; i < n; i++) {
			q.add(i);
		}
		return q;
	}

	/** A chunk-load storm on a fake clock: every tick stays within budget + one section. */
	@Test
	void stormStaysWithinBudget() {
		long[] now = { 0 };
		long perKeyNs = 37_000; // a section re-read: tens of microseconds
		TickBudget budget = new TickBudget(BUDGET_NS, () -> now[0]);
		LongLinkedOpenHashSet q = queue(20_000); // ~ a 32-chunk view distance worth of sections
		List<TickBudget.Backlog> done = new ArrayList<>();
		List<Long> order = new ArrayList<>();
		int ticks = 0;
		while (!q.isEmpty()) {
			long start = now[0];
			budget.drain(q, key -> {
				order.add(key);
				now[0] += perKeyNs;
				return true;
			}, done::add);
			long took = now[0] - start;
			assertTrue(took <= BUDGET_NS + perKeyNs, "tick " + ticks + " took " + took + " ns");
			ticks++;
		}
		assertEquals(20_000, order.size());
		for (int i = 0; i < order.size(); i++) {
			assertEquals(i, order.get(i), "oldest first, each once");
		}
		assertEquals(1, done.size());
		assertEquals(20_000, done.get(0).keys());
		assertEquals(ticks, done.get(0).ticks());
		assertTrue(done.get(0).maxTickNs() <= BUDGET_NS + perKeyNs);
		// 4 ms of 37 us sections: 109 per tick (the first send is free of the budget check).
		assertEquals((20_000 + 108) / 109, ticks);
	}

	/** A section slower than the whole budget still goes out, one per tick (progress). */
	@Test
	void slowKeysStillProgress() {
		long[] now = { 0 };
		TickBudget budget = new TickBudget(BUDGET_NS, () -> now[0]);
		LongLinkedOpenHashSet q = queue(3);
		for (int tick = 0; tick < 3; tick++) {
			TickBudget.Stop stop = budget.drain(q, key -> {
				now[0] += 10_000_000L;
				return true;
			}, b -> {
			});
			assertEquals(tick < 2 ? TickBudget.Stop.BUDGET : TickBudget.Stop.EMPTY, stop);
			assertEquals(2 - tick, q.size());
		}
	}

	/** A full ring stops the tick and keeps the key queued, at the front. */
	@Test
	void refusedKeyStaysQueued() {
		long[] now = { 0 };
		TickBudget budget = new TickBudget(BUDGET_NS, () -> now[0]);
		LongLinkedOpenHashSet q = queue(5);
		int[] room = { 2 };
		assertEquals(TickBudget.Stop.REFUSED, budget.drain(q, key -> room[0]-- > 0, b -> {
		}));
		assertEquals(3, q.size());
		assertEquals(2L, q.firstLong());
	}

	/** The real clock: a storm of keys costing ~20 us each, timed per tick. */
	@Test
	void realClockStorm() {
		TickBudget budget = new TickBudget(BUDGET_NS, System::nanoTime);
		LongLinkedOpenHashSet q = queue(5_000);
		long slowest = 0;
		int ticks = 0;
		while (!q.isEmpty()) {
			long t0 = System.nanoTime();
			budget.drain(q, key -> {
				long until = System.nanoTime() + 20_000;
				while (System.nanoTime() < until) {
					Thread.onSpinWait();
				}
				return true;
			}, b -> {
			});
			slowest = Math.max(slowest, System.nanoTime() - t0);
			ticks++;
		}
		System.out.printf("TickBudget: 5000 sections of ~20 us over %d ticks, slowest tick %.2f ms (budget %d ms)%n", ticks, slowest / 1e6,
			BUDGET_NS / 1_000_000);
		assertTrue(ticks >= 20, "spread over ticks: " + ticks);
		// A measurement, not the proof (the fake-clock test is): generous for a preempted thread on a busy desktop.
		assertTrue(slowest < BUDGET_NS + 20_000_000L, "slowest tick " + slowest + " ns");
	}
}
