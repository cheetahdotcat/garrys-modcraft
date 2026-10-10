package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The Java mirror of the protocol must equal protocol/gmodcraft_protocol.h, constant for constant.
 * The header's own static_asserts tie those constants to the real struct layouts (offsetof /
 * sizeof), so header == Proto here means Proto matches what a C++ host actually lays out.
 */
class ProtoLayoutTest {
	private static final Pattern CONSTEXPR = Pattern.compile("^\\s*inline\\s+constexpr\\s+(?:std::)?(\\w+)\\s+(k\\w+)\\s*=\\s*([^;]+);");
	private static final Pattern ENUM_START = Pattern.compile("^\\s*enum\\s+(\\w+)\\s*:\\s*std::\\w+");
	// Every "kName = value" inside an enum body (any number per line; enum values must be explicit).
	private static final Pattern ENUM_VALUE = Pattern.compile("\\b(k\\w+)\\s*=\\s*([^,}]+)");

	static Path header() {
		String prop = System.getProperty("gmodcraft.protocolHeader");
		Path p = prop != null ? Path.of(prop) : Path.of("..", "protocol", "gmodcraft_protocol.h");
		assertTrue(Files.isRegularFile(p), "protocol header not found at " + p.toAbsolutePath());
		return p;
	}

	/** kClOffMcState -> CL_OFF_MC_STATE (the one naming rule between the header and Proto). */
	static String javaName(String headerName) {
		String s = headerName.substring(1);
		s = s.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_");
		s = s.replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", "_");
		return s.toUpperCase(java.util.Locale.ROOT);
	}

	/** A plain C++ literal: 0x1F, 12, 12u, 0x40ull, 40.0, -1.0e30f. Anything else is rejected. */
	static Number literal(String text, String where) {
		String t = text.trim();
		if (t.matches("-?0[xX][0-9a-fA-F]+[uUlL]*")) {
			boolean neg = t.startsWith("-");
			String hex = t.replaceAll("[uUlL]+$", "").replaceFirst("-?0[xX]", "");
			long v = Long.parseUnsignedLong(hex, 16);
			return neg ? -v : v;
		}
		if (t.matches("-?[0-9]+[uUlL]*")) {
			return Long.parseLong(t.replaceAll("[uUlL]+$", ""));
		}
		if (t.matches("-?[0-9]+\\.[0-9]*(?:[eE][-+]?[0-9]+)?[fF]?")) {
			return Double.parseDouble(t.replaceAll("[fF]$", ""));
		}
		fail(where + ": not a plain literal: '" + t + "' (write derived values as literals and static_assert them)");
		return null;
	}

	// Any "kName =" at all, wherever it appears (outside comments): what the parser must account for.
	private static final Pattern ANY_ASSIGNMENT = Pattern.compile("\\b(k\\w+)\\s*=(?!=)");

	/** C++ type of each constexpr constant, for the width check (enum values are left out). */
	static final Map<String, String> TYPES = new LinkedHashMap<>();

