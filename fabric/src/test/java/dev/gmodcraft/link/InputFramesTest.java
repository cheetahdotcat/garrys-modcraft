package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A press and its release never reach Minecraft in the same frame; the order per key is kept. */
class InputFramesTest {
	private final InputFrames frames = new InputFrames();

	private static InputFrames.Event key(int code, boolean down) {
		return new InputFrames.Event(Proto.IN_KEY, code, down ? 1 : 0, 0, 0);
	}

	private static InputFrames.Event button(int code, boolean down) {
		return new InputFrames.Event(Proto.IN_MOUSE_BUTTON, code, down ? 1 : 0, 0, 0);
	}

	private static final InputFrames.Event CURSOR = new InputFrames.Event(Proto.IN_CURSOR, 0, 10, 20, 0);
	private static final InputFrames.Event RELEASE_ALL = new InputFrames.Event(Proto.IN_RELEASE_ALL, 0, 0, 0, 0);

	private static String name(InputFrames.Event e) {
		return switch (e.type()) {
			case Proto.IN_KEY -> "k" + e.code() + (e.a() != 0 ? "+" : "-");
			case Proto.IN_MOUSE_BUTTON -> "b" + e.code() + (e.a() != 0 ? "+" : "-");
			case Proto.IN_RELEASE_ALL -> "releaseAll";
			case Proto.IN_CURSOR -> "cursor";
			default -> "t" + e.type();
		};
	}

	/** One Minecraft frame: the held-back events, then these from the ring. Returns what was dispatched. */
	private List<String> frame(InputFrames.Event... ring) {
		List<String> got = new ArrayList<>();
		this.frames.beginFrame(e -> got.add(name(e)));
		for (InputFrames.Event e : ring) {
			this.frames.offer(e);
		}
		return got;
	}

	@Test
	void pressAndReleaseInOneFrameAreSplit() {
		assertEquals(List.of("k20+"), frame(key(20, true), key(20, false)));
		assertEquals(1, this.frames.pending());
		assertEquals(List.of("k20-"), frame());
		assertEquals(List.of(), frame());
	}

	@Test
	void pressReleasePressKeepsOrder() {
		assertEquals(List.of("k41+"), frame(key(41, true), key(41, false), key(41, true)));
		// The release, then the second press; that press is new in this frame, so its release waits again.
		assertEquals(List.of("k41-", "k41+", "k26+"), frame(key(26, true), key(41, false)));
		assertEquals(List.of("k41-"), frame());
	}

	@Test
	void keysHeldAcrossFramesAreUntouched() {
		assertEquals(List.of("k26+"), frame(key(26, true)));
		assertEquals(List.of("k26-"), frame(key(26, false)));
	}

	@Test
	void otherKeysAndEventsPassThrough() {
		assertEquals(List.of("k9+"), frame(key(9, true)));
		// k9 was held before this frame: its release goes through; k8 is pressed and released now.
		assertEquals(List.of("k8+", "cursor", "k9-"), frame(key(8, true), key(8, false), CURSOR, key(9, false)));
		assertEquals(List.of("k8-"), frame());
	}

	@Test
	void mouseButtonsToo() {
		assertEquals(List.of("b1+"), frame(button(1, true), button(1, false)));
		assertEquals(List.of("b1-"), frame());
	}

	@Test
	void keyAndButtonWithTheSameCodeAreDifferent() {
		assertEquals(List.of("k1+", "b1-"), frame(key(1, true), button(1, false)));
	}

	@Test
	void releaseAllDropsWhatWasHeldBack() {
		assertEquals(List.of("k20+", "releaseAll"), frame(key(20, true), key(20, false), RELEASE_ALL));
		assertEquals(List.of(), frame());
		assertEquals(List.of("k21+"), frame(key(21, true), key(21, false)));
		this.frames.clear();  // InputBridge.releaseAll() (link down, GMod menu open)
		assertEquals(List.of(), frame());
	}
}
