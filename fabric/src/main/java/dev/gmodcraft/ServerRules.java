package dev.gmodcraft;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MC server's rules (P8 WP3, protocol v24), as config/gmodcraft.properties has them. Immutable;
 * pure (no Minecraft classes), so parsing and validation are unit-tested. GmodCraftConfig holds the
 * current one and applies it.
 *
 * <pre>
 * gamemode=survival          # survival | creative | adventure | spectator: the default game mode
 * forceGamemode=false        # every player gets that game mode when they join
 * pvp=true                   # players can hurt each other
 * keepInventory=true         # nothing dropped on death
 * digIntoMap=true            # Minecraft digs into GMod's map (mining, explosions)
 * digThinWalls=true          # v38: players dig map cells that are mostly empty too (off: thin walls / floors stay, SkyDig.thinWall)
 * hostDamagePerMcDamage=5.0  # GMod damage per Minecraft damage point (GMod 100 hp / MC 20)
 * noclipMc=true              # GMod's noclip reaches Minecraft players
 * fireCrossover=true         # fire crosses between GMod and Minecraft (props catch MC fire, burning GMod things light MC blocks)
 * physgunMobs=true           # GMod's physgun / gravgun / tools work on Minecraft's mobs, animals, carts and boats
 * difficulty=normal          # v34: peaceful | easy | normal | hard (only applied when set: else the world's own)
 * mobSpawning=false          # v34: natural mob spawning (gamerules spawn_mobs / spawn_monsters; off: the mirror world's default)
 * mobCapPercent=100          # v34: natural spawning caps in percent of vanilla (0 .. 1000)
 * worldType=mirror           # NEW worlds only: mirror (void) | flat_void_maps | flat_everywhere | underground
 * floorY=64                  # NEW worlds only: the y maps' floors land on (flat worlds: their surface is floorY - 1)
 * </pre>
 *
 * gamemode, forceGamemode, pvp and difficulty are only applied when the file (or an admin) sets them: a world
 * keeps its own otherwise. The others always apply (with these defaults).
 */
