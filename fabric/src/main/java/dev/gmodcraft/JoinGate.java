package dev.gmodcraft;

import dev.gmodcraft.link.ServerLink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * P6b pairing check (no Minecraft classes, so it is unit-tested on its own). The GMod server gives
 * each player a random single-use join token (by GMod net message, to that client only) and
 * publishes the first 8 bytes of SHA-256(token) in the player's HostPlayers slot. A Minecraft
 * player's client hands the token over after joining (SkyNet.JoinToken). When the server doesn't
 * check Microsoft accounts ({@code !usesAuthentication()}), names and offline UUIDs are free to
 * choose, so the player is mapped to a SteamID only through {@link #verify}: the token itself picks
 * the slot (the one slot whose hash matches, compared in constant time; a hash two slots carry is
 * refused), the token wasn't used before, and it isn't older than the TTL (counted from when its
 * hash first appeared in HostPlayers). The Minecraft identity a slot claims plays no part: token ->
 * slot -> SteamID.
 * <p>
 * Used tokens are remembered only while some HostPlayers slot still carries their hash (a hash no
 * slot holds can't verify anyway). That is safe because the GMod side never re-publishes an old
 * hash: every JoinInfo gets a fresh random token (server/mp.lua). A verified player whose SteamID
 * leaves HostPlayers (the GMod player quit) is an orphan: {@link #orphans} names it once the grace
 * period is over (a changelevel rewrites HostPlayers within seconds, well inside it), and the
 * caller kicks it. Server thread only.
 */
public final class JoinGate {
	/** How {@link #verify} ended. */
	public enum Result {
		OK("verified"),
		NO_TOKEN("no join token is issued on the GMod server right now"),
		WRONG("the join token doesn't match"),
		AMBIGUOUS("the join token doesn't match exactly one GMod player"),
		REUSED("the join token was already used"),
		EXPIRED("the join token expired");

		public final String why;

		Result(String why) {
			this.why = why;
		}
	}

	private final long ttlNs;
	private final long graceNs;
	// hash -> System.nanoTime() it was first seen in HostPlayers
	private final Map<Long, Long> firstSeen = new HashMap<>();
	// hashes whose token was used
	private final Set<Long> consumed = new HashSet<>();
	// Minecraft UUID -> SteamID64 of verified players (until they disconnect or leave their slot)
	private final Map<UUID, Long> verified = new HashMap<>();
	// verified players whose SteamID is in no HostPlayers slot -> System.nanoTime() it went missing
	private final Map<UUID, Long> orphanedSince = new HashMap<>();

	public JoinGate(long ttlSeconds) {
		this(ttlSeconds, 60);
	}

	public JoinGate(long ttlSeconds, long orphanGraceSeconds) {
		this.ttlNs = Math.max(1L, ttlSeconds) * 1_000_000_000L;
		this.graceNs = Math.max(1L, orphanGraceSeconds) * 1_000_000_000L;
	}

	/**
	 * The join token TTL in seconds: -Dgmodcraft.joinTokenTtl, else GMODCRAFT_JOIN_TOKEN_TTL, else
	 * 120 (clamped to 5..3600; tests use a short one).
	 */
	public static long configuredTtlSeconds() {
		return configured("gmodcraft.joinTokenTtl", "GMODCRAFT_JOIN_TOKEN_TTL", 120);
	}

	/**
	 * How long a verified player may stay after its SteamID left HostPlayers, in seconds:
	 * -Dgmodcraft.orphanGrace, else GMODCRAFT_ORPHAN_GRACE, else 60 (clamped to 5..3600).
	 */
	public static long configuredOrphanGraceSeconds() {
		return configured("gmodcraft.orphanGrace", "GMODCRAFT_ORPHAN_GRACE", 60);
	}

	private static long configured(String property, String env, long fallback) {
		String v = System.getProperty(property);
		if (v == null || v.isBlank()) {
			v = System.getenv(env);
		}
		long value = fallback;
		if (v != null && !v.isBlank()) {
			try {
				value = Long.parseLong(v.trim());
			} catch (NumberFormatException e) {
				value = fallback;
			}
		}
		return Math.max(5, Math.min(3600, value));
	}

	public long ttlSeconds() {
		return this.ttlNs / 1_000_000_000L;
	}

	public long orphanGraceSeconds() {
		return this.graceNs / 1_000_000_000L;
	}

	/** The first 8 bytes of SHA-256(token as UTF-8), big-endian, as HostPlayer.tokenHash holds them. */
	public static long hashOf(String token) {
		byte[] d = sha256(token);
		long v = 0;
		for (int i = 0; i < ServerLink.TOKEN_HASH_BYTES; i++) {
			v = (v << 8) | (d[i] & 0xFFL);
		}
		return v;
	}

	private static byte[] sha256(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] bytes(long v) {
		byte[] b = new byte[ServerLink.TOKEN_HASH_BYTES];
		for (int i = b.length - 1; i >= 0; i--) {
			b[i] = (byte) v;
			v >>>= 8;
		}
		return b;
	}

	/**
	 * HostPlayers was (re)read: note when each token hash first appeared, drop what no slot holds any
	 * more (a hash gone from every slot can't verify again, so its bookkeeping can go), and start (or
	 * stop) the orphan clock of verified players whose SteamID left (or came back to) HostPlayers.
	 */
	public void observe(List<ServerLink.HostPlayer> slots, long nowNs) {
		Set<Long> live = new HashSet<>();
		Set<Long> steamIds = new HashSet<>();
		for (ServerLink.HostPlayer p : slots) {
			if (p.steamId() == 0) {
				continue;
			}
			steamIds.add(p.steamId());
			if (p.tokenHash() != 0) {
				live.add(p.tokenHash());
				this.firstSeen.putIfAbsent(p.tokenHash(), nowNs);
			}
		}
		this.firstSeen.keySet().retainAll(live);
		this.consumed.retainAll(live);
		for (Map.Entry<UUID, Long> e : this.verified.entrySet()) {
			if (steamIds.contains(e.getValue())) {
				this.orphanedSince.remove(e.getKey());
			} else {
				this.orphanedSince.putIfAbsent(e.getKey(), nowNs);
			}
		}
	}

	/**
	 * Verified players whose SteamID has been missing from HostPlayers for longer than the grace
	 * period (their GMod player quit): the caller kicks them, and {@link #forget}s them.
	 */
	public List<UUID> orphans(long nowNs) {
		List<UUID> out = new java.util.ArrayList<>();
		for (Map.Entry<UUID, Long> e : this.orphanedSince.entrySet()) {
			if (nowNs - e.getValue() > this.graceNs) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/**
	 * Checks the token player {@code uuid} presented against every HostPlayers slot: the one slot
	 * whose hash matches is the player's (each slot compared in constant time, no early exit; a hash
	 * that two or more slots carry is refused). On OK the token is used up and the player is mapped
	 * to that slot's SteamID until {@link #forget}. What identity a slot claims doesn't matter.
	 */
	public Result verify(UUID uuid, List<ServerLink.HostPlayer> slots, String token, long nowNs) {
		byte[] presented = java.util.Arrays.copyOf(sha256(token), ServerLink.TOKEN_HASH_BYTES);
		ServerLink.@Nullable HostPlayer match = null;
		int matches = 0;
		boolean anyIssued = false;
		for (ServerLink.HostPlayer p : slots) {
			if (p.steamId() == 0 || p.tokenHash() == 0) {
				continue;
			}
			anyIssued = true;
			if (MessageDigest.isEqual(presented, bytes(p.tokenHash()))) {
				matches++;
				match = p;
			}
		}
		if (!anyIssued) {
			return Result.NO_TOKEN;
		}
		if (token.isEmpty() || match == null) {
			return Result.WRONG;
		}
		if (matches > 1) {
			return Result.AMBIGUOUS;
		}
		long hash = match.tokenHash();
		if (this.consumed.contains(hash)) {
			return Result.REUSED;
		}
		Long seen = this.firstSeen.putIfAbsent(hash, nowNs);
		if (seen != null && nowNs - seen > this.ttlNs) {
			return Result.EXPIRED;
		}
		this.consumed.add(hash);
		this.verified.put(uuid, match.steamId());
		this.orphanedSince.remove(uuid);
		return Result.OK;
	}

	/**
	 * The slot an identity claim (unchecked: whatever the GMod clients reported) points at. One slot:
	 * that one. Several: a spoof by all but one of them. On a listen server (Minecraft's integrated
	 * server, whose owner is the GMod host, always entity index 1) the host's slot keeps the claim;
	 * anywhere else nobody does, since there entity 1 is just whoever joined first. Never "the last
	 * slot wins".
	 */
	public static ServerLink.@Nullable HostPlayer resolveClaim(@Nullable List<ServerLink.HostPlayer> claims, boolean listenServer) {
		if (claims == null || claims.isEmpty()) {
			return null;
		}
		if (claims.size() == 1) {
			return claims.get(0);
		}
		if (listenServer) {
			for (ServerLink.HostPlayer p : claims) {
				if (p.entIndex() == 1) {
					return p;
				}
			}
		}
		return null;
	}

	/** The SteamID64 a verified player plays as, or 0. */
	public long verifiedSteamId(UUID uuid) {
		Long v = this.verified.get(uuid);
		return v != null ? v : 0L;
	}

	public boolean isVerified(UUID uuid) {
		return this.verified.containsKey(uuid);
	}

	/** The player left the Minecraft server. */
	public void forget(UUID uuid) {
		this.verified.remove(uuid);
		this.orphanedSince.remove(uuid);
	}

	/** A new link session / server stop: nothing carries over. */
	public void clear() {
		this.firstSeen.clear();
		this.consumed.clear();
		this.verified.clear();
		this.orphanedSince.clear();
	}
}
