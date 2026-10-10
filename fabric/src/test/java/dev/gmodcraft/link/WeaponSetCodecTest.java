package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** McWeaponSets writer (protocol v17): byte layout, seqlock, truncation, stale tails, count. */
class WeaponSetCodecTest {
	private static final long BASE = 0x40; // not 0: offsets must be relative to the table

	@Test
	void writesTheDocumentedLayout() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment s = arena.allocate(BASE + Proto.MC_WEAPON_SETS_BYTES, 8);
			WeaponSetCodec.Set set = new WeaponSetCodec.Set(76561197960265999L, 0x1234, 7, Proto.WEAPON_SET_DEAD, 2, new int[] { 0xAAAA, 0xBBBB }, new int[] { 12, -1 });
			WeaponSetCodec.writeSet(s, BASE, 3, set);
			WeaponSetCodec.writeCount(s, BASE, 4);
			long r = BASE + Proto.WTS_SETS + 3 * Proto.MC_WEAPON_SET_BYTES;
			assertEquals(2, s.get(ValueLayout.JAVA_INT, r + Proto.WS_SEQ), "seq even and bumped once per write");
			assertEquals(2, s.get(ValueLayout.JAVA_INT, r + Proto.WS_COUNT));
			assertEquals(0x1234, s.get(ValueLayout.JAVA_INT, r + Proto.WS_HELD_HASH));
			assertEquals(7, s.get(ValueLayout.JAVA_INT, r + Proto.WS_LAST_REQUEST_ID));
			assertEquals(76561197960265999L, s.get(ValueLayout.JAVA_LONG, r + Proto.WS_STEAM_ID));
			assertEquals(Proto.WEAPON_SET_DEAD, s.get(ValueLayout.JAVA_INT, r + Proto.WS_FLAGS));
			assertEquals(0xBBBB, s.get(ValueLayout.JAVA_INT, r + Proto.WS_ENTRIES + Proto.WEAPON_ENTRY_BYTES + Proto.WE_HASH));
			assertEquals(-1, s.get(ValueLayout.JAVA_INT, r + Proto.WS_ENTRIES + Proto.WEAPON_ENTRY_BYTES + Proto.WE_CLIP1));
			assertEquals(4, s.get(ValueLayout.JAVA_INT, BASE + Proto.WTS_COUNT));
			assertEquals(0, s.get(ValueLayout.JAVA_INT, BASE + Proto.WTS_SETS + 2 * Proto.MC_WEAPON_SET_BYTES + Proto.WS_SEQ), "other sets untouched");
			WeaponSetCodec.Set back = WeaponSetCodec.readSet(s, BASE, 3);
			assertTrue(set.sameAs(back));
		}
	}

	@Test
	void rewriteClearsTheOldTailAndCutsAtTheLimit() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment s = arena.allocate(BASE + Proto.MC_WEAPON_SETS_BYTES, 8);
			int n = Proto.MAX_WEAPONS_PER_PLAYER + 5;
			int[] h = new int[n], c = new int[n];
			for (int i = 0; i < n; i++) {
				h[i] = 100 + i;
				c[i] = i;
			}
			WeaponSetCodec.writeSet(s, BASE, 0, new WeaponSetCodec.Set(1, 0, 0, 0, n, h, c));
			WeaponSetCodec.Set full = WeaponSetCodec.readSet(s, BASE, 0);
			assertEquals(Proto.MAX_WEAPONS_PER_PLAYER, full.count(), "cut to kMaxWeaponsPerPlayer");
			WeaponSetCodec.writeSet(s, BASE, 0, new WeaponSetCodec.Set(1, 0, 0, 0, 1, new int[] { 5 }, new int[] { 6 }));
			long r = BASE + Proto.WTS_SETS;
			assertEquals(4, s.get(ValueLayout.JAVA_INT, r + Proto.WS_SEQ));
			assertEquals(0, s.get(ValueLayout.JAVA_INT, r + Proto.WS_ENTRIES + 5 * Proto.WEAPON_ENTRY_BYTES + Proto.WE_HASH), "old entries zeroed");
			WeaponSetCodec.Set one = WeaponSetCodec.readSet(s, BASE, 0);
			assertArrayEquals(new int[] { 5 }, one.hashes());
			assertArrayEquals(new int[] { 6 }, one.clips());
		}
	}

	@Test
	void oddSeqLeftByADeadWriterIsRecovered() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment s = arena.allocate(BASE + Proto.MC_WEAPON_SETS_BYTES, 8);
			s.set(ValueLayout.JAVA_INT, BASE + Proto.WTS_SETS + Proto.WS_SEQ, 5);
			WeaponSetCodec.writeSet(s, BASE, 0, new WeaponSetCodec.Set(1, 0, 0, 0, 0, new int[0], new int[0]));
			assertEquals(0, s.get(ValueLayout.JAVA_INT, BASE + Proto.WTS_SETS + Proto.WS_SEQ) & 1, "even after the write");
		}
	}

	@Test
	void sameAsComparesContentOnly() {
		WeaponSetCodec.Set a = new WeaponSetCodec.Set(1, 2, 3, 0, 1, new int[] { 9, 99 }, new int[] { 4, 44 });
		WeaponSetCodec.Set b = new WeaponSetCodec.Set(1, 2, 3, 0, 1, new int[] { 9 }, new int[] { 4 });
		assertTrue(a.sameAs(b), "entries past count don't matter");
		assertFalse(a.sameAs(new WeaponSetCodec.Set(1, 2, 4, 0, 1, new int[] { 9 }, new int[] { 4 })), "lastRequestId");
		assertFalse(a.sameAs(new WeaponSetCodec.Set(1, 2, 3, 0, 1, new int[] { 9 }, new int[] { 5 })), "clip");
		assertFalse(a.sameAs(null));
		// the index-out-of-range guard: nothing written, nothing thrown
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment s = arena.allocate(BASE + Proto.MC_WEAPON_SETS_BYTES, 8);
			WeaponSetCodec.writeSet(s, BASE, Proto.MAX_PLAYERS, a);
			WeaponSetCodec.writeSet(s, BASE, -1, a);
		}
	}
}
