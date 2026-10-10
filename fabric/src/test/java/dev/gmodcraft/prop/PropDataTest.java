package dev.gmodcraft.prop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.weapon.Fnv;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The gmodcraft:prop component (protocol v33): canonical form (stacking), codecs, the prop text both ways. */
class PropDataTest {
	private static final String CRATE = "models/props_junk/wood_crate001a.mdl";

	@Test
	void canonicalFormMakesLookalikesEqual() {
		PropData a = new PropData("Models\\Props_Junk\\Wood_Crate001a.mdl", 0, "0100", PropData.WHITE, "", "");
		PropData b = new PropData(CRATE, 0, "01", PropData.WHITE, "", "");
		assertEquals(b, a, "case, backslashes and trailing zero body groups don't matter");
		assertEquals("01", a.bodygroups());
		assertEquals("wood_crate001a", a.shortName());
		assertEquals(Fnv.hash32(CRATE), a.hash());
		assertEquals("", new PropData("models/../cfg/x.mdl", 0, "", 0, "", "").model(), "no path escapes");
		assertTrue(new PropData("", 0, "", 0, "", "").inert());
		assertEquals(Proto.PROP_MODEL_MAX_BYTES, new PropData("m".repeat(400), 0, "", 0, "", "").model().length());
		assertEquals("0a", new PropData(CRATE, 0, "", 0, "", "0A-zz").dupe());
		assertEquals(65535, new PropData(CRATE, 1 << 20, "", 0, "", "").skin());
	}

	@Test
	void codecRoundTripAndDefaults() {
		PropData d = new PropData(CRATE, 2, "1", 0xFF0000FF, "models/debug/debugwhite", "beef");
		JsonElement json = PropData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
		assertEquals(d, PropData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		JsonObject minimal = new JsonObject();
		minimal.addProperty("model", CRATE);
		assertEquals(PropData.of(CRATE), PropData.CODEC.parse(JsonOps.INSTANCE, minimal).getOrThrow());
		ByteBuf buf = Unpooled.buffer();
		PropData.STREAM_CODEC.encode(buf, d);
		assertEquals(d, PropData.STREAM_CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void textRoundTrip() {
		PropData d = new PropData(CRATE, 3, "12", 0x80FF80FF, "phoenix_storms/metalset_1-2", "");
		byte[] t = d.text();
		assertTrue(t.length <= Proto.PROP_TEXT_MAX_BYTES);
		byte[] padded = new byte[Proto.PROP_HOST_CHUNK_BYTES * Proto.PROP_HOST_MAX_CHUNKS];
		System.arraycopy(t, 0, padded, 0, t.length);
		assertEquals(d, PropData.parse(padded, 3, 0x80FF80FF));
		assertEquals(PropData.of(CRATE), PropData.parse((CRATE + "\0").getBytes(StandardCharsets.US_ASCII), 0, PropData.WHITE), "missing parts are empty");
		assertNull(PropData.parse(new byte[8], 0, 0), "no model");
		// the longest legal text fits the protocol's budget
		PropData max = new PropData("m".repeat(500), 0, "z".repeat(99), 0, "t".repeat(200), "f".repeat(40));
		assertTrue(max.text().length <= Proto.PROP_TEXT_MAX_BYTES);
		assertTrue(max.text().length <= Proto.PROP_MC_CHUNK_BYTES * Proto.PROP_MC_MAX_CHUNKS);
	}
}
