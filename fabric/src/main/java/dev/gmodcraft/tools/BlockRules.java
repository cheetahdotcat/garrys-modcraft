package dev.gmodcraft.tools;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Block Tool (P7b-2): the first, pure check of a block state string from GMod, before Minecraft
 * parses it (which checks again: the fluid state, GameMasterBlock). Liquids only when the admin
 * allowed them; operator-only blocks never.
 */
public final class BlockRules {
	public enum Verdict { OK, MALFORMED, LIQUID, OPERATOR, RESTRICTED }

	private static final Pattern FORM = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?");
	private static final Set<String> OPERATOR = Set.of("command_block", "chain_command_block", "repeating_command_block", "structure_block",
		"structure_void", "jigsaw", "barrier", "test_block", "test_instance_block", "light");
	private static final Set<String> LIQUID = Set.of("water", "lava", "bubble_column");

	private BlockRules() {
	}

	/** The id's path ("minecraft:oak_log[axis=y]" -> "oak_log"). */
	static String path(String state) {
		String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
		return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
	}

	/** Blocks a non-admin may neither place nor break (the tool is open to everyone by convar). */
	public static final Set<String> EVERYONE_DENY = Set.of("bedrock", "end_portal", "end_gateway", "nether_portal", "fire", "soul_fire", "spawner", "tnt",
		"barrier", "light", "structure_void", "end_portal_frame", "reinforced_deepslate", "trial_spawner", "vault");

	/** {@link #check(String, boolean)}, plus: a non-admin ({@code everyone}) never gets the EVERYONE_DENY blocks. */
	public static Verdict check(String state, boolean allowLiquid, boolean everyone) {
		Verdict v = check(state, allowLiquid);
		if (v == Verdict.OK && everyone && EVERYONE_DENY.contains(path(state.trim().toLowerCase(Locale.ROOT)))) {
			return Verdict.RESTRICTED;
		}
		return v;
	}

	/** Whether a non-admin may break a block with this id path. */
	public static boolean everyoneMayBreak(String idPath) {
		return !EVERYONE_DENY.contains(idPath) && !OPERATOR.contains(idPath);
	}

	public static Verdict check(String state, boolean allowLiquid) {
		String s = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty() || s.length() > 60 || !FORM.matcher(s).matches()) {
			return Verdict.MALFORMED;
		}
		String p = path(s);
		if (OPERATOR.contains(p)) {
			return Verdict.OPERATOR;
		}
		if (!allowLiquid && (LIQUID.contains(p) || s.contains("waterlogged=true"))) {
			return Verdict.LIQUID;
		}
		return Verdict.OK;
	}
}
