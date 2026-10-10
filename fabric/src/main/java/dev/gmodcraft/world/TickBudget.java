package dev.gmodcraft.world;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * Drains a queue of keys within a per-tick time budget (BlockDeltas: dirty sections). No
 * Minecraft types, so the budget logic is unit-tested on its own.
 *
 * <p>Each call sends at least one key (so a backlog always makes progress), then keeps going
 * until the queue is empty, the budget is spent, or {@code send} refuses (ring full: the key stays
 * queued for the next tick). A call overruns the budget by at most the cost of one key. It also
 * measures a backlog from first key to empty: keys, ticks and the slowest tick, for the log.
 */
public final class TickBudget {
	private final long budgetNs;
	private final LongSupplier clock;
	// The current backlog (since the queue was last empty).
	private long backlogKeys;
	private int backlogTicks;
	private long backlogMaxNs;

	/** A finished backlog: how many keys over how many ticks, and the slowest tick. */
	public record Backlog(long keys, int ticks, long maxTickNs) {
	}

	public TickBudget(long budgetNs, LongSupplier clock) {
		this.budgetNs = budgetNs;
		this.clock = clock;
	}

	/** Why a drain stopped. */
	public enum Stop {
		EMPTY, BUDGET, REFUSED
	}

	/**
	 * Sends queued keys (oldest first) until empty, out of time, or refused. {@code done} is
	 * called with the backlog's totals when this call empties a queue that had keys.
	 */
	public Stop drain(LongLinkedOpenHashSet queue, LongPredicate send, java.util.function.Consumer<Backlog> done) {
		if (queue.isEmpty()) {
			return Stop.EMPTY;
		}
		long start = this.clock.getAsLong();
		Stop stop = Stop.EMPTY;
		long sent = 0;
		while (!queue.isEmpty()) {
			if (sent > 0 && this.clock.getAsLong() - start >= this.budgetNs) {
				stop = Stop.BUDGET;
				break;
			}
			long key = queue.firstLong();
			if (!send.test(key)) {
				stop = Stop.REFUSED;
				break;
			}
			queue.removeFirstLong();
			sent++;
		}
		long took = this.clock.getAsLong() - start;
		this.backlogKeys += sent;
		this.backlogTicks++;
		this.backlogMaxNs = Math.max(this.backlogMaxNs, took);
		if (queue.isEmpty()) {
			Backlog b = new Backlog(this.backlogKeys, this.backlogTicks, this.backlogMaxNs);
			reset();
			done.accept(b);
		}
		return stop;
	}

	/** Forget the current backlog's totals (the queue was cleared). */
	public void reset() {
		this.backlogKeys = 0;
		this.backlogTicks = 0;
		this.backlogMaxNs = 0;
	}
}
