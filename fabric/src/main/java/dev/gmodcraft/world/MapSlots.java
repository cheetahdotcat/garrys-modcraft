package dev.gmodcraft.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The MC server's map table (D-003, docs/DESIGN.md section 3): every GMod map gets its own slot on
 * a 2048-block grid (Proto.SLOT_BLOCKS) in the one mirror dimension, allocated the first time the
 * map is seen and never forgotten, so a map's builds stay where they were across restarts and map
 * changes, and two maps never share a slot.
 *
 * <p>Stored as {@code <world>/data/gmodcraft/map_slots.json}, written (tmp file + atomic rename)
 * the moment a slot is allocated: it must not depend on the world being saved cleanly (a killed
 * Minecraft would otherwise forget a slot it already handed out). Keyed by the lowercased map name
 * when the host gives one, else by worldId (the FNV-1a hash of that name). Server thread only.
 *
 * <p>v2 (P8 WP1, protocol v21): each slot also has a vertical offset {@code oyUnits} (Source units:
 * mc.y = (src.z + oyUnits) / 40, applied only by the host), the floor it was chosen for, where that
 * came from and the map's footprint; the file has the target floor y ({@code floorY}). A v1 file's
 * slots read as oyUnits 0 (anchorSrc 0 = from before v21) and keep it; the file is written as v2 the
 * next time a slot is allocated.
 */
public final class MapSlots {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private MapSlots() {
	}

	/**
	 * One map's slot. oyUnits: the vertical offset (Source units); floorZ: the host floor it puts on
	 * floorY; anchorSrc: Proto.AnchorSource; footprint: the map's xy box
	 * {minX, minY, maxX, maxY} in Source units when known (else null). A v1 entry reads as
	 * kAnchorLegacy with oyUnits 0. history (P8 WP2): every re-anchor of the slot, oldest first (null
	 * when there was none).
	 */
	public record Slot(String map, int worldId, int slotX, int slotZ, int oyUnits, float floorZ, int anchorSrc, @Nullable List<Float> footprint,
		@Nullable List<Reanchor> history) {
		/** A slot without a vertical offset (v1, and tests). */
		public Slot(String map, int worldId, int slotX, int slotZ) {
			this(map, worldId, slotX, slotZ, 0, 0.0F, 0, null, null);
		}

		public Slot(String map, int worldId, int slotX, int slotZ, int oyUnits, float floorZ, int anchorSrc, @Nullable List<Float> footprint) {
			this(map, worldId, slotX, slotZ, oyUnits, floorZ, anchorSrc, footprint, null);
		}

		/** The re-anchors so far (never null). */
		public List<Reanchor> reanchors() {
			return this.history == null ? List.of() : this.history;
		}

		/** Whole blocks the content has moved in total (re-anchors 0 .. n - 1). */
		public int blocksMoved(int n) {
			int b = 0;
			List<Reanchor> h = reanchors();
			for (int i = 0; i < Math.min(n, h.size()); i++) {
				b += h.get(i).blocks();
			}
			return b;
		}

		/** The origin's x / z in blocks. */
		public int originX() {
			return this.slotX * Proto.SLOT_BLOCKS;
		}

		public int originZ() {
			return this.slotZ * Proto.SLOT_BLOCKS;
		}
	}

	/**
	 * One re-anchor (P8 WP2): job id, when (ms), the offset change in Source units, the whole blocks the
	 * content moved, the offset before, and the job it undid (0: none).
	 */
	public record Reanchor(int id, long atMs, int dyUnits, int blocks, int oyBefore, int undoOf) {
	}

	/** A new slot's offset, as the policy chose it ({@link #offsetFor}). */
	public record Anchor(int oyUnits, float floorZ, int source, @Nullable List<Float> footprint) {
		public static final Anchor NONE = new Anchor(0, 0.0F, Proto.ANCHOR_NONE, null);
	}

	/** The answer for one lookup: the slot, whether it was allocated just now, and the table size. */
	public record Lookup(Slot slot, boolean isNew, int count) {
	}

	private static final class FileFormat {
		int version;  // 0 when absent: read as v1 (format() writes 2)
		int floorY = Proto.ANCHOR_FLOOR_Y;  // v2: the MC y new maps' floors land on (T). v24 (P8 WP3): fixed when the table is
		                                    // made (the world's flat generator, else the config's floorY) and used for every new slot
		List<Slot> slots = new ArrayList<>();
	}

