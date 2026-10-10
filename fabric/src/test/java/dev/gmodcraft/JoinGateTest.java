package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.ServerLink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P6b: the join token check (single use, expiry, constant-time match against the slot's hash). */
class JoinGateTest {
	private static final long S = 1_000_000_000L;
	private static final long STEAM_A = 76561198000000001L;
	private static final long STEAM_B = 76561198000000002L;
	private static final UUID GUEST = UUID.nameUUIDFromBytes("OfflinePlayer:Guest".getBytes(StandardCharsets.UTF_8));

	private static ServerLink.HostPlayer slot(long steamId, String name, String token) {
		return new ServerLink.HostPlayer(steamId, 2, 0x5, null, name, token == null ? 0L : JoinGate.hashOf(token));
	}

	@Test
	void hashIsTheFirstEightBytesOfSha256() throws Exception {
		// What the GMod module and tools/fake_host.py publish: sha256(token)[:8], big-endian.
		byte[] d = MessageDigest.getInstance("SHA-256").digest("mp-test-token-5f3a9c2e7b1d4e60".getBytes(StandardCharsets.UTF_8));
		long want = 0;
		for (int i = 0; i < 8; i++) {
			want = (want << 8) | (d[i] & 0xFF);
		}
		assertEquals(want, JoinGate.hashOf("mp-test-token-5f3a9c2e7b1d4e60"));
		assertEquals(0xba7816bf8f01cfeaL, JoinGate.hashOf("abc")); // FIPS 180-4 vector
	}

	@Test
	void theRightTokenMapsOnceAndOnlyOnce() {
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "tok-a");
		g.observe(List.of(a), 0);
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a), "tok-a", 1 * S));
		assertEquals(STEAM_A, g.verifiedSteamId(GUEST));
		// the same token again (a second connection replaying it): refused
		UUID other = UUID.randomUUID();
		assertEquals(JoinGate.Result.REUSED, g.verify(other, List.of(a), "tok-a", 2 * S));
		assertEquals(0L, g.verifiedSteamId(other));
	}

	@Test
	void wrongMissingAndUnissuedTokensAreRefused() {
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "tok-a");
		g.observe(List.of(a), 0);
		assertEquals(JoinGate.Result.WRONG, g.verify(GUEST, List.of(a), "tok-b", S));
		assertEquals(JoinGate.Result.WRONG, g.verify(GUEST, List.of(a), "", S));
		assertEquals(JoinGate.Result.NO_TOKEN, g.verify(GUEST, List.of(), "tok-a", S));
		assertEquals(JoinGate.Result.NO_TOKEN, g.verify(GUEST, List.of(slot(STEAM_A, "Guest", null)), "tok-a", S));
		assertFalse(g.isVerified(GUEST));
		// the right one still works afterwards (wrong tries don't burn it)
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a), "tok-a", 2 * S));
	}

	@Test
	void expiryCountsFromWhenTheHashFirstAppeared() {
		JoinGate g = new JoinGate(20);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "tok-a");
		g.observe(List.of(a), 0);
		g.observe(List.of(a), 15 * S); // re-reads don't restart the clock
		assertEquals(JoinGate.Result.EXPIRED, g.verify(GUEST, List.of(a), "tok-a", 21 * S));
		// a fresh token (new hash) gets its own clock
		ServerLink.HostPlayer a2 = slot(STEAM_A, "Guest", "tok-a2");
		g.observe(List.of(a2), 22 * S);
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a2), "tok-a2", 30 * S));
	}

	@Test
	void nameSpoofWithoutTheVictimsTokenGetsNothing() {
		// Offline mode: "Guest" claims the victim's slot by name but only knows its own token.
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer victim = slot(STEAM_B, "Guest", "victim-token");
		g.observe(List.of(victim), 0);
		assertEquals(JoinGate.Result.WRONG, g.verify(GUEST, List.of(victim), "attacker-token", S));
		assertEquals(0L, g.verifiedSteamId(GUEST));
	}

	@Test
	void leavingTheSlotOrRotatingTheTokenForgets() {
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "tok-a");
		g.observe(List.of(a), 0);
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a), "tok-a", S));
		// the GMod server rotates the token: the old one can't come back (no slot holds its hash)
		ServerLink.HostPlayer rotated = slot(STEAM_A, "Guest", "tok-new");
		g.observe(List.of(rotated), 2 * S);
		assertTrue(g.isVerified(GUEST));
		assertEquals(JoinGate.Result.WRONG, g.verify(UUID.randomUUID(), List.of(rotated), "tok-a", 3 * S));
		// the GMod player leaves: an orphan once the grace period is over, then forgotten
		g.observe(List.of(), 4 * S);
		assertTrue(g.isVerified(GUEST));
		assertTrue(g.orphans(4 * S + 60 * S).isEmpty());
		assertEquals(List.of(GUEST), g.orphans(4 * S + 61 * S));
		g.forget(GUEST);
		assertEquals(0L, g.verifiedSteamId(GUEST));
		assertTrue(g.orphans(100 * S).isEmpty());
	}

	@Test
	void theTokenPicksTheSlotNotTheClaimedIdentity() {
		// An attacker's GMod slot claims the guest's Minecraft UUID and name and comes last (the old
		// lookup by identity, last slot wins, would have checked the guest's token against it).
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer guest = slot(STEAM_A, "Guest", "guest-token");
		ServerLink.HostPlayer attacker = slot(STEAM_B, "Guest", "attacker-token");
		g.observe(List.of(guest, attacker), 0);
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(guest, attacker), "guest-token", S));
		assertEquals(STEAM_A, g.verifiedSteamId(GUEST));
		// a second Minecraft player with the attacker's own token plays as the attacker, nobody else
		UUID other = UUID.randomUUID();
		assertEquals(JoinGate.Result.OK, g.verify(other, List.of(guest, attacker), "attacker-token", S));
		assertEquals(STEAM_B, g.verifiedSteamId(other));
	}

	@Test
	void aHashInTwoSlotsIsRefused() {
		JoinGate g = new JoinGate(120);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "dup");
		ServerLink.HostPlayer b = slot(STEAM_B, "Other", "dup");
		g.observe(List.of(a, b), 0);
		assertEquals(JoinGate.Result.AMBIGUOUS, g.verify(GUEST, List.of(a, b), "dup", S));
		assertFalse(g.isVerified(GUEST));
		// not used up: once only one slot carries it, it verifies
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a, slot(STEAM_B, "Other", "else")), "dup", 2 * S));
	}

	@Test
	void anOrphanThatComesBackInTimeStays() {
		JoinGate g = new JoinGate(120, 10);
		ServerLink.HostPlayer a = slot(STEAM_A, "Guest", "tok-a");
		g.observe(List.of(a), 0);
		assertEquals(JoinGate.Result.OK, g.verify(GUEST, List.of(a), "tok-a", S));
		g.observe(List.of(), 2 * S);  // a changelevel: HostPlayers rewritten a few seconds later
		g.observe(List.of(slot(STEAM_A, "Guest", "tok-new")), 5 * S);
		assertTrue(g.orphans(30 * S).isEmpty());
		assertEquals(STEAM_A, g.verifiedSteamId(GUEST));
		g.observe(List.of(), 31 * S);  // gone for good
		assertTrue(g.orphans(40 * S).isEmpty());
		assertEquals(List.of(GUEST), g.orphans(42 * S));
	}

	@Test
	void aClaimSeveralSlotsMakeGoesToTheListenHostOnly() {
		ServerLink.HostPlayer first = new ServerLink.HostPlayer(STEAM_A, 1, 0x5, GUEST, "Guest", 0L);   // joined first / the listen host
		ServerLink.HostPlayer victim = new ServerLink.HostPlayer(STEAM_B, 2, 0x5, GUEST, "Guest", 0L);
		assertEquals(victim, JoinGate.resolveClaim(List.of(victim), false));
		// dedicated (online mode): entity 1 is whoever joined first, so it gets nothing, nor does anyone
		assertEquals(null, JoinGate.resolveClaim(List.of(first, victim), false));
		assertEquals(null, JoinGate.resolveClaim(List.of(victim, first), false));
		// listen server: entity 1 is the host
		assertEquals(first, JoinGate.resolveClaim(List.of(victim, first), true));
		assertEquals(null, JoinGate.resolveClaim(List.of(victim, slot(STEAM_A, "Guest", null)), true)); // ent 2 twice: nobody
		assertEquals(null, JoinGate.resolveClaim(null, true));
	}

	@Test
	void ttlIsClamped() {
		assertEquals(5, new JoinGate(5).ttlSeconds());
		assertTrue(JoinGate.configuredTtlSeconds() >= 5 && JoinGate.configuredTtlSeconds() <= 3600);
		assertTrue(JoinGate.configuredOrphanGraceSeconds() >= 5 && JoinGate.configuredOrphanGraceSeconds() <= 3600);
		assertEquals(60, new JoinGate(120).orphanGraceSeconds());
	}
}
