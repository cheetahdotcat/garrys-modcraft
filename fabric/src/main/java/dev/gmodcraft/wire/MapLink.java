package dev.gmodcraft.wire;

import dev.gmodcraft.link.Proto;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * R2 (protocol v25): a redstone bridge's link to a GMod map entity, stored with the block (block
 * entity NBT): the map (worldId and name), the entity (MapCreationID, and its targetname for
 * people), its kind and the signal's direction. Pure, so it is unit-tested without Minecraft.
 */
public record MapLink(int worldId, String map, int creationId, String name, int kind, int mode) {
	static final Map<String, Integer> KINDS = Map.of("button", Proto.LINK_BUTTON, "momentary", Proto.LINK_MOMENTARY, "trigger", Proto.LINK_TRIGGER,
		"door", Proto.LINK_DOOR, "movelinear", Proto.LINK_MOVE_LINEAR, "relay", Proto.LINK_RELAY, "light", Proto.LINK_LIGHT, "sprite", Proto.LINK_SPRITE);
	static final Map<String, Integer> MODES = Map.of("in", Proto.LINK_IN, "out", Proto.LINK_OUT);

	/** Which kinds work in which direction (in: the entity drives redstone; out: redstone drives it). */
	static boolean supports(int kind, int mode) {
		if (mode == Proto.LINK_IN) {
			return kind == Proto.LINK_BUTTON || kind == Proto.LINK_MOMENTARY || kind == Proto.LINK_TRIGGER || kind == Proto.LINK_DOOR || kind == Proto.LINK_RELAY;
		}
		if (mode == Proto.LINK_OUT) {
			return kind == Proto.LINK_BUTTON || kind == Proto.LINK_DOOR || kind == Proto.LINK_MOVE_LINEAR || kind == Proto.LINK_LIGHT || kind == Proto.LINK_SPRITE;
		}
		return false;
	}

	/**
	 * Parses kAdminBridgeLink's text slot "<kind> <mode> [targetname]" for map {@code worldId} /
	 * {@code map}. Null when malformed or the kind can't go that way.
	 */
	public static @Nullable MapLink parse(String text, int worldId, String map, int creationId) {
		if (text == null || creationId < 0) {
			return null;
		}
		String[] p = text.trim().split("\\s+", 3);
		if (p.length < 2) {
			return null;
		}
		Integer kind = KINDS.get(p[0].toLowerCase(Locale.ROOT)), mode = MODES.get(p[1].toLowerCase(Locale.ROOT));
		if (kind == null || mode == null || !supports(kind, mode)) {
			return null;
		}
		String name = p.length > 2 ? p[2].replaceAll("[^A-Za-z0-9_.*-]", "") : "";
		return new MapLink(worldId, map == null ? "" : map, creationId, name.length() > 48 ? name.substring(0, 48) : name, kind, mode);
	}

	/** kEvBridgeLink's flags: kind | mode << 8. */
	public int flags() {
		return this.kind | this.mode << 8;
	}
}
