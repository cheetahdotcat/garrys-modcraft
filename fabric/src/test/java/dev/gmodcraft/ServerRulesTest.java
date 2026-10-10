package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** P8 WP3: server rules parsing, validation and the properties rewrite. */
class ServerRulesTest {
	private static Properties props(String text) throws Exception {
		Properties p = new Properties();
		p.load(new StringReader(text));
		return p;
	}

	@Test
	void defaultsKeepTheOldBehaviour() {
		ServerRules r = ServerRules.DEFAULT;
		assertTrue(r.keepInventory());
		assertTrue(r.digIntoMap());
		assertTrue(r.noclipMc());
		assertTrue(r.fireCrossover());
		assertTrue(r.physgunMobs());
		assertTrue(r.digThinWalls(), "v38: thin walls dig by default (as before the rule)");
		assertEquals(Proto.RULE_DIG_INTO_MAP | Proto.RULE_NOCLIP_MC | Proto.RULE_FIRE_CROSSOVER | Proto.RULE_PHYSGUN_MOBS | Proto.RULE_DIG_THIN_WALLS, r.modFlags());
		ServerRules noThin = r.with(ServerRules.DIG_THIN_WALLS, "off");
		assertFalse(noThin.digThinWalls());
		assertEquals("false", noThin.value(ServerRules.DIG_THIN_WALLS));
		assertEquals(0, noThin.modFlags() & Proto.RULE_DIG_THIN_WALLS);
		assertTrue(noThin.digIntoMap(), "other rules unchanged");
		assertTrue(noThin.isSet(ServerRules.DIG_THIN_WALLS));
		ServerRules noPhysgun = r.with(ServerRules.PHYSGUN_MOBS, "false");
		assertFalse(noPhysgun.physgunMobs());
		assertEquals("false", noPhysgun.value(ServerRules.PHYSGUN_MOBS));
		assertEquals(0, noPhysgun.modFlags() & Proto.RULE_PHYSGUN_MOBS);
		assertTrue(noPhysgun.fireCrossover(), "other rules unchanged");
		ServerRules off = r.with(ServerRules.FIRE_CROSSOVER, "off");
		assertFalse(off.fireCrossover());
		assertEquals("false", off.value(ServerRules.FIRE_CROSSOVER));
		assertEquals(0, off.modFlags() & Proto.RULE_FIRE_CROSSOVER);
		assertEquals(5.0F, r.hostDamagePerMcDamage());
		assertEquals("mirror", r.worldType());
		assertEquals(Proto.ANCHOR_FLOOR_Y, r.floorY());
		assertFalse(r.isSet(ServerRules.PVP));
	}

	@Test
	void fromPropertiesIsLenient() throws Exception {
		List<String> warnings = new ArrayList<>();
		ServerRules r = ServerRules.fromProperties(props("""
			gamemode=Creative
			pvp=false
			keepInventory=nope
			hostDamagePerMcDamage=-3
			worldType=gmodcraft:flat_void_maps
			floorY=70
			destruction=false
			"""), warnings::add);
		assertEquals(Proto.GAME_CREATIVE, r.gameMode());
		assertFalse(r.pvp());
		assertTrue(r.isSet(ServerRules.PVP));
		assertTrue(r.keepInventory());  // the bad value kept the default
		assertEquals(5.0F, r.hostDamagePerMcDamage());
		assertEquals("flat_void_maps", r.worldType());
		assertEquals(Proto.WORLD_FLAT_VOID_MAPS, r.worldTypeId());
		assertEquals(70, r.floorY());
		assertEquals(2, warnings.size(), warnings.toString());
	}

	@Test
	void validationRefusesBadValues() {
		ServerRules r = ServerRules.DEFAULT;
		assertThrows(IllegalArgumentException.class, () -> r.with("gamemode", "hardcore"));
		assertThrows(IllegalArgumentException.class, () -> r.with("gamemode", "7"));
		assertThrows(IllegalArgumentException.class, () -> r.with("pvp", "maybe"));
		assertThrows(IllegalArgumentException.class, () -> r.with("hostDamagePerMcDamage", "NaN"));
		assertThrows(IllegalArgumentException.class, () -> r.with("hostDamagePerMcDamage", "0"));
		assertThrows(IllegalArgumentException.class, () -> r.with("hostDamagePerMcDamage", "1e9"));
		assertThrows(IllegalArgumentException.class, () -> r.with("floorY", "64.5"));
		assertThrows(IllegalArgumentException.class, () -> r.with("floorY", "5000"));
		assertThrows(IllegalArgumentException.class, () -> r.with("worldType", "amplified"));
		// W2: underground is a world type now (id 4, past WORLD_OTHER = 3)
		assertEquals("underground", r.with("worldType", "gmodcraft:underground").worldType());
		assertEquals(Proto.WORLD_UNDERGROUND, r.with("worldType", "underground").worldTypeId());
		assertEquals("underground", ServerRules.typeName(Proto.WORLD_UNDERGROUND));
		assertEquals("other", ServerRules.typeName(Proto.WORLD_OTHER));
		// v41: hull (id 5)
		assertEquals("hull", r.with("worldType", "gmodcraft:hull").worldType());
		assertEquals(Proto.WORLD_HULL, r.with("worldType", "hull").worldTypeId());
		assertEquals("hull", ServerRules.typeName(Proto.WORLD_HULL));
		assertThrows(IllegalArgumentException.class, () -> r.with("hardcore", "true"));
		assertEquals(Proto.GAME_SPECTATOR, r.with("gamemode", "3").gameMode());
		assertTrue(r.with("keepInventory", "OFF").isSet("keepInventory"));
		assertFalse(r.with("keepInventory", "off").keepInventory());
	}

