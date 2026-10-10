package dev.gmodcraft.weapon;

import dev.gmodcraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The kHostEvWeaponGive text ("class\0print name", NUL-padded; protocol v17). No Minecraft classes. */
public final class WeaponText {
	private WeaponText() {
	}

	/**
	 * {class, print name}, or null when the class is empty, too long, not printable ASCII without
	 * spaces, or doesn't hash to {@code hash}. The print name decodes leniently (replacement chars).
	 */
	public static String @Nullable [] parse(byte[] text, int hash) {
		int nul = 0;
		while (nul < text.length && text[nul] != 0) {
			nul++;
		}
		if (nul == 0 || nul > Proto.WEAPON_CLASS_MAX_BYTES) {
			return null;
		}
		for (int i = 0; i < nul; i++) {
			if (text[i] <= 0x20 || text[i] >= 0x7F) {
				return null;
			}
		}
		String cls = new String(text, 0, nul, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
		if (Fnv.hash32(cls) != hash) {
			return null;
		}
		int end = nul + 1;
		while (end < text.length && text[end] != 0) {
			end++;
		}
		String name = "";
		if (end > nul + 1) {
			try {
				name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
					.decode(ByteBuffer.wrap(text, nul + 1, end - nul - 1)).toString();
			} catch (CharacterCodingException e) {
				name = "";
			}
		}
		return new String[] { cls, name };
	}
}
