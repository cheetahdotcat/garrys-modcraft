package dev.gmodcraft;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.world.MapSlots;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * v24 (P8 WP3) admin commands of the GMod "Server" page: kAdminSetRules (the server rules, live and
 * into config/gmodcraft.properties) and kAdminSlotHistory (the current slot's re-anchor history,
 * one entry per request). v34 (control centre): kAdminSlotInfo (any map's slot, for the Maps page),
 * kAdminWorldList / kAdminWorldOp (the World page: backups, a new world or a restore scheduled for
 * the next start, WorldOps). Admins only (kAdminByAdmin). Server thread.
 */
public final class ServerAdmin {
	private ServerAdmin() {
	}

	public static boolean handles(int code) {
		return code == Proto.ADMIN_SET_RULES || code == Proto.ADMIN_SLOT_HISTORY || code == Proto.ADMIN_SLOT_INFO || code == Proto.ADMIN_WORLD_LIST
			|| code == Proto.ADMIN_WORLD_OP;
	}

	/** Answers cmd (one kEvAdminResult) and logs it. */
	public static void run(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		if ((cmd.flags() & Proto.ADMIN_BY_ADMIN) == 0) {
			reply(cmd, Proto.ADMIN_NOT_ALLOWED, 0, 0, 0, 0, 0, 0, "server commands are for admins");
			return;
		}
		switch (cmd.code()) {
			case Proto.ADMIN_SET_RULES -> setRules(server, cmd, text);
			case Proto.ADMIN_SLOT_INFO -> slotInfo(server, cmd, text);
			case Proto.ADMIN_WORLD_LIST -> worldList(server, cmd);
			case Proto.ADMIN_WORLD_OP -> worldOp(server, cmd, text);
			default -> history(server, cmd);
		}
	}

	/** kAdminSlotInfo: a map's slot (the Maps page asks map by map; not logged: one line per map would flood the log). */
	private static void slotInfo(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		String map = text == null ? "" : text.trim();
		MapSlots.Slot s = map.isEmpty() ? null : MapSlots.byName(server, map);
		int result = s == null ? Proto.ADMIN_NOTHING : Proto.ADMIN_OK;
		ServerLink.INSTANCE.pushEvent(Proto.EV_ADMIN_RESULT, 0, cmd.steamId(), s == null ? 0 : s.slotX(), s == null ? 0 : s.slotZ(),
			s == null ? 0 : s.oyUnits(), s == null ? 0 : s.anchorSrc(), s != null && s.map().equalsIgnoreCase(ServerHost.currentMap()) ? 1 : 0, 0,
			cmd.requestId(), result);
	}

	// The world list as last read (sizes walk the folders: kept a few seconds for the entry-by-entry fetch).
	private static List<WorldOps.Entry> worlds = List.of();
	private static long worldsAtNs;

	private static List<WorldOps.Entry> worlds(MinecraftServer server, boolean fresh) throws IOException {
		long now = System.nanoTime();
		if (fresh || worlds.isEmpty() || now - worldsAtNs > 10_000_000_000L) {
			worlds = WorldOps.list(server.getWorldPath(LevelResource.ROOT), true);
			worldsAtNs = now;
		}
		return worlds;
	}

	private static void worldList(MinecraftServer server, ServerLink.HostEvent cmd) {
		if (!server.isDedicatedServer()) {
			reply(cmd, Proto.ADMIN_UNSUPPORTED, 0, 0, 0, 0, 0, 0, "world list: dedicated servers only (single player: the launcher's New world)");
			return;
		}
		int index = cmd.a();
		List<WorldOps.Entry> list;
		try {
			list = worlds(server, index == 0);
		} catch (IOException e) {
			reply(cmd, Proto.ADMIN_FAILED, 0, 0, 0, 0, 0, 0, "world list: " + e.getMessage());
			return;
		}
		if (index < 0 || index >= list.size()) {
			quiet(cmd, Proto.ADMIN_NOTHING, 0, 0, 0, 0, list.size(), index);
			return;
		}
		WorldOps.Entry e = list.get(index);
		int mib = (int) Math.min(Integer.MAX_VALUE, (e.bytes() + (1 << 19)) >> 20);
		if (index == 0) {
			WorldOps.Op op = WorldOps.pending(server.getServerDirectory());
			int what = -1;
			if (op != null && op.op() == Proto.WORLD_OP_NEW) {
				what = ServerRules.WORLD_TYPES.indexOf(op.arg());
			} else if (op != null) {
				for (int i = 1; i < list.size(); i++) {
					if (list.get(i).stamp().equals(op.arg())) {
						what = i;
					}
				}
			}
			quiet(cmd, Proto.ADMIN_OK, op == null ? Proto.WORLD_OP_NONE : op.op(), what, mib, Proto.WORLD_ENTRY_RUNNING, list.size(), 0);
		} else {
			int[] ds = e.dayAndSecond();
			quiet(cmd, Proto.ADMIN_OK, ds[0], ds[1], mib, Proto.WORLD_ENTRY_BACKUP, list.size(), index);
		}
	}

	/** kAdminWorldOp: schedule (or cancel) a new world / a restore for the next start; with kAdminRestartNow and a supervisor, stop now. */
	private static void worldOp(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		if (!server.isDedicatedServer()) {
			reply(cmd, Proto.ADMIN_UNSUPPORTED, 0, 0, 0, 0, 0, 0, "world operations: dedicated servers only (single player: the launcher's New world)");
			return;
		}
		java.nio.file.Path dir = server.getServerDirectory();
		try {
			if (cmd.a() == Proto.WORLD_OP_CANCEL) {
				WorldOps.schedule(dir, null);
				reply(cmd, Proto.ADMIN_OK, 0, 0, 0, 0, 0, 0, "world operation cancelled");
				return;
			}
			WorldOps.Op op;
			try {
				op = WorldOps.check(cmd.a(), text, WorldOps.list(server.getWorldPath(LevelResource.ROOT), false));  // no sizes: just the names
			} catch (IllegalArgumentException e) {
				reply(cmd, Proto.ADMIN_MALFORMED, 0, 0, 0, 0, 0, 0, "world operation refused: " + e.getMessage());
				return;
			}
			WorldOps.schedule(dir, op);
			boolean now = (cmd.flags() & Proto.ADMIN_RESTART_NOW) != 0 && WorldOps.supervised();
			if (now) {
				WorldOps.requestRestart(dir);
			}
			reply(cmd, Proto.ADMIN_OK, now ? 1 : 0, 0, 0, 0, 0, 0, "world operation scheduled: " + op.line() + (now ? "; the server restarts now"
				: "; applied when tools/run_mc_server.sh next starts the server"));
			if (now) {
				server.execute(() -> server.halt(false));  // the answer is in the ring already; the stop saves the world
			}
		} catch (IOException e) {
			reply(cmd, Proto.ADMIN_FAILED, 0, 0, 0, 0, 0, 0, "world operation: couldn't write in " + dir + ": " + e.getMessage());
		}
	}

	private static void quiet(ServerLink.HostEvent cmd, int result, int a, int b, int c, int d, int flags, int weapon) {
		ServerLink.INSTANCE.pushEvent(Proto.EV_ADMIN_RESULT, 0, cmd.steamId(), a, b, c, d, flags, weapon, cmd.requestId(), result);
	}

	/** A staged kAdminSetRules change: the pairs of its texts so far (kAdminStaged), per admin. */
	private record Stage(int change, java.util.LinkedHashMap<String, String> pairs, long atNs) {
	}

	private static final java.util.Map<Long, Stage> STAGES = new java.util.HashMap<>();

	private static void setRules(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		long now = System.nanoTime();
		STAGES.values().removeIf(s -> now - s.atNs() > Proto.RULES_STAGE_MS * 1_000_000L);  // expired: dropped
		boolean staged = (cmd.flags() & Proto.ADMIN_STAGED) != 0;
		int change = cmd.a();
		if (staged && change == 0) {
			// 0 means a one-text change: a staged part needs the change's id
			reply(cmd, Proto.ADMIN_MALFORMED, 0, 0, 0, 0, 0, 0, "a staged rules text needs a change id (a); nothing changed");
			return;
		}
		Stage stage = STAGES.get(cmd.steamId());
		if (stage != null && stage.change() != change) {
			STAGES.remove(cmd.steamId());  // a text of another change: the old one is dropped
			stage = null;
		}
		java.util.LinkedHashMap<String, String> pairs = new java.util.LinkedHashMap<>(stage != null ? stage.pairs() : java.util.Map.of());
		if (stage == null && change != 0 && !staged) {
			// the final text of a staged change whose earlier texts aren't here (expired, or another change came between)
			reply(cmd, Proto.ADMIN_MALFORMED, 0, 0, 0, 0, 0, 0, "change " + change + ": its earlier texts aren't buffered (expired?); nothing changed");
			return;
		}
		java.util.List<String> textKeys = new java.util.ArrayList<>();
		ServerRules next;
		try {
			java.util.Map<String, String> mine = ServerRules.parsePairs(text);
			textKeys.addAll(mine.keySet());
			pairs.putAll(mine);
			GmodCraftConfig.rules().withAll(pairs);  // the whole set so far is checked before anything is kept
			if (staged) {
				STAGES.put(cmd.steamId(), new Stage(change, pairs, now));
				reply(cmd, Proto.ADMIN_OK, pairs.size(), 0, 0, 0, Proto.ADMIN_STAGED, 0, "rules staged (" + pairs.size() + " pairs so far): " + text);
				return;
			}
			STAGES.remove(cmd.steamId());
			next = GmodCraftConfig.change(pairs);
		} catch (ServerRules.Bad e) {
			STAGES.remove(cmd.steamId());
			// e.pair counts in the whole set: report it within this text (0: the staged part's, not this text's)
			String badKey = e.pair >= 1 && e.pair <= pairs.size() ? new java.util.ArrayList<>(pairs.keySet()).get(e.pair - 1) : null;
			int pair = stage == null ? e.pair : badKey == null ? 0 : textKeys.indexOf(badKey) + 1;
			reply(cmd, Proto.ADMIN_BAD_RULE, pair, 0, 0, 0, 0, 0, "refused '" + text + "'" + (stage != null ? " (with the staged part)" : "") + ": "
				+ e.getMessage() + "; nothing changed");
			return;
		} catch (IOException e) {
			STAGES.remove(cmd.steamId());
			GmodCraft.LOG.error("GmodCraft: couldn't write {}", GmodCraftConfig.file(), e);
			reply(cmd, Proto.ADMIN_FAILED, 0, 0, 0, 0, 0, 0, "couldn't write " + GmodCraftConfig.file() + ": " + e.getMessage() + " (nothing changed)");
			return;
		}
		GmodCraft.applyRules(server, next, true);
		ServerHost.reanswerSlot();
		reply(cmd, Proto.ADMIN_OK, pairs.size(), 0, 0, 0, 0, 0, "rules set: " + String.join(";", pairs.keySet()));
	}

	private static void history(MinecraftServer server, ServerLink.HostEvent cmd) {
		String map = ServerHost.currentMap();
		MapSlots.Lookup l = map.isEmpty() ? null : MapSlots.find(server, map, ServerHost.worldId());
		if (l == null) {
			reply(cmd, Proto.ADMIN_OUTSIDE_SLOT, 0, 0, 0, 0, 0, 0, "no slot for the current map");
			return;
		}
		List<MapSlots.Reanchor> h = l.slot().reanchors();
		int index = cmd.a();
		if (index < 0 || index >= h.size()) {
			reply(cmd, Proto.ADMIN_NOTHING, 0, 0, 0, 0, h.size(), index, "no history entry " + index + " (" + h.size() + ")");
			return;
		}
		MapSlots.Reanchor r = h.get(h.size() - 1 - index);  // 0: the newest
		int undoneBy = 0;
		for (MapSlots.Reanchor o : h) {
			if (o.undoOf() == r.id()) {
				undoneBy = o.id();
			}
		}
		reply(cmd, Proto.ADMIN_OK, r.id(), r.dyUnits(), r.undoOf(), undoneBy, h.size(), index,
			"history " + index + ": job " + r.id() + " dy " + r.dyUnits() + (r.undoOf() != 0 ? " (undid " + r.undoOf() + ")" : "")
				+ (undoneBy != 0 ? " (undone by " + undoneBy + ")" : ""));
	}

	private static void reply(ServerLink.HostEvent cmd, int result, int a, int b, int c, int d, int flags, int weapon, String message) {
		GmodCraft.LOG.info("GmodCraft: admin command {} ({}) from {}: {} - {}", cmd.requestId(), cmd.code(), Long.toUnsignedString(cmd.steamId()), result,
			message);
		ServerLink.INSTANCE.pushEvent(Proto.EV_ADMIN_RESULT, 0, cmd.steamId(), a, b, c, d, flags, weapon, cmd.requestId(), result);
	}
}