	static Map<String, Number> parseHeader(List<String> lines) {
		Map<String, Number> out = new LinkedHashMap<>();
		TYPES.clear();
		boolean inEnum = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).replaceAll("//.*$", "");
			String where = "gmodcraft_protocol.h:" + (i + 1);
			Matcher m;
			if ((m = CONSTEXPR.matcher(line)).find()) {
				put(out, m.group(2), literal(m.group(3), where), where);
				TYPES.put(m.group(2), m.group(1));
			} else if (ENUM_START.matcher(line).find()) {
				inEnum = true;
			} else if (inEnum && line.trim().startsWith("};")) {
				inEnum = false;
			} else if (inEnum) {
				m = ENUM_VALUE.matcher(line);
				while (m.find()) {
					put(out, m.group(1), literal(m.group(2), where), where);
				}
			}
		}
		return out;
	}

	private static void put(Map<String, Number> out, String name, Number value, String where) {
		if (out.put(name, value) != null) {
			fail(where + ": " + name + " defined twice");
		}
	}

	static Map<String, Number> javaConstants() throws IllegalAccessException {
		Map<String, Number> out = new TreeMap<>();
		for (Field f : Proto.class.getDeclaredFields()) {
			int mod = f.getModifiers();
			if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || !Modifier.isPublic(mod)) {
				continue;
			}
			Object v = f.get(null);
			if (v instanceof Number n) {
				out.put(f.getName(), n);
			}
		}
		return out;
	}

	/**
	 * Every "kName =" in the header (outside comments) must have been parsed: a constant written
	 * in a form the parser skips (a multi-line enum value, a constexpr with an unusual type
	 * spelling, ...) would otherwise silently escape the parity check.
	 */
	@Test
	void everyAssignmentInTheHeaderWasParsed() throws IOException {
		List<String> lines = Files.readAllLines(header());
		Map<String, Number> parsed = parseHeader(lines);
		List<String> missed = new ArrayList<>();
		int total = 0;
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = ANY_ASSIGNMENT.matcher(lines.get(i).replaceAll("//.*$", ""));
			while (m.find()) {
				total++;
				if (!parsed.containsKey(m.group(1))) {
					missed.add("gmodcraft_protocol.h:" + (i + 1) + " " + m.group(1));
				}
			}
		}
		assertTrue(missed.isEmpty(), "constants the parser skipped:\n  " + String.join("\n  ", missed));
		assertEquals(total, parsed.size(), "every kName = in the header is exactly one parsed constant");
	}

	/** Java field types follow the C++ widths: u64 -> long, double -> double, float -> float, else int. */
	@Test
	void javaFieldWidthsMatchTheHeaderTypes() throws Exception {
		parseHeader(Files.readAllLines(header()));
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> e : TYPES.entrySet()) {
			Class<?> want = switch (e.getValue()) {
				case "uint64_t", "int64_t" -> long.class;
				case "double" -> double.class;
				case "float" -> float.class;
				case "uint32_t", "int32_t", "uint16_t", "int16_t", "uint8_t", "int8_t" -> int.class;
				default -> null;
			};
			if (want == null) {
				problems.add(e.getKey() + ": unexpected C++ type " + e.getValue());
				continue;
			}
			Field f;
			try {
				f = Proto.class.getField(javaName(e.getKey()));
			} catch (NoSuchFieldException x) {
				continue; // reported by everyHeaderConstantMatchesProtoAndViceVersa
			}
			if (f.getType() != want) {
				problems.add(javaName(e.getKey()) + " is " + f.getType() + ", header " + e.getKey() + " is " + e.getValue() + " (want " + want + ")");
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n  ", problems));
	}

	@Test
	void everyHeaderConstantMatchesProtoAndViceVersa() throws IOException, IllegalAccessException {
		Map<String, Number> header = parseHeader(Files.readAllLines(header()));
		Map<String, Number> java = javaConstants();
		assertTrue(header.size() > 200, "suspiciously few header constants parsed: " + header.size());

		List<String> problems = new ArrayList<>();
		Map<String, String> javaToHeader = new LinkedHashMap<>();
		for (Map.Entry<String, Number> e : header.entrySet()) {
			String jn = javaName(e.getKey());
			String clash = javaToHeader.put(jn, e.getKey());
			if (clash != null) {
				problems.add(e.getKey() + " and " + clash + " both map to " + jn);
			}
			Number jv = java.get(jn);
			if (jv == null) {
				problems.add("missing in Proto: " + jn + " (header " + e.getKey() + " = " + e.getValue() + ")");
			} else if (!same(e.getValue(), jv)) {
				problems.add("mismatch " + jn + ": header " + e.getValue() + ", Proto " + jv);
			}
		}
		for (String jn : java.keySet()) {
			if (!javaToHeader.containsKey(jn)) {
				problems.add("Proto." + jn + " has no counterpart in the header");
			}
		}
		if (!problems.isEmpty()) {
			fail(problems.size() + " protocol mismatches:\n  " + String.join("\n  ", problems));
		}
	}

	private static boolean same(Number header, Number java) {
		if (header instanceof Double || java instanceof Double || java instanceof Float) {
			// float constants are compared at float precision (kNoWater is a float in C++ too)
			return java instanceof Float ? (float) header.doubleValue() == java.floatValue() : header.doubleValue() == java.doubleValue();
		}
		return header.longValue() == java.longValue();
	}

	/** The layout facts the Java code relies on, restated (cheap, and documents intent). */
	@Test
	void regionsAreOrderedAndInsideTheirMappings() {
		long[][] client = {
			{ Proto.CL_OFF_HEADER, Proto.LINK_HEADER_BYTES },
			{ Proto.CL_OFF_HOST_STATE, Proto.HOST_STATE_BYTES },
			{ Proto.CL_OFF_MC_STATE, Proto.MC_STATE_BYTES },
			{ Proto.CL_OFF_OVERLAY_CTL, 0x10 },
			{ Proto.CL_OFF_OVERLAY_SLOT_HDR, Proto.SLOT_HDR_BYTES * Proto.OVERLAY_SLOTS },
			{ Proto.CL_OFF_WATER_GRID, Proto.WATER_GRID_BYTES },
			{ Proto.CL_OFF_MC_IDENTITY, Proto.MC_IDENTITY_BYTES },
			{ Proto.CL_OFF_LINK_STATS, Proto.LINK_STATS_BYTES },
			{ Proto.CL_OFF_INPUT_RING, Proto.INPUT_RING_BYTES },
			{ Proto.CL_OFF_ACTOR_TABLE, Proto.ACTOR_TABLE_BYTES },
			{ Proto.CL_OFF_WORLD_ENTITIES, Proto.WORLD_ENTITIES_BYTES },
			{ Proto.CL_OFF_JOIN_INFO, Proto.JOIN_INFO_BYTES },
			{ Proto.CL_OFF_JOIN_STATUS, Proto.JOIN_STATUS_BYTES },
			{ Proto.CL_OFF_EVENT_RING, Proto.CL_EVENT_RING_BYTES },
			{ Proto.CL_OFF_MC_SCREEN, Proto.MC_SCREEN_BYTES },
			{ Proto.CL_OFF_MC_SKY, Proto.MC_SKY_BYTES },
			{ Proto.CL_OFF_COLLISION_RING, Proto.CL_COLLISION_RING_BYTES },
			{ Proto.CL_OFF_OVERLAY_PIXELS, Proto.OVERLAY_SLOT_BYTES * Proto.OVERLAY_SLOTS },
			{ Proto.CL_OFF_RENDER_RING, Proto.RENDER_RING_BYTES },
		};
		checkRegions("client", client, Proto.CL_MAPPING_BYTES);
		long[][] server = {
			{ Proto.SV_OFF_HEADER, Proto.LINK_HEADER_BYTES },
			{ Proto.SV_OFF_SERVER_STATE, Proto.SERVER_STATE_BYTES },
			{ Proto.SV_OFF_MC_SERVER_STATE, Proto.MC_SERVER_STATE_BYTES },
			{ Proto.SV_OFF_WATER_GRID, Proto.WATER_GRID_BYTES },
			{ Proto.SV_OFF_LINK_STATS, Proto.LINK_STATS_BYTES },
			{ Proto.SV_OFF_HOST_EVENT_RING, Proto.HOST_EVENT_RING_BYTES },
			{ Proto.SV_OFF_HOST_PLAYERS, Proto.HOST_PLAYERS_BYTES },
			{ Proto.SV_OFF_MC_PLAYERS, Proto.MC_PLAYERS_BYTES },
			{ Proto.SV_OFF_ACTOR_TABLE, Proto.ACTOR_TABLE_BYTES },
			{ Proto.SV_OFF_EVENT_RING, Proto.EVENT_RING_BYTES },
			{ Proto.SV_OFF_MC_SERVER_INFO, Proto.MC_SERVER_INFO_BYTES },
			{ Proto.SV_OFF_HELD_MC_ENTITIES, Proto.HELD_MC_ENTITIES_BYTES },
			{ Proto.SV_OFF_MC_WEAPON_SETS, Proto.MC_WEAPON_SETS_BYTES },
			{ Proto.SV_OFF_MC_ENTITIES, Proto.MC_ENTITIES_BYTES },
			{ Proto.SV_OFF_BLOCK_RING, Proto.SV_BLOCK_RING_BYTES },
			{ Proto.SV_OFF_COLLISION_RING, Proto.SV_COLLISION_RING_BYTES },
			{ Proto.SV_OFF_WATER_GRIDS, Proto.SV_WATER_GRIDS_BYTES },
		};
		assertTrue(Proto.SV_WATER_GRIDS_BYTES >= Proto.WATER_GRID_BYTES * Proto.MAX_PLAYERS, "a water grid for every player slot");
		// v17: a weapon set per McPlayers slot; the host event text holds a whole class name.
		assertEquals(Proto.WTS_SETS + Proto.MC_WEAPON_SET_BYTES * Proto.MAX_PLAYERS, Proto.MC_WEAPON_SETS_BYTES);
		assertEquals(Proto.WS_ENTRIES + Proto.WEAPON_ENTRY_BYTES * Proto.MAX_WEAPONS_PER_PLAYER, Proto.MC_WEAPON_SET_BYTES);
		assertEquals(Proto.HOST_EVENT_BYTES, Proto.HE_TEXT + Proto.WEAPON_TEXT_CHUNK_BYTES);
		assertTrue(Proto.WEAPON_CLASS_MAX_BYTES < Proto.WEAPON_TEXT_MAX_BYTES);
		assertEquals(Proto.EVENT_RING_BYTES - Proto.ER_DATA, Proto.MC_EVENT_BYTES * Proto.EVENT_RING_ENTRIES);
		assertEquals(Proto.CL_EVENT_RING_BYTES - Proto.ER_DATA, Proto.MC_EVENT_BYTES * Proto.CL_EVENT_RING_ENTRIES);
		checkRegions("server", server, Proto.SV_MAPPING_BYTES);
		assertEquals(0x52434D47, Proto.MAGIC, "magic is \"GMCR\" read as a little-endian u32");
		assertEquals((long) Proto.MAX_OVERLAY_W * Proto.MAX_OVERLAY_H * 4, Proto.OVERLAY_SLOT_BYTES);
	}

	private static void checkRegions(String link, long[][] regions, long mappingBytes) {
		long end = 0;
		for (long[] r : regions) {
			assertTrue(r[0] >= end, link + " region at 0x" + Long.toHexString(r[0]) + " overlaps the previous one (ends 0x" + Long.toHexString(end) + ")");
			end = r[0] + r[1];
		}
		assertEquals(mappingBytes, end, link + " mapping size == end of its last region");
	}

	/** If a C++ compiler is around, the header's static_asserts run as part of the build too. */
	@Test
	void headerCompilesWithItsStaticAsserts() throws Exception {
		Path h = header().toAbsolutePath();
		Process p;
		try {
			p = new ProcessBuilder("g++", "-std=c++20", "-Wall", "-Wextra", "-Wno-pragma-once-outside-header", "-fsyntax-only", "-x", "c++", h.toString()).redirectErrorStream(true).start();
		} catch (IOException e) {
			Assumptions.abort("g++ not available: " + e.getMessage());
			return;
		}
		assertTrue(p.waitFor(60, TimeUnit.SECONDS), "g++ timed out");
		String output = new String(p.getInputStream().readAllBytes());
		assertEquals(0, p.exitValue(), "g++ rejected the protocol header:\n" + output);
	}
}
