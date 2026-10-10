package dev.gmodcraft.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Splits the host's input events into Minecraft frames so that a key or mouse button pressed in a
 * frame is not also released in that frame: Minecraft drops a press and release that arrive in the
 * same frame (seen with Esc on its pause screen), so the release waits for the next frame, and
 * every later event of that key or button waits behind it to keep their order. Other events
 * (cursor, scroll, text, menu) pass straight through. {@link Event#releaseAll()} drops whatever
 * was held back: everything is released at once anyway.
 *
 * <p>Pure logic (no Minecraft), so it is unit-tested; InputBridge feeds it once per frame.
 */
public final class InputFrames {
	/** One input-ring event (see InputEvent in the protocol header). */
	public record Event(int type, int code, int a, int b, int c) {
		boolean keyLike() {
			return this.type == Proto.IN_KEY || this.type == Proto.IN_MOUSE_BUTTON;
		}

		boolean press() {
			return this.a != 0;
		}

		long key() {
			return ((long) this.type << 32) | (this.code & 0xFFFFFFFFL);
		}

		boolean releaseAll() {
			return this.type == Proto.IN_RELEASE_ALL;
		}
	}

	private final ArrayDeque<Event> deferred = new ArrayDeque<>();
	private final Set<Long> pressedThisFrame = new HashSet<>();
	private final Set<Long> heldBack = new HashSet<>();
	private Consumer<Event> sink = e -> {
	};

	/**
	 * Starts a frame: events held back last frame are offered again first (under the same rule,
	 * so a press among them holds its own release back once more).
	 */
	public void beginFrame(Consumer<Event> sink) {
		this.sink = sink;
		this.pressedThisFrame.clear();
		this.heldBack.clear();
		if (this.deferred.isEmpty()) {
			return;
		}
		List<Event> again = new ArrayList<>(this.deferred);
		this.deferred.clear();
		for (Event e : again) {
			offer(e);
		}
	}

	/** One event read from the ring this frame. */
	public void offer(Event e) {
		if (e.releaseAll()) {
			clear();
			this.sink.accept(e);
			return;
		}
		if (!e.keyLike()) {
			this.sink.accept(e);
			return;
		}
		long k = e.key();
		if (this.heldBack.contains(k) || (!e.press() && this.pressedThisFrame.contains(k))) {
			this.heldBack.add(k);
			this.deferred.add(e);
			return;
		}
		if (e.press()) {
			this.pressedThisFrame.add(k);
		}
		this.sink.accept(e);
	}

	/** Forget everything held back (all keys are being released some other way). */
	public void clear() {
		this.deferred.clear();
		this.heldBack.clear();
	}

	/** Events waiting for the next frame. */
	public int pending() {
		return this.deferred.size();
	}
}
