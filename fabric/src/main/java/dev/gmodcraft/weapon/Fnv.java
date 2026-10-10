package dev.gmodcraft.weapon;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** FNV-1a 32 of a lowercased name: the protocol's class hash (and its worldId), as GMod computes it. */
public final class Fnv {
	private Fnv() {
	}

	/** FNV-1a 32 over the UTF-8 of {@code s} lowercased (Locale.ROOT); never 0 for a GMod class in practice. */
	public static int hash32(String s) {
		int h = 0x811C9DC5;
		for (byte b : s.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
			h ^= b & 0xFF;
			h *= 0x01000193;
		}
		return h;
	}
}
