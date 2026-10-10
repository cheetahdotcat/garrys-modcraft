package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hull world's MIRROR bits per section: set, clear (a changed block), merge, sync codec. */
class MirrorColumnTest {
	@Test
	void setAndClear() {
		Map<Integer, long[]> add = new HashMap<>();
		MirrorColumn.set(add, 3, 63, -5);
		MirrorColumn.set(add, 3, 62, -5);
		MirrorColumn.set(add, 15, -1, 31);
		MirrorColumn c = MirrorColumn.EMPTY.with(add);
		assertTrue(c.isMirror(3, 63, -5));
		assertTrue(c.isMirror(3 + 16, 63, -5 + 32), "chunk-relative: the same bit in another chunk's column");
		assertTrue(c.isMirror(15, -1, 31));
		assertFalse(c.isMirror(3, 64, -5));
		assertFalse(c.isMirror(4, 63, -5));
		assertEquals(3, c.count());
		assertEquals(2, c.sections().size());

		MirrorColumn d = c.without(3, 63, -5);
		assertFalse(d.isMirror(3, 63, -5), "mined / changed: not a mirror block any more");
		assertTrue(d.isMirror(3, 62, -5));
		assertTrue(c.isMirror(3, 63, -5), "immutable: the old column is unchanged");
		MirrorColumn e = d.without(15, -1, 31);
		assertEquals(1, e.sections().size(), "a section left empty is dropped");
		assertNull(e.bits(-1));
		assertEquals(0, e.without(3, 62, -5).count());
		assertEquals(e.count(), e.without(0, 200, 0).count(), "clearing a bit that isn't set changes nothing");
	}

	@Test
	void mergeKeepsBoth() {
		Map<Integer, long[]> a = new HashMap<>();
		MirrorColumn.set(a, 1, 1, 1);
		Map<Integer, long[]> b = new HashMap<>();
		MirrorColumn.set(b, 2, 2, 2);
		MirrorColumn.set(b, 2, 40, 2);
		MirrorColumn c = MirrorColumn.EMPTY.with(a).with(b);
		assertTrue(c.isMirror(1, 1, 1) && c.isMirror(2, 2, 2) && c.isMirror(2, 40, 2));
		assertEquals(3, c.count());
	}

	@Test
	void saveCodecRoundTrip() {
		Map<Integer, long[]> a = new HashMap<>();
		MirrorColumn.set(a, 0, -64, 0);
		MirrorColumn.set(a, 15, 63, 15);
		MirrorColumn.set(a, 7, 300, 9);
		MirrorColumn c = MirrorColumn.EMPTY.with(a);
		var json = MirrorColumn.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c).getOrThrow();
		MirrorColumn back = MirrorColumn.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(3, back.count());
		assertTrue(back.isMirror(0, -64, 0) && back.isMirror(15, 63, 15) && back.isMirror(7, 300, 9));
	}

	@Test
	void syncCodecRoundTrip() {
		Map<Integer, long[]> a = new HashMap<>();
		for (int y = -20; y < 70; y += 7) {
			MirrorColumn.set(a, y & 15, y, (y * 3) & 15);
		}
		MirrorColumn c = MirrorColumn.EMPTY.with(a);
		ByteBuf buf = Unpooled.buffer();
		MirrorColumn.STREAM_CODEC.encode(buf, c);
		MirrorColumn back = MirrorColumn.STREAM_CODEC.decode(buf);
		assertEquals(c.count(), back.count());
		for (int y = -20; y < 70; y += 7) {
			assertTrue(back.isMirror(y & 15, y, (y * 3) & 15));
		}
		assertEquals(0, buf.readableBytes());
	}
}