public record ServerRules(int gameMode, boolean forceGamemode, boolean pvp, boolean keepInventory, boolean digIntoMap, float hostDamagePerMcDamage,
	boolean noclipMc, boolean fireCrossover, boolean physgunMobs, int difficulty, boolean mobSpawning, int mobCapPercent, String worldType, int floorY,
	boolean digThinWalls, Set<String> explicit) {

	public static final String GAMEMODE = "gamemode", FORCE_GAMEMODE = "forceGamemode", PVP = "pvp", KEEP_INVENTORY = "keepInventory",
		DIG_INTO_MAP = "digIntoMap", DAMAGE = "hostDamagePerMcDamage", NOCLIP_MC = "noclipMc", FIRE_CROSSOVER = "fireCrossover", PHYSGUN_MOBS = "physgunMobs", DIFFICULTY = "difficulty",
		MOB_SPAWNING = "mobSpawning", MOB_CAP_PERCENT = "mobCapPercent", WORLD_TYPE = "worldType", FLOOR_Y = "floorY", DIG_THIN_WALLS = "digThinWalls";
	/** Every key, in the order they're written. */
	public static final List<String> KEYS = List.of(GAMEMODE, FORCE_GAMEMODE, PVP, KEEP_INVENTORY, DIG_INTO_MAP, DIG_THIN_WALLS, DAMAGE, NOCLIP_MC, FIRE_CROSSOVER, PHYSGUN_MOBS, DIFFICULTY, MOB_SPAWNING,
		MOB_CAP_PERCENT, WORLD_TYPE, FLOOR_Y);
	public static final List<String> GAME_MODES = List.of("survival", "creative", "adventure", "spectator");  // index = Proto.GAME_*
	public static final List<String> DIFFICULTIES = List.of("peaceful", "easy", "normal", "hard");  // index = Proto.DIFFICULTY_*
	/** The world types a new world can have (the level-type / preset name is gmodcraft:&lt;name&gt;); typeId gives the Proto.WORLD_*. */
	public static final List<String> WORLD_TYPES = List.of("mirror", "flat_void_maps", "flat_everywhere", "underground");
	/** floorY limits: the flat layers (floorY - 4 .. floorY - 1) and a map's floor stay inside the dimension (-1024 .. 1023). */
	public static final int FLOOR_Y_MIN = -1000, FLOOR_Y_MAX = 1000;
	public static final float DAMAGE_MAX = 1000.0F;

	public static final ServerRules DEFAULT = new ServerRules(Proto.GAME_SURVIVAL, false, true, true, true, 5.0F, true, true, true, Proto.DIFFICULTY_NORMAL, false, 100,
		"mirror", Proto.ANCHOR_FLOOR_Y, true, Set.of());

	public ServerRules {
		explicit = Set.copyOf(explicit);
	}

	/** Was this key set by the file or an admin (else the default stands, or the world's own value)? */
	public boolean isSet(String key) {
		return this.explicit.contains(key);
	}

	/** Proto.WORLD_* of worldType (Proto.WORLD_VOID for an unknown name: never stored). */
	public int worldTypeId() {
		int i = typeId(this.worldType);
		return i < 0 ? Proto.WORLD_VOID : i;
	}

	/** Proto.WORLD_* of a world type name (WORLD_TYPES), or -1. */
	public static int typeId(String name) {
		return switch (name == null ? "" : name) {
			case "mirror" -> Proto.WORLD_VOID;
			case "flat_void_maps" -> Proto.WORLD_FLAT_VOID_MAPS;
			case "flat_everywhere" -> Proto.WORLD_FLAT_EVERYWHERE;
			case "underground" -> Proto.WORLD_UNDERGROUND;
			default -> -1;
		};
	}

	/** The name of a Proto.WORLD_* ("other" for WORLD_OTHER or an unknown id). */
	public static String typeName(int id) {
		for (String t : WORLD_TYPES) {
			if (typeId(t) == id) {
				return t;
			}
		}
		return "other";
	}

	/** McServerState::ruleFlags for the rules the mod applies itself (pvp / keepInventory / force come from the server). */
	public int modFlags() {
		return (this.digIntoMap ? Proto.RULE_DIG_INTO_MAP : 0) | (this.noclipMc ? Proto.RULE_NOCLIP_MC : 0)
			| (this.fireCrossover ? Proto.RULE_FIRE_CROSSOVER : 0) | (this.physgunMobs ? Proto.RULE_PHYSGUN_MOBS : 0)
			| (this.mobSpawning ? Proto.RULE_MOB_SPAWNING : 0) | (this.digThinWalls ? Proto.RULE_DIG_THIN_WALLS : 0);
	}

	/** This with key = value. Throws IllegalArgumentException (the reason) for an unknown key or a bad value. */
	public ServerRules with(String key, String value) {
		String v = value == null ? "" : value.trim();
		Set<String> ex = new LinkedHashSet<>(this.explicit);
		ex.add(key);
		int gm = this.gameMode, diff = this.difficulty, cap = this.mobCapPercent, fy = this.floorY;
		boolean force = this.forceGamemode, pv = this.pvp, keep = this.keepInventory, dig = this.digIntoMap, noclip = this.noclipMc,
			fire = this.fireCrossover, physgun = this.physgunMobs, spawn = this.mobSpawning, thin = this.digThinWalls;
		float dmg = this.hostDamagePerMcDamage;
		String wt = this.worldType;
		switch (key) {
			case GAMEMODE -> gm = named(GAMEMODE, GAME_MODES, v);
			case FORCE_GAMEMODE -> force = bool(key, v);
			case PVP -> pv = bool(key, v);
			case KEEP_INVENTORY -> keep = bool(key, v);
			case DIG_INTO_MAP -> dig = bool(key, v);
			case DIG_THIN_WALLS -> thin = bool(key, v);
			case DAMAGE -> {
				float f;
				try {
					f = Float.parseFloat(v);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(key + ": not a number: '" + v + "'");
				}
				if (!(f > 0.0F) || !Float.isFinite(f) || f > DAMAGE_MAX) {
					throw new IllegalArgumentException(key + ": must be more than 0 and at most " + DAMAGE_MAX + ": '" + v + "'");
				}
				dmg = f;
			}
			case NOCLIP_MC -> noclip = bool(key, v);
			case FIRE_CROSSOVER -> fire = bool(key, v);
			case PHYSGUN_MOBS -> physgun = bool(key, v);
			case DIFFICULTY -> diff = named(DIFFICULTY, DIFFICULTIES, v);
			case MOB_SPAWNING -> spawn = bool(key, v);
			case MOB_CAP_PERCENT -> cap = whole(key, v, 0, Proto.MOB_CAP_MAX);
			case WORLD_TYPE -> {
				String t = v.toLowerCase(Locale.ROOT);
				if (t.startsWith("gmodcraft:")) {
					t = t.substring("gmodcraft:".length());
				}
				if (!WORLD_TYPES.contains(t)) {
					throw new IllegalArgumentException(key + ": one of " + String.join(", ", WORLD_TYPES) + ": '" + v + "'");
				}
				wt = t;
			}
			case FLOOR_Y -> fy = whole(key, v, FLOOR_Y_MIN, FLOOR_Y_MAX);
			default -> throw new IllegalArgumentException("unknown rule '" + key + "' (" + String.join(", ", KEYS) + ")");
		}
		return new ServerRules(gm, force, pv, keep, dig, dmg, noclip, fire, physgun, diff, spawn, cap, wt, fy, thin, ex);
	}

	private static int whole(String key, String v, int min, int max) {
		int y;
		try {
			y = Integer.parseInt(v);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + ": not a whole number: '" + v + "'");
		}
		if (y < min || y > max) {
			throw new IllegalArgumentException(key + ": " + min + " .. " + max + ": '" + v + "'");
		}
		return y;
	}

	/** The value of key as the properties file writes it. */
	public String value(String key) {
		return switch (key) {
			case GAMEMODE -> GAME_MODES.get(this.gameMode);
			case FORCE_GAMEMODE -> Boolean.toString(this.forceGamemode);
			case PVP -> Boolean.toString(this.pvp);
			case KEEP_INVENTORY -> Boolean.toString(this.keepInventory);
			case DIG_INTO_MAP -> Boolean.toString(this.digIntoMap);
			case DIG_THIN_WALLS -> Boolean.toString(this.digThinWalls);
			case DAMAGE -> Float.toString(this.hostDamagePerMcDamage);
			case NOCLIP_MC -> Boolean.toString(this.noclipMc);
			case FIRE_CROSSOVER -> Boolean.toString(this.fireCrossover);
			case PHYSGUN_MOBS -> Boolean.toString(this.physgunMobs);
			case DIFFICULTY -> DIFFICULTIES.get(this.difficulty);
			case MOB_SPAWNING -> Boolean.toString(this.mobSpawning);
			case MOB_CAP_PERCENT -> Integer.toString(this.mobCapPercent);
			case WORLD_TYPE -> this.worldType;
			case FLOOR_Y -> Integer.toString(this.floorY);
			default -> throw new IllegalArgumentException("unknown rule '" + key + "'");
		};
	}

	/** A name from names (or its index), as its index. */
	private static int named(String key, List<String> names, String v) {
		String m = v.toLowerCase(Locale.ROOT);
		int i = names.indexOf(m);
		if (i < 0) {
			try {
				i = Integer.parseInt(m);
			} catch (NumberFormatException e) {
				i = -1;
			}
		}
		if (i < 0 || i >= names.size()) {
			throw new IllegalArgumentException(key + ": one of " + String.join(", ", names) + ": '" + v + "'");
		}
		return i;
	}

	private static boolean bool(String key, String v) {
		if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on")) {
			return true;
		}
		if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off")) {
			return false;
		}
		throw new IllegalArgumentException(key + ": true or false: '" + v + "'");
	}

	/** The rules a properties file sets (lenient: a bad value is reported to warn and its default kept). */
	public static ServerRules fromProperties(Properties props, java.util.function.Consumer<String> warn) {
		ServerRules r = DEFAULT;
		for (String key : KEYS) {
			String v = props.getProperty(key);
			if (v == null) {
				continue;
			}
			try {
				r = r.with(key, v);
			} catch (IllegalArgumentException e) {
				warn.accept(e.getMessage() + "; using " + r.value(key));
			}
		}
		return r;
	}

	/** One admin request's pairs: "key=value;key=value". Throws Bad (with the 1-based pair) when one is malformed. */
	public static Map<String, String> parsePairs(String text) throws Bad {
		Map<String, String> out = new LinkedHashMap<>();
		String[] parts = text == null ? new String[0] : text.split(";");
		int n = 0;
		for (String part : parts) {
			if (part.isBlank()) {
				continue;
			}
			n++;
			int eq = part.indexOf('=');
			if (eq <= 0) {
				throw new Bad(n, "'" + part.trim() + "' is not key=value");
			}
			out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
		}
		if (out.isEmpty()) {
			throw new Bad(0, "no key=value pairs");
		}
		return out;
	}

	/** Applies every pair to this (all or nothing). Throws Bad naming the first bad pair (1-based). */
	public ServerRules withAll(Map<String, String> pairs) throws Bad {
		ServerRules r = this;
		int n = 0;
		for (Map.Entry<String, String> e : pairs.entrySet()) {
			n++;
			try {
				r = r.with(e.getKey(), e.getValue());
			} catch (IllegalArgumentException ex) {
				throw new Bad(n, ex.getMessage());
			}
		}
		return r;
	}

	/** A refused admin request: which pair (1-based; 0: the whole text) and why. */
	public static final class Bad extends Exception {
		public final int pair;

		public Bad(int pair, String why) {
			super(why);
			this.pair = pair;
		}
	}

	private static final Pattern KEY_LINE = Pattern.compile("^\\s*([^#!=:\\s][^=:\\s]*)\\s*[=:\\s].*$|^\\s*([^#!=:\\s][^=:\\s]*)\\s*$");

	/**
	 * The properties file text with these keys set: a key's existing line is replaced (the last one
	 * wins in Properties, so every line of it is), missing keys are appended; comments and other keys
	 * (the client's destruction=, join=) are kept as they are.
	 */
	public static String rewrite(String text, Map<String, String> set) {
		List<String> lines = new ArrayList<>(text == null || text.isEmpty() ? List.of() : List.of(text.split("\\R", -1)));
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		Set<String> done = new LinkedHashSet<>();
		for (int i = 0; i < lines.size(); i++) {
			var m = KEY_LINE.matcher(lines.get(i));
			if (!m.matches()) {
				continue;
			}
			String k = m.group(1) != null ? m.group(1) : m.group(2);
			if (set.containsKey(k)) {
				lines.set(i, k + "=" + set.get(k));
				done.add(k);
			}
		}
		for (Map.Entry<String, String> e : set.entrySet()) {
			if (!done.contains(e.getKey())) {
				lines.add(e.getKey() + "=" + e.getValue());
			}
		}
		return String.join("\n", lines) + "\n";
	}
}
