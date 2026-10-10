package dev.gmodcraft.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.gmodcraft.link.Proto;
import org.junit.jupiter.api.Test;

class MapLinkTest {
	@Test
	void parsesKindModeName() {
		MapLink l = MapLink.parse("door out front_door_1", 0x1234, "gm_construct", 42);
		assertNotNull(l);
		assertEquals(Proto.LINK_DOOR, l.kind());
		assertEquals(Proto.LINK_OUT, l.mode());
		assertEquals("front_door_1", l.name());
		assertEquals(42, l.creationId());
		assertEquals(0x1234, l.worldId());
		assertEquals("gm_construct", l.map());
		assertEquals(Proto.LINK_DOOR | Proto.LINK_OUT << 8, l.flags());
		MapLink b = MapLink.parse("  Button   IN  ", 1, "m", 0);
		assertNotNull(b);
		assertEquals("", b.name());
		assertEquals(Proto.LINK_IN, b.mode());
	}

	@Test
	void refusesWhatCantGoThatWay() {
		assertNull(MapLink.parse("trigger out t", 1, "m", 5));   // a trigger can't be driven
		assertNull(MapLink.parse("light in l", 1, "m", 5));      // a light reports nothing
		assertNull(MapLink.parse("movelinear in x", 1, "m", 5));
		assertNull(MapLink.parse("sprite in x", 1, "m", 5));
		assertNotNull(MapLink.parse("relay in r", 1, "m", 5));
		assertNotNull(MapLink.parse("button out b", 1, "m", 5));
		assertNotNull(MapLink.parse("momentary in b", 1, "m", 5));
		assertNull(MapLink.parse("momentary out b", 1, "m", 5));
	}

	@Test
	void refusesMalformed() {
		assertNull(MapLink.parse("", 1, "m", 5));
		assertNull(MapLink.parse("door", 1, "m", 5));
		assertNull(MapLink.parse("rocket in x", 1, "m", 5));
		assertNull(MapLink.parse("door sideways x", 1, "m", 5));
		assertNull(MapLink.parse("door in x", 1, "m", -1));     // no MapCreationID
		assertNull(MapLink.parse(null, 1, "m", 5));
	}

	@Test
	void namesAreCleanedAndCut() {
		MapLink l = MapLink.parse("door in a b;c\"d" + "x".repeat(80), 1, "m", 5);
		assertNotNull(l);
		assertEquals(48, l.name().length());
		assertEquals("abcd", l.name().substring(0, 4));
	}
}
