package dev.gmodcraft.world;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * BlockDeltas' dirty sections, safe to mark from any thread (no Minecraft types, so it is
 * unit-tested on its own). Block changes also arrive off the server thread: world generation
 * places features through WorldGenRegion.setBlock on the Worker-Main threads, which ends in
 * ServerLevel.updatePOIOnBlockStateChange (P6e: a plain LongLinkedOpenHashSet written from there
 * corrupted, and TickBudget.drain died with an index of -1).
 * <p>
 * The ordered set belongs to the owner thread (the server thread, bound every tick): only it reads
 * or writes the set, so a drain never sees it change under it. Marks from other threads go into a
 * concurrent set (deduplicated, so it stays bounded by the distinct sections even when nobody
 * absorbs, e.g. before the first tick binds an owner) that the owner moves into its set
 * ({@link #absorb}) before it drains.
 */
public final class DirtyQueue {
	private final LongLinkedOpenHashSet set = new LongLinkedOpenHashSet();
	private final Set<Long> offThread = ConcurrentHashMap.newKeySet();
	private volatile @Nullable Thread owner;

	/** The thread that owns the set (the server thread). */
	public void bindOwner(Thread thread) {
		this.owner = thread;
	}

	/** Marks a key dirty. Any thread. */
	public void mark(long key) {
		if (Thread.currentThread() == this.owner) {
			this.set.add(key);
		} else {
			this.offThread.add(key);
		}
	}

	/** Owner only: moves the other threads' marks into the set and returns it (oldest first). */
	public LongLinkedOpenHashSet absorb() {
		// A key re-marked between next() and remove() is taken in now anyway (add is idempotent).
		for (Iterator<Long> it = this.offThread.iterator(); it.hasNext(); ) {
			long key = it.next();
			it.remove();
			this.set.add(key);
		}
		return this.set;
	}

	/** Owner only: forgets everything marked so far. */
	public void clear() {
		this.offThread.clear();
		this.set.clear();
	}

	/** How many marks wait for the owner (any thread; for tests and diagnostics). */
	public int pendingOffThread() {
		return this.offThread.size();
	}

	/** Owner only: how many keys are queued (after an {@link #absorb}). */
	public int size() {
		return this.set.size();
	}
}
