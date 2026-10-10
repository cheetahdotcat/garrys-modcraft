package dev.gmodcraft.weapon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.gmodcraft.link.Proto;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The gmodcraft:swep component's codecs, the class hash and the Give text parser (protocol v17). */
class SwepDataTest {
	@Test
	void fnvMatchesTheHostsHash() {
		// tools/fake_host.py fnv1a32, shared/hybrid.lua: FNV-1a 32 over the lowercased class.
		assertEquals(0x811C9DC5, Fnv.hash32(""));
		assertEquals((int) 0xE40C292CL, Fnv.hash32("a"));
		assertEquals(Fnv.hash32("weapon_pistol"), Fnv.hash32("WEAPON_Pistol"));
		assertEquals((int) 0xC4131651L, Fnv.hash32("weapon_pistol"));
		assertEquals((int) 0xC22D7F39L, Fnv.hash32("gmod_tool"));
	}

	@Test
	void codecRoundTripAndDefaults() {
		SwepData d = new SwepData("Weapon_SMG1", "SMG", Proto.WEAP_CAT_SMG, 45, 3);
		assertEquals("weapon_smg1", d.weaponClass());
		JsonElement json = SwepData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
		assertEquals(d, SwepData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		JsonObject minimal = new JsonObject();
		minimal.addProperty("class", "weapon_crowbar");
		SwepData m = SwepData.CODEC.parse(JsonOps.INSTANCE, minimal).getOrThrow();
		assertEquals(new SwepData("weapon_crowbar", "", Proto.WEAP_CAT_GENERIC, -1, -1), m);
		// a component without a class (old / hand-made stacks) decodes as inert instead of failing
		assertEquals("", SwepData.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().weaponClass());
		// out-of-range values are clamped, never rejected (a hand-edited / creative stack stays loadable)
		assertEquals(Proto.WEAP_CAT_GENERIC, new SwepData("x", "", 99, -5, -7).category());
		assertEquals(-1, new SwepData("x", "", 0, -5, -7).clip1());
		assertEquals("weapon_a_", new SwepData("Weapon Aé", "", 0, 0, 0).weaponClass());
	}

	@Test
	void streamCodecRoundTrip() {
		SwepData d = new SwepData("gmod_tool", "Tool Gun ™", Proto.WEAP_CAT_TOOL, -1, -1);
		ByteBuf buf = Unpooled.buffer();
		SwepData.STREAM_CODEC.encode(buf, d);
		assertEquals(d, SwepData.STREAM_CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void categoryNamesCoverEveryCategory() {
		assertEquals(Proto.WEAP_CATEGORIES, SwepData.CATEGORY_NAMES.length);
		assertEquals("pistol", SwepData.CATEGORY_NAMES[Proto.WEAP_CAT_PISTOL]);
		assertEquals("tool", SwepData.CATEGORY_NAMES[Proto.WEAP_CAT_TOOL]);
	}

	private static byte[] text(String s) {
		byte[] out = new byte[Proto.WEAPON_TEXT_MAX_BYTES];
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(b, 0, out, 0, b.length);
		return out;
	}

	@Test
	void giveTextParsing() {
		int h = Fnv.hash32("weapon_357");
		assertArrayEquals(new String[] { "weapon_357", ".357 Magnum" }, WeaponText.parse(text("weapon_357\0.357 Magnum"), h));
		assertArrayEquals(new String[] { "weapon_357", "" }, WeaponText.parse(text("weapon_357"), h));
		assertNull(WeaponText.parse(text("weapon_357\0x"), h + 1), "hash mismatch");
		assertNull(WeaponText.parse(text("\0name"), Fnv.hash32("")), "empty class");
		assertNull(WeaponText.parse(text("weapon 357\0x"), Fnv.hash32("weapon 357")), "space in class");
		assertNull(WeaponText.parse(text("a".repeat(Proto.WEAPON_CLASS_MAX_BYTES + 1)), Fnv.hash32("a".repeat(Proto.WEAPON_CLASS_MAX_BYTES + 1))));
		// a print name cut in the middle of a character decodes with a replacement, never fails
		byte[] t = text("weapon_357\0é");
		t[12] = 0;
		assertEquals("weapon_357", WeaponText.parse(t, h)[0]);
	}
}