	@Test
	void adminPairsAreAllOrNothing() throws Exception {
		Map<String, String> pairs = ServerRules.parsePairs("pvp=false; keepInventory=false;");
		assertEquals(Map.of("pvp", "false", "keepInventory", "false"), pairs);
		ServerRules r = ServerRules.DEFAULT.withAll(pairs);
		assertFalse(r.pvp());
		assertFalse(r.keepInventory());
		ServerRules.Bad bad = assertThrows(ServerRules.Bad.class, () -> ServerRules.DEFAULT.withAll(ServerRules.parsePairs("pvp=false;bogus=1")));
		assertEquals(2, bad.pair);
		assertEquals(1, assertThrows(ServerRules.Bad.class, () -> ServerRules.parsePairs("pvp")).pair);
		assertEquals(0, assertThrows(ServerRules.Bad.class, () -> ServerRules.parsePairs(" ; ")).pair);
	}

	@Test
	void rewriteKeepsOtherKeysAndComments() throws Exception {
		String old = "# Garry's Modcraft\ndestruction=false\npvp = true\njoin=\n# keep me\npvp: true\n";
		String now = ServerRules.rewrite(old, Map.of("pvp", "false", "gamemode", "creative"));
		Properties p = props(now);
		assertEquals("false", p.getProperty("pvp"));
		assertEquals("creative", p.getProperty("gamemode"));
		assertEquals("false", p.getProperty("destruction"));
		assertEquals("", p.getProperty("join"));
		assertTrue(now.contains("# keep me"));
		assertTrue(now.startsWith("# Garry's Modcraft\n"));
		assertEquals("gamemode=creative\n", ServerRules.rewrite("", Map.of("gamemode", "creative")));
	}

	@Test
	void valuesRoundTrip() throws Exception {
		ServerRules r = ServerRules.DEFAULT.withAll(ServerRules.parsePairs("gamemode=adventure;forceGamemode=true;hostDamagePerMcDamage=2.5;floorY=-10"));
		Properties p = new Properties();
		for (String k : ServerRules.KEYS) {
			p.setProperty(k, r.value(k));
		}
		ServerRules back = ServerRules.fromProperties(p, w -> {
			throw new AssertionError(w);
		});
		assertEquals(r.gameMode(), back.gameMode());
		assertEquals(r.forceGamemode(), back.forceGamemode());
		assertEquals(r.hostDamagePerMcDamage(), back.hostDamagePerMcDamage());
		assertEquals(r.floorY(), back.floorY());
	}

	@Test
	void mobRulesV34() throws Exception {
		ServerRules d = ServerRules.DEFAULT;
		assertFalse(d.mobSpawning(), "spawning stays off by default (the mirror world's old behaviour)");
		assertEquals(100, d.mobCapPercent());
		assertFalse(d.isSet(ServerRules.DIFFICULTY), "the world keeps its own difficulty unless set");
		assertEquals(0, d.modFlags() & Proto.RULE_MOB_SPAWNING);
		ServerRules r = d.withAll(ServerRules.parsePairs("difficulty=hard;mobSpawning=true;mobCapPercent=250"));
		assertEquals(Proto.DIFFICULTY_HARD, r.difficulty());
		assertTrue(r.isSet(ServerRules.DIFFICULTY));
		assertTrue(r.mobSpawning());
		assertEquals(250, r.mobCapPercent());
		assertEquals(Proto.RULE_MOB_SPAWNING, r.modFlags() & Proto.RULE_MOB_SPAWNING);
		assertEquals("hard", r.value(ServerRules.DIFFICULTY));
		assertEquals("250", r.value(ServerRules.MOB_CAP_PERCENT));
		assertTrue(r.keepInventory() && r.physgunMobs(), "other rules unchanged");
		assertEquals(Proto.DIFFICULTY_PEACEFUL, d.with(ServerRules.DIFFICULTY, "0").difficulty());
		assertThrows(IllegalArgumentException.class, () -> d.with(ServerRules.DIFFICULTY, "hardcore"));
		assertThrows(IllegalArgumentException.class, () -> d.with(ServerRules.MOB_CAP_PERCENT, "-1"));
		assertThrows(IllegalArgumentException.class, () -> d.with(ServerRules.MOB_CAP_PERCENT, "1001"));
		assertThrows(IllegalArgumentException.class, () -> d.with(ServerRules.MOB_SPAWNING, "maybe"));
		Properties p = new Properties();
		for (String k : ServerRules.KEYS) {
			p.setProperty(k, r.value(k));
		}
		ServerRules back = ServerRules.fromProperties(p, w -> {
			throw new AssertionError(w);
		});
		assertEquals(r.difficulty(), back.difficulty());
		assertEquals(r.mobSpawning(), back.mobSpawning());
		assertEquals(r.mobCapPercent(), back.mobCapPercent());
	}
}