	// The mirror dimension's vertical range (data/gmodcraft/dimension_type/mirror.json: min_y -1024,
	// height 2048) and the vanilla range outside which a warning is logged.
	static final int DIM_MIN_Y = -1024, DIM_MAX_Y = 1024;
	static final int VANILLA_MIN_Y = -64, VANILLA_MAX_Y = 320;
	static final int UNITS = (int) Proto.UNITS_PER_BLOCK;

	/**
	 * The policy for a NEW slot's vertical offset (Source units): the floor on kAnchorFloorY,
	 * oyUnits = 64 * 40 - round(floorZ), clamped so the map's z range [minZ, maxZ] stays inside the
	 * mirror dimension (when it fits at all). minZ/maxZ NaN: no clamp.
	 */
	public static int offsetFor(float floorZ, float minZ, float maxZ) {
		return offsetFor(Proto.ANCHOR_FLOOR_Y, floorZ, minZ, maxZ);
	}

	/** As offsetFor(floorZ, minZ, maxZ), with the floor on floorY (the table's, v24). */
	public static int offsetFor(int floorY, float floorZ, float minZ, float maxZ) {
		long oy = (long) floorY * UNITS - Math.round(floorZ);
		if (!Float.isNaN(minZ) && !Float.isNaN(maxZ) && maxZ >= minZ) {
			long lo = (long) DIM_MIN_Y * UNITS - (long) Math.floor(minZ);
			long hi = (long) DIM_MAX_Y * UNITS - (long) Math.ceil(maxZ);
			if (lo <= hi) {
				oy = Math.max(lo, Math.min(hi, oy));
			}
		}
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, oy));
	}

	private static @Nullable MinecraftServer loadedFor;
	private static List<Slot> slots = new ArrayList<>();
	private static final int FLOOR_UNDECIDED = Integer.MIN_VALUE;
	private static int tableFloorY = FLOOR_UNDECIDED;  // the loaded table's floorY (undecided: no file yet)

	/**
	 * The y new maps' floors land on: the table's (fixed when the table is first written: the world's
	 * flat generator's floor, else the config's floorY).
	 */
	public static int floorY(MinecraftServer server) {
		load(server);
		if (tableFloorY != FLOOR_UNDECIDED) {
			return tableFloorY;
		}
		var overworld = server.overworld();
		if (overworld != null && overworld.getChunkSource().getGenerator() instanceof SlotFlatGenerator g) {
			return g.floorY();
		}
		return dev.gmodcraft.GmodCraftConfig.floorY();
	}

	/** floorY(server) for reports (McServerState): never throws; an unreadable table gives the config's floorY. */
	public static int floorYOrConfig(MinecraftServer server) {
		try {
			return floorY(server);
		} catch (IllegalStateException e) {
			return dev.gmodcraft.GmodCraftConfig.floorY();
		}
	}

	/** The floorY a map_slots.json text has (version 1 or none: kAnchorFloorY). */
	static int parseFloorY(String json) {
		FileFormat data = GSON.fromJson(json, FileFormat.class);
		return data == null || data.version < 2 ? Proto.ANCHOR_FLOOR_Y : data.floorY;
	}

	public static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATA).resolve("gmodcraft").resolve("map_slots.json");
	}

	private static void load(MinecraftServer server) {
		if (loadedFor == server) {
			return;
		}
		List<Slot> read;
		int floor;
		Path f = file(server);
		try {
			String json = Files.readString(f, StandardCharsets.UTF_8);
			read = parse(json, f.toString());
			floor = parseFloorY(json);
			GmodCraft.LOG.info("GmodCraft: {} map slots loaded from {} (new maps' floors on y {})", read.size(), f, floor);
		} catch (NoSuchFileException e) {
			read = new ArrayList<>();
			floor = FLOOR_UNDECIDED;
			GmodCraft.LOG.info("GmodCraft: no map slots yet ({})", f);
		} catch (IOException | RuntimeException e) {
			// Don't start over silently: a fresh table would hand out slots that are already built on.
			// (RuntimeException: Gson's JsonParseException, and anything else a broken file provokes.)
			throw new IllegalStateException("GmodCraft: can't read the map slot table " + f + "; fix or remove it", e);
		}
		slots = read;
		tableFloorY = floor;
		loadedFor = server;
		FlatColumns.publish(FlatColumns.snapshot(slots));
	}

	/**
	 * The slots in a map_slots.json text. A null entry is logged and skipped; an entry without a
	 * map name keeps its slot and is matched by worldId. Malformed JSON throws (JsonParseException).
	 */
	static List<Slot> parse(String json, String source) {
		List<Slot> read = new ArrayList<>();
		FileFormat data = GSON.fromJson(json, FileFormat.class);
		if (data == null || data.slots == null) {
			return read;
		}
		for (int i = 0; i < data.slots.size(); i++) {
			Slot s = data.slots.get(i);
			if (s == null || s.map() == null && s.worldId() == 0) {
				// Nothing identifies the map (worldId 0 is "none" in the protocol).
				GmodCraft.LOG.error("GmodCraft: {}: map slot entry {} is invalid ({}); skipped", source, i, s);
				continue;
			}
			if (data.version < 2 && s.anchorSrc() == 0) {
				// From before v21: no vertical offset, and reported as such.
				s = new Slot(s.map(), s.worldId(), s.slotX(), s.slotZ(), 0, 0.0F, Proto.ANCHOR_LEGACY, null, null);
			}
			if (s.map() == null) {
				GmodCraft.LOG.error("GmodCraft: {}: map slot entry {} has no map name; kept for world {} at slot ({}, {})", source, i,
					Integer.toHexString(s.worldId()), s.slotX(), s.slotZ());
				s = new Slot("", s.worldId(), s.slotX(), s.slotZ(), s.oyUnits(), s.floorZ(), s.anchorSrc(), s.footprint(), s.history());
			}
			read.add(s);
		}
		return read;
	}

	/**
	 * The table as map_slots.json text (v2) with kAnchorFloorY: tests only. The server always writes
	 * with the table's own floorY (save: format(slots, tableFloorY)), re-anchor commits included.
	 */
	static String format(List<Slot> table) {
		return format(table, Proto.ANCHOR_FLOOR_Y);
	}

	/** The table as map_slots.json text (v2) with this floorY. */
	static String format(List<Slot> table, int floorY) {
		FileFormat data = new FileFormat();
		data.version = 2;
		data.floorY = floorY;
		data.slots = table;
		return GSON.toJson(data);
	}

	private static boolean save(MinecraftServer server) {
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			if (tableFloorY == FLOOR_UNDECIDED) {
				tableFloorY = floorY(server);  // fixed now: the first write of this world's table
			}
			Files.writeString(tmp, format(slots, tableFloorY), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			GmodCraft.LOG.error("GmodCraft: couldn't save the map slot table {}", f, e);
			return false;
		}
	}

	/** The slot of a map, allocating the next free one (nearest the origin first) on its first visit. */
	public static Lookup lookup(MinecraftServer server, String mapName, int worldId) {
		return lookup(server, mapName, worldId, Anchor.NONE);
	}

	/** v34 kAdminSlotInfo: the slot of a map by its name only (lower case; no allocation, no worldId check), else null. */
	public static @Nullable Slot byName(MinecraftServer server, String mapName) {
		load(server);
		String map = mapName.toLowerCase(Locale.ROOT);
		if (map.isEmpty()) {
			return null;
		}
		for (Slot s : slots) {
			if (s.map().equals(map)) {
				return s;
			}
		}
		return null;
	}

	/** The slot of a map if it has one already (no allocation), else null. */
	public static @Nullable Lookup find(MinecraftServer server, String mapName, int worldId) {
		load(server);
		Slot s = match(slots, mapName.toLowerCase(Locale.ROOT), worldId);
		return s == null ? null : new Lookup(s, false, slots.size());
	}

	/** As {@link #lookup(MinecraftServer, String, int)}; a NEW slot gets the anchor's vertical offset. */
	public static Lookup lookup(MinecraftServer server, String mapName, int worldId, Anchor anchor) {
		load(server);
		String map = mapName.toLowerCase(Locale.ROOT);
		Slot found = match(slots, map, worldId);
		if (found != null) {
			return new Lookup(found, false, slots.size());
		}
		int[] xz = nextFree(slots);
		Slot slot = new Slot(map, worldId, xz[0], xz[1], anchor.oyUnits(), anchor.floorZ(), anchor.source(), anchor.footprint());
		slots.add(slot);
		save(server);
		FlatColumns.publish(FlatColumns.snapshot(slots));
		GmodCraft.LOG.info("GmodCraft: map {} (world {}) gets slot ({}, {}), origin ({}, {}), vertical offset {} units ({} blocks; anchor source {})",
			map.isEmpty() ? "?" : map, Integer.toHexString(worldId), slot.slotX(), slot.slotZ(), slot.slotX() * Proto.SLOT_BLOCKS,
			slot.slotZ() * Proto.SLOT_BLOCKS, slot.oyUnits(), slot.oyUnits() / (float) Proto.UNITS_PER_BLOCK, slot.anchorSrc());
		return new Lookup(slot, true, slots.size());
	}

	/** Every slot of the table (read-only). */
	public static List<Slot> all(MinecraftServer server) {
		load(server);
		return List.copyOf(slots);
	}

	/** The slot whose area holds block (x, z), or null. */
	public static @Nullable Slot at(MinecraftServer server, int x, int z) {
		load(server);
		for (Slot s : slots) {
			if (dev.gmodcraft.wire.SlotArea.contains(s.originX(), s.originZ(), x, z)) {
				return s;
			}
		}
		return null;
	}

	/** The next re-anchor id (one above any in the table). */
	public static int nextReanchorId(MinecraftServer server) {
		load(server);
		int id = 0;
		for (Slot s : slots) {
			for (Reanchor r : s.reanchors()) {
				id = Math.max(id, r.id());
			}
		}
		return id + 1;
	}

	/**
	 * P8 WP2: a re-anchor's commit: the slot of {@code map} gets oyUnits + dyUnits and the history entry;
	 * written at once. Returns the new slot.
	 */
	public static Slot commitReanchor(MinecraftServer server, String map, int worldId, Reanchor entry) {
		load(server);
		Slot s = match(slots, map.toLowerCase(Locale.ROOT), worldId);
		if (s == null) {
			throw new IllegalStateException("no slot for " + map);
		}
		List<Reanchor> h = new java.util.ArrayList<>(s.reanchors());
		h.add(entry);
		Slot n = new Slot(s.map(), s.worldId(), s.slotX(), s.slotZ(), s.oyUnits() + entry.dyUnits(), s.floorZ(), s.anchorSrc(), s.footprint(),
			List.copyOf(h));
		slots.set(slots.indexOf(s), n);
		if (!save(server)) {
			slots.set(slots.indexOf(n), s);
			throw new IllegalStateException("couldn't write the map slot table: the re-anchor isn't committed");
		}
		FlatColumns.publish(FlatColumns.snapshot(slots));  // every table change (review WP3 #7)
		return n;
	}

	/** Forget the loaded table (it is read again from disk next time; after a crash recovery restored it). */
	public static void reload() {
		loadedFor = null;
		tableFloorY = FLOOR_UNDECIDED;
	}

	/** Server start (before the levels load): read the table so the flat generator sees the slots' footprints. */
	public static void prime(MinecraftServer server) {
		reload();
		FlatColumns.publish(FlatColumns.EMPTY);
		try {
			load(server);
		} catch (IllegalStateException e) {
			// Reported again (and handled) where the slot is looked up; the flat generator keeps every
			// slot origin's +-416 void meanwhile.
			GmodCraft.LOG.error("GmodCraft: the map slot table can't be read at start", e);
		}
	}

	/** The table's entry for a map (by name, or by worldId for a nameless entry / no name given), or null. */
	static @Nullable Slot match(List<Slot> table, String map, int worldId) {
		for (Slot s : table) {
			// A nameless entry (kept from a table without the map name) still matches by worldId.
			boolean match = map.isEmpty() || s.map().isEmpty() ? s.worldId() == worldId : s.map().equals(map);
			if (match) {
				if (!map.isEmpty() && s.worldId() != worldId) {
					GmodCraft.LOG.warn("GmodCraft: map {} now hashes to {} (table says {}); keeping its slot", map, Integer.toHexString(worldId),
						Integer.toHexString(s.worldId()));
				}
				return s;
			}
		}
		return null;
	}

	/** Square rings around (0, 0): the first slot no map has yet. */
	static int[] nextFree(List<Slot> taken) {
		for (int r = 0; ; r++) {
			for (int z = -r; z <= r; z++) {
				for (int x = -r; x <= r; x++) {
					if (Math.max(Math.abs(x), Math.abs(z)) != r) {
						continue;
					}
					boolean used = false;
					for (Slot s : taken) {
						used |= s.slotX() == x && s.slotZ() == z;
					}
					if (!used) {
						return new int[] { x, z };
					}
				}
			}
		}
	}
}
