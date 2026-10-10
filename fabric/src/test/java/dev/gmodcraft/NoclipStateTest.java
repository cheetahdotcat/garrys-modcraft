package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** N1 (v22): GMod's noclip flag and the per-player state the MC server keeps from it. */
class NoclipStateTest {
	private static final UUID A = new UUID(1, 1), B = new UUID(2, 2);

	@Test
	void hostPlayerFlagIsRead() {
		assertEquals(0x8, Proto.HOST_PLAYER_NOCLIP);
		ServerLink.HostPlayer on = new ServerLink.HostPlayer(1L, 2, Proto.HOST_PLAYER_ALIVE | Proto.HOST_PLAYER_HAS_MC | Proto.HOST_PLAYER_NOCLIP, null, "x");
		ServerLink.HostPlayer off = new ServerLink.HostPlayer(1L, 2, Proto.HOST_PLAYER_ALIVE | Proto.HOST_PLAYER_HAS_MC, null, "x");
		assertTrue(on.noclip());
		assertFalse(off.noclip());
	}

	@Test
	void onOffTransitionsAreReportedOnce() {
		NoclipState s = new NoclipState();
		assertEquals(NoclipState.Change.NONE, s.update(A, false), "never in noclip: nothing to do");
		assertFalse(s.noFall(A));
		assertEquals(NoclipState.Change.ON, s.update(A, true));
		assertEquals(NoclipState.Change.NONE, s.update(A, true));
		assertTrue(s.active(A));
		assertFalse(s.active(B));
		assertEquals(List.of(A), s.activePlayers());
		assertEquals(NoclipState.Change.OFF, s.update(A, false));
		assertEquals(NoclipState.Change.NONE, s.update(A, false));
		assertFalse(s.active(A));
	}

	@Test
	void noFallDamageUntilLandedAfterNoclip() {
		NoclipState s = new NoclipState();
		s.update(A, true);
		assertTrue(s.noFall(A));
		s.landed(A); // standing while in noclip doesn't end anything
		assertTrue(s.active(A));
		s.update(A, false);
		assertTrue(s.noFall(A), "let go in mid-air: the drop doesn't hurt");
		s.landed(A);
		assertFalse(s.noFall(A), "landed: normal fall damage from here");
	}

	@Test
	void backIntoNoclipDuringTheGrace() {
		NoclipState s = new NoclipState();
		s.update(A, true);
		s.update(A, false);
		assertEquals(NoclipState.Change.ON, s.update(A, true));
		assertTrue(s.active(A));
	}

	@Test
	void forgetAndClear() {
		NoclipState s = new NoclipState();
		s.update(A, true);
		s.update(B, true);
		s.forget(A);
		assertFalse(s.noFall(A));
		assertTrue(s.active(B));
		s.clear();
		assertFalse(s.noFall(B));
	}
}
