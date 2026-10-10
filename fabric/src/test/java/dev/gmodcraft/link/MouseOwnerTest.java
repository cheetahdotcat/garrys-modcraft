package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** I1: Minecraft never touches the system mouse when it was started for GMod or GMod took over. */
class MouseOwnerTest {
	@Test
	void ownershipTable() {
		assertTrue(MouseOwner.mayTouchSystemMouse(false, false), "a plain Minecraft the user plays: its own mouse");
		assertFalse(MouseOwner.mayTouchSystemMouse(true, false), "started hidden for GMod, not linked yet (pre-warm, loading): never");
		assertFalse(MouseOwner.mayTouchSystemMouse(false, true), "GMod connected: never again this session");
		assertFalse(MouseOwner.mayTouchSystemMouse(true, true));
	}

	@Test
	void aGrabFromBeforeTheTakeoverIsUndoneOnce() {
		MouseOwner m = new MouseOwner();
		assertFalse(m.takeStaleGrab(), "never grabbed: nothing to undo");
		m.systemGrab(true);               // a visible Minecraft in its world, before GMod came
		assertTrue(m.takeStaleGrab(), "GMod takes over: the grab is undone");
		assertFalse(m.takeStaleGrab(), "once");
		m.systemGrab(true);
		m.systemGrab(false);              // released normally before the takeover
		assertFalse(m.takeStaleGrab());
	}

	@Test
	void blockedCalls() {
		MouseOwner m = new MouseOwner();
		assertEquals(1, m.blockedOne());
		assertEquals(2, m.blockedOne());
	}
}
