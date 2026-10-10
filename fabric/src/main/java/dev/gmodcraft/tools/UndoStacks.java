package dev.gmodcraft.tools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Per-player (steamId) undo stacks of at most {@code depth} entries: the oldest drops off. In memory. */
public final class UndoStacks<T> {
	private final int depth;
	private final Map<Long, Deque<T>> stacks = new HashMap<>();

	public UndoStacks(int depth) {
		this.depth = depth;
	}

	public void push(long who, T entry) {
		Deque<T> d = this.stacks.computeIfAbsent(who, k -> new ArrayDeque<>());
		d.push(entry);
		while (d.size() > this.depth) {
			d.removeLast();
		}
	}

	public @Nullable T pop(long who) {
		Deque<T> d = this.stacks.get(who);
		return d == null ? null : d.poll();
	}

	public int size(long who) {
		Deque<T> d = this.stacks.get(who);
		return d == null ? 0 : d.size();
	}

	public void clear() {
		this.stacks.clear();
	}
}
