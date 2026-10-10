package dev.gmodcraft.link;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * McWeaponSets (server link @kSvOffMcWeaponSets, protocol v17) on a raw segment: the layout half of
 * the hybrid weapon sync, kept free of Minecraft classes so it can be unit-tested. Each set is its
 * own seqlock; the table count is published with release after the sets.
 */
public final class WeaponSetCodec {
	private static final VarHandle INT = JAVA_INT.varHandle();

	private WeaponSetCodec() {
	}

	/**
	 * One player's set as written. {@code hashes}/{@code clips} hold {@code count} entries (cut to
	 * kMaxWeaponsPerPlayer when written).
	 */
	public record Set(long steamId, int heldHash, int lastRequestId, int flags, int count, int[] hashes, int[] clips) {
		public Set {
			count = Math.max(0, Math.min(count, Math.min(hashes.length, clips.length)));
		}

		/** Equal content (what decides whether a set must be rewritten). */
		public boolean sameAs(@Nullable Set o) {
			if (o == null || o.steamId != this.steamId || o.heldHash != this.heldHash || o.lastRequestId != this.lastRequestId || o.flags != this.flags
				|| o.count != this.count) {
				return false;
			}
			return Arrays.equals(this.hashes, 0, this.count, o.hashes, 0, o.count) && Arrays.equals(this.clips, 0, this.count, o.clips, 0, o.count);
		}
	}

	/** Seqlock write of set {@code index} at {@code base} (the McWeaponSets table's offset). */
	public static void writeSet(MemorySegment s, long base, int index, Set set) {
		if (index < 0 || index >= MAX_PLAYERS) {
			return;
		}
		long r = base + WTS_SETS + (long) index * MC_WEAPON_SET_BYTES;
		int seq = s.get(JAVA_INT, r + WS_SEQ);
		if ((seq & 1) != 0) {
			seq++; // a writer died mid-write (never with one server thread); start from an even value
		}
		INT.setRelease(s, r + WS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int n = Math.min(set.count(), MAX_WEAPONS_PER_PLAYER);
		s.set(JAVA_INT, r + WS_COUNT, n);
		s.set(JAVA_INT, r + WS_HELD_HASH, set.heldHash());
		s.set(JAVA_INT, r + WS_LAST_REQUEST_ID, set.lastRequestId());
		s.set(JAVA_LONG, r + WS_STEAM_ID, set.steamId());
		s.set(JAVA_INT, r + WS_FLAGS, set.flags());
		for (int i = 0; i < MAX_WEAPONS_PER_PLAYER; i++) {
			long e = r + WS_ENTRIES + (long) i * WEAPON_ENTRY_BYTES;
			s.set(JAVA_INT, e + WE_HASH, i < n ? set.hashes()[i] : 0);
			s.set(JAVA_INT, e + WE_CLIP1, i < n ? set.clips()[i] : 0);
		}
		INT.setRelease(s, r + WS_SEQ, seq + 2);
	}

	/** Publishes how many sets are in use (after the sets themselves were written). */
	public static void writeCount(MemorySegment s, long base, int count) {
		INT.setRelease(s, base + WTS_COUNT, Math.max(0, Math.min(count, MAX_PLAYERS)));
	}

	/** Seqlock read of set {@code index} (tests and diagnostics); null on a torn read. */
	public static @Nullable Set readSet(MemorySegment s, long base, int index) {
		long r = base + WTS_SETS + (long) index * MC_WEAPON_SET_BYTES;
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = (int) INT.getAcquire(s, r + WS_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int n = Math.max(0, Math.min(s.get(JAVA_INT, r + WS_COUNT), MAX_WEAPONS_PER_PLAYER));
			int[] h = new int[n], c = new int[n];
			for (int i = 0; i < n; i++) {
				long e = r + WS_ENTRIES + (long) i * WEAPON_ENTRY_BYTES;
				h[i] = s.get(JAVA_INT, e + WE_HASH);
				c[i] = s.get(JAVA_INT, e + WE_CLIP1);
			}
			Set out = new Set(s.get(JAVA_LONG, r + WS_STEAM_ID), s.get(JAVA_INT, r + WS_HELD_HASH), s.get(JAVA_INT, r + WS_LAST_REQUEST_ID),
				s.get(JAVA_INT, r + WS_FLAGS), n, h, c);
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, r + WS_SEQ) == seq1) {
				return out;
			}
		}
		return null;
	}

	public static int readCount(MemorySegment s, long base) {
		return (int) INT.getAcquire(s, base + WTS_COUNT);
	}
}
