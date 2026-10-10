package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.net.SkyNet;
import org.junit.jupiter.api.Test;

/** The soft e4mc hook's parsing, and the log-safe join token hash. */
class E4mcTest {
	@Test
	void picksTheDomainOutOfE4mcsLogLine() {
		assertEquals("abc-def.e4mc.link", E4mc.parseDomain("Domain assigned: abc-def.e4mc.link"));
		assertEquals("x.e4mc.link", E4mc.parseDomain("Domain assigned:   x.e4mc.link  "));
		assertNull(E4mc.parseDomain("Domain assigned: "));
		assertNull(E4mc.parseDomain("Domain assigned: two words"));
		assertNull(E4mc.parseDomain("Something else"));
		assertNull(E4mc.parseDomain(null));
	}

	@Test
	void tokenHashIsShortAndHidesTheToken() {
		String token = "secret-pairing-token-0123456789abcdef";
		String hash = SkyNet.tokenHash(token);
		assertTrue(hash.matches("#[0-9a-f]{8}"), hash);
		assertTrue(!hash.contains("secret"));
		assertEquals(hash, SkyNet.tokenHash(token));
		assertTrue(!new SkyNet.JoinToken(token).toString().contains("secret"));
	}
}
