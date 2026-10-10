package dev.gmodcraft.demo;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.wire.SlotArea;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Demo builds in the Minecraft world (P7b, protocol v19), server thread only.
 *
 * <ul>
 * <li>Place: refused unless the whole box is air (or forced), inside the current map's slot (when a
 * GMod map is linked) and within the size limit. The box is snapshotted first (block states and
 * block entity NBT), persisted in {@code <world>/data/gmodcraft/demos.dat} (an NBT file written like
 * map_slots.json: atomic rename; survives restarts), then every block is set without neighbour
 * side effects and one neighbour-update pass follows (so a rail never lands before its floor).
 * <li>Clear: the snapshot is written back the same way; entities the demo spawned (tag
 * {@code gmodcraft_demo_<id>}: minecarts, the range demo's dispensed arrows) and item entities inside
 * the box are removed; players' arrows and items outside the box are never touched. Clear
 * overwrites whatever players changed inside the box. The undo data of a cleared instance stays in
 * demos.dat (a tombstone) until the world has been saved.
 * <li>Never over another live demo (not even forced); forced placement over blocks is refused when
 * something attached from outside (torch, lever, rail, ...) would break off.
 * <li>GMod hears kEvDemoPlaced / kEvDemoCleared; every live instance of the map is announced again
 * (+ kEvDemoSyncDone) when the link session, the map or the slot changes.
 * </ul>
 */
public final class DemoWorld {
	public static final String TAG = "gmodcraft_demo";
	// Dispensers of live range demos (their fresh ownerless arrows get the instance tag).
	private static final Map<Integer, List<BlockPos>> ARROW_SOURCES = new LinkedHashMap<>();
	private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;
	private static final Rotation[] ROT = { Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90 };

	/** One placed demo. Cells: the box's original contents in x, y, z order (Snapshot.capture's). */
	record Instance(int id, int kind, int worldId, int x, int y, int z, int q, long placedBy, DemoShape.Box box, List<Cell> cells) {
	}

	record Cell(BlockState state, @Nullable CompoundTag blockEntity) {
	}

	private static final Map<Integer, Instance> INSTANCES = new LinkedHashMap<>();
	// Cleared instances whose undo data stays in demos.dat until the world itself has been saved
	// (AFTER_SAVE): a kill between a clear and the next world save leaves the demo's blocks on disk,
	// and then the instance is live again after the restart. Not live in this run.
	private static final Map<Integer, Instance> TOMBSTONES = new LinkedHashMap<>();
	private static int nextId = 1;
	private static boolean loaded;
	// announce bookkeeping: what the last announce batch was for
	private static int announcedGeneration = -1;
	private static int announcedWorld;
	private static int announcedOx = Integer.MIN_VALUE;
	private static int announcedOz = Integer.MIN_VALUE;
	private static int announcedOy = Integer.MIN_VALUE;  // v21: a new vertical offset moves the GMod parts
	private static boolean announceNow;
	// a kHostEvAdminCommand waiting for its text slot
	private static ServerLink.@Nullable HostEvent pending;

	private DemoWorld() {
	}

	public static void init() {
		if (Demos.all().isEmpty()) {
			throw new IllegalStateException("no demos");
		}
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
			if (!TOMBSTONES.isEmpty()) {
				Map<Integer, Instance> gone = new LinkedHashMap<>(TOMBSTONES);
				TOMBSTONES.clear();
				if (!save(server)) {
					TOMBSTONES.putAll(gone); // try again after the next world save
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			INSTANCES.clear();
			TOMBSTONES.clear();
			ARROW_SOURCES.clear();
			loaded = false;
			nextId = 1;
			announcedGeneration = -1;
			pending = null;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("gmodcraft")
			.then(Commands.literal("demo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("place")
					.then(Commands.argument("name", StringArgumentType.word()).executes(c -> cmdPlace(c, false))
						.then(Commands.argument("force", BoolArgumentType.bool()).executes(c -> cmdPlace(c, BoolArgumentType.getBool(c, "force"))))))
				.then(Commands.literal("placeat")
					.then(Commands.argument("name", StringArgumentType.word())
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
							.then(Commands.argument("quarter", IntegerArgumentType.integer(0, 3)).executes(c -> cmdPlaceAt(c, false))
								.then(Commands.argument("force", BoolArgumentType.bool()).executes(c -> cmdPlaceAt(c, BoolArgumentType.getBool(c, "force"))))))))
				.then(Commands.literal("clear").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(DemoWorld::cmdClear)))
				.then(Commands.literal("clearall").executes(DemoWorld::cmdClearAll))
				.then(Commands.literal("list").executes(DemoWorld::cmdList))
				.then(Commands.literal("hash")
					.then(Commands.argument("from", BlockPosArgument.blockPos())
						.then(Commands.argument("to", BlockPosArgument.blockPos()).executes(DemoWorld::cmdHash)))))));
	}

	// ---- persistence --------------------------------------------------------------------------
	public static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATA).resolve("gmodcraft").resolve("demos.dat");
	}

	private static void load(MinecraftServer server) {
		if (loaded) {
			return;
		}
		loaded = true;
		Path f = file(server);
		if (!Files.exists(f)) {
			return;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			nextId = Math.max(1, root.getIntOr("nextId", 1));
			ListTag list = root.getListOrEmpty("instances");
			for (int i = 0; i < list.size(); i++) {
				CompoundTag t = list.getCompoundOrEmpty(i);
				int[] b = t.getIntArray("box").orElse(new int[6]);
				DemoShape.Box box = new DemoShape.Box(b[0], b[1], b[2], b[3], b[4], b[5]);
				ListTag cl = t.getListOrEmpty("cells");
				List<Cell> cells = new ArrayList<>(cl.size());
				for (int k = 0; k < cl.size(); k++) {
					CompoundTag c = cl.getCompoundOrEmpty(k);
					BlockState st = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, c.getCompoundOrEmpty("s"));
					cells.add(new Cell(st, c.getCompound("e").orElse(null)));
				}
				if (cells.size() != box.volume()) {
					GmodCraft.LOG.error("GmodCraft: demo instance {} in {} has {} cells for a box of {}: skipped", t.getIntOr("id", 0), f, cells.size(), box.volume());
					continue;
				}
				Instance in = new Instance(t.getIntOr("id", 0), t.getIntOr("kind", 0), t.getIntOr("world", 0), t.getIntOr("x", 0), t.getIntOr("y", 0),
					t.getIntOr("z", 0), t.getIntOr("q", 0), t.getLongOr("by", 0L), box, cells);
				INSTANCES.put(in.id(), in);
				trackArrowSources(in);
				nextId = Math.max(nextId, in.id() + 1);
			}
			GmodCraft.LOG.info("GmodCraft: {} demo instance(s) loaded from {}", INSTANCES.size(), f);
		} catch (IOException | RuntimeException e) {
			GmodCraft.LOG.error("GmodCraft: can't read {} (demo undo data); demos placed before stay as they are", f, e);
		}
	}

	/** Writes demos.dat (live instances and tombstones); false when it couldn't. */
	private static boolean save(MinecraftServer server) {
		CompoundTag root = new CompoundTag();
		root.putInt("version", 1);
		root.putInt("nextId", nextId);
		ListTag list = new ListTag();
		List<Instance> all = new ArrayList<>(INSTANCES.values());
		all.addAll(TOMBSTONES.values());
		for (Instance in : all) {
			CompoundTag t = new CompoundTag();
			t.putInt("id", in.id());
			t.putInt("kind", in.kind());
			t.putInt("world", in.worldId());
			t.putInt("x", in.x());
			t.putInt("y", in.y());
			t.putInt("z", in.z());
			t.putInt("q", in.q());
			t.putLong("by", in.placedBy());
			DemoShape.Box b = in.box();
			t.putIntArray("box", new int[] { b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ() });
			ListTag cl = new ListTag();
			for (Cell c : in.cells()) {
				CompoundTag ct = new CompoundTag();
				ct.put("s", NbtUtils.writeBlockState(c.state()));
				if (c.blockEntity() != null) {
					ct.put("e", c.blockEntity());
				}
				cl.add(ct);
			}
			t.put("cells", cl);
			list.add(t);
		}
		root.put("instances", list);
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			GmodCraft.LOG.error("GmodCraft: can't write {} (demo undo data)", f, e);
			return false;
		}
	}

	// ---- place / clear ---------------------------------------------------------------------------
	/** What a place / clear did: an AdminResult, the instance, a message for chat. */
	public record Result(int code, int instance, int count, String message) {
	}

	private static final class LevelGrid implements Snapshot.Grid<Cell> {
		final ServerLevel level;

		LevelGrid(ServerLevel level) {
			this.level = level;
		}

		@Override
		public Cell get(int x, int y, int z) {
			BlockPos p = new BlockPos(x, y, z);
			BlockEntity be = this.level.getBlockEntity(p);
			return new Cell(this.level.getBlockState(p), be == null ? null : be.saveWithFullMetadata(this.level.registryAccess()));
		}

		@Override
		public void set(int x, int y, int z, Cell c) {
			BlockPos p = new BlockPos(x, y, z);
			BlockEntity old = this.level.getBlockEntity(p);
			if (old instanceof Container container) {
				container.clearContent(); // its contents are in the snapshot: never drop them
			}
			this.level.setBlock(p, c.state(), PLACE_FLAGS);
			if (c.blockEntity() != null) {
				BlockEntity be = BlockEntity.loadStatic(p, c.state(), c.blockEntity(), this.level.registryAccess());
				if (be != null) {
					this.level.setBlockEntity(be);
				}
			}
		}
	}

	private static String cellText(Cell c) {
		return BlockStateParser.serialize(c.state()) + (c.blockEntity() == null ? "" : stripPos(c.blockEntity()).toString());
	}

	private static CompoundTag stripPos(CompoundTag t) {
		CompoundTag c = t.copy();
		c.remove("x");
		c.remove("y");
		c.remove("z");
		return c;
	}

	/** Places demo {@code name} with its origin at {@code origin}, turned {@code q} quarters. */
	public static Result place(MinecraftServer server, String name, BlockPos origin, int q, boolean force, long placedBy) {
		if (dev.gmodcraft.slot.SlotJobs.locked()) {
			return new Result(Proto.ADMIN_BUSY, 0, 0, "a re-anchor is running: try again when it's done");
		}
		load(server);
		DemoShape shape = Demos.byName(name);
		if (shape == null) {
			return new Result(Proto.ADMIN_UNKNOWN_DEMO, 0, 0, "unknown demo '" + name + "' (" + names() + ")");
		}
		ServerLevel level = server.overworld();
		q = Math.floorMod(q, 4);
		DemoShape.Box box = shape.worldBox(origin.getX(), origin.getY(), origin.getZ(), q);
		int world = 0;
		if (ServerHost.linked()) {
			if (!ServerHost.slotKnown()) {
				return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "the GMod map's area isn't known yet");
			}
			int ox = ServerHost.slotOriginX(), oz = ServerHost.slotOriginZ();
			if (!SlotArea.contains(ox, oz, box.minX(), box.minZ()) || !SlotArea.contains(ox, oz, box.maxX(), box.maxZ())) {
				return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "that is outside the GMod map's area");
			}
			world = ServerHost.worldId();
		}
		if (box.minY() < level.getMinY() || box.maxY() >= level.getMaxY()) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "outside the world's height");
		}
		// Never over another demo, not even forced: clearing them in either order would bring the
		// other's blocks back (each snapshot holds what was there when it was placed).
		for (Instance other : INSTANCES.values()) {
			if (intersects(other.box(), box)) {
				return new Result(Proto.ADMIN_OCCUPIED, 0, 0, "that overlaps demo #" + other.id() + " (clear it first; Force never builds over a demo)");
			}
		}
		LevelGrid grid = new LevelGrid(level);
		int[] busy = Snapshot.firstOccupied(grid, box, c -> c.state().isAir());
		if (busy != null && !force) {
			return new Result(Proto.ADMIN_OCCUPIED, 0, 0, "the area isn't empty (" + BlockStateParser.serialize(level.getBlockState(new BlockPos(busy[0], busy[1], busy[2])))
				+ " at " + busy[0] + " " + busy[1] + " " + busy[2] + "); clear it or force");
		}
		if (busy != null) {
			// Forced over blocks: something attached to them from outside the box (a torch, lever,
			// rail, ...) would pop off and isn't in the snapshot. Refuse instead.
			BlockPos att = attachedOutside(level, box);
			if (att != null) {
				return new Result(Proto.ADMIN_OCCUPIED, 0, 0, "can't force: " + BlockStateParser.serialize(level.getBlockState(att)) + " at " + att.toShortString()
					+ " next to the area would break off; move it first");
			}
		}
		// Parse everything first: a bad definition must not leave half a demo behind.
		List<BlockPos> positions = new ArrayList<>(shape.blocks.size());
		List<BlockState> states = new ArrayList<>(shape.blocks.size());
		try {
			for (DemoShape.Block b : shape.blocks) {
				BlockState st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, b.state(), false).blockState().rotate(ROT[q]);
				int[] r = DemoShape.rotate(b.x(), b.z(), q);
				positions.add(new BlockPos(origin.getX() + r[0], origin.getY() + b.y(), origin.getZ() + r[1]));
				states.add(st);
			}
		} catch (Exception e) {
			GmodCraft.LOG.error("GmodCraft: demo {} has a bad block state", name, e);
			return new Result(Proto.ADMIN_FAILED, 0, 0, "demo " + name + " is broken (see the server log)");
		}
		Snapshot<Cell> snap = Snapshot.capture(grid, box);
		int id = nextId++;
		Instance in = new Instance(id, shape.kind, world, origin.getX(), origin.getY(), origin.getZ(), q, placedBy, box, snap.cells().stream().map(Snapshot.Cell::value).toList());
		INSTANCES.put(id, in);
		if (!save(server)) { // the undo data must be on disk before the world changes
			INSTANCES.remove(id);
			nextId--;
			return new Result(Proto.ADMIN_FAILED, 0, 0, "can't write the undo data (see the server log); nothing was built");
		}
		trackArrowSources(in);
		if (force) {
			for (Snapshot.Cell<Cell> c : snap.cells()) {
				if (!c.value().state().isAir()) {
					grid.set(c.x(), c.y(), c.z(), new Cell(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null));
				}
			}
		}
		for (int i = 0; i < positions.size(); i++) {
			level.setBlock(positions.get(i), states.get(i), PLACE_FLAGS);
		}
		for (DemoShape.Fill f : shape.fills) {
			int[] r = DemoShape.rotate(f.x(), f.z(), q);
			BlockEntity be = level.getBlockEntity(new BlockPos(origin.getX() + r[0], origin.getY() + f.y(), origin.getZ() + r[1]));
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(f.item()));
			if (be instanceof DispenserBlockEntity d) {
				d.insertItem(new ItemStack(item, f.count()));
			} else if (be instanceof Container c) {
				for (int s = 0; s < c.getContainerSize(); s++) {
					if (c.getItem(s).isEmpty()) {
						c.setItem(s, new ItemStack(item, f.count()));
						break;
					}
				}
			}
		}
		for (int i = 0; i < positions.size(); i++) {
			level.updateNeighborsAt(positions.get(i), states.get(i).getBlock());
		}
		for (DemoShape.Spawn s : shape.spawns) {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(s.type()));
			Entity e = type.create(level, EntitySpawnReason.COMMAND);
			if (e == null) {
				continue;
			}
			double[] p = rotateCentered(s.x(), s.z(), q);
			double[] v = DemoShape.rotate(s.vx(), s.vz(), q);
			e.snapTo(origin.getX() + p[0], origin.getY() + s.y(), origin.getZ() + p[1], 0.0F, 0.0F);
			e.setDeltaMovement(v[0], 0.0, v[1]);
			e.addTag(TAG);
			e.addTag(TAG + "_" + id);
			level.addFreshEntity(e);
		}
		GmodCraft.LOG.info("GmodCraft: demo {} '{}' placed at {} quarter {} ({} blocks, box {})", id, name, origin.toShortString(), q, positions.size(), box);
		announce(in, false, placedBy);
		return new Result(Proto.ADMIN_OK, id, 1, "placed demo " + name + " (#" + id + ")");
	}

	/** A point (relative to the origin block's corner) turned about the origin block's centre, like the blocks. */
	static double[] rotateCentered(double x, double z, int q) {
		double[] r = DemoShape.rotate(x - 0.5, z - 0.5, q);
		return new double[] { r[0] + 0.5, r[1] + 0.5 };
	}

	public static Result clear(MinecraftServer server, int id) {
		if (dev.gmodcraft.slot.SlotJobs.locked()) {
			return new Result(Proto.ADMIN_BUSY, 0, 0, "a re-anchor is running: try again when it's done");
		}
		load(server);
		Instance in = INSTANCES.get(id);
		if (in == null) {
			return new Result(Proto.ADMIN_NO_INSTANCE, 0, 0, "no demo #" + id);
		}
		ServerLevel level = server.overworld();
		DemoShape.Box b = in.box();
		removeEntities(level, in);
		LevelGrid grid = new LevelGrid(level);
		List<Snapshot.Cell<Cell>> cells = new ArrayList<>(in.cells().size());
		int k = 0;
		for (int x = b.minX(); x <= b.maxX(); x++) {
			for (int y = b.minY(); y <= b.maxY(); y++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					cells.add(new Snapshot.Cell<>(x, y, z, in.cells().get(k++)));
				}
			}
		}
		new Snapshot<>(cells).restore(grid);
		for (Snapshot.Cell<Cell> c : cells) {
			level.updateNeighborsAt(new BlockPos(c.x(), c.y(), c.z()), c.value().state().getBlock());
		}
		removeEntities(level, in); // anything the update pass dropped
		INSTANCES.remove(id);
		ARROW_SOURCES.remove(id);
		TOMBSTONES.put(id, in); // the undo data stays on disk until the world is saved (AFTER_SAVE)
		save(server);
		DemoShape shape = Demos.byKind(in.kind());
		String name = shape == null ? "?" : shape.name;
		GmodCraft.LOG.info("GmodCraft: demo {} '{}' cleared, {} blocks restored", id, name, cells.size());
		if (!ServerLink.INSTANCE.pushEvent(Proto.EV_DEMO_CLEARED, 0, 0L, in.x(), in.y(), in.z(), in.q(), 0, in.kind(), id, in.worldId())) {
			announceNow = true; // dropped: the next batch's kEvDemoSyncDone makes GMod drop the orphan parts
		}
		return new Result(Proto.ADMIN_OK, id, 1, "cleared demo " + name + " (#" + id + "), the world is as it was");
	}

	/** Every instance of the current map (or of no map when not linked). */
	public static Result clearAll(MinecraftServer server) {
		if (dev.gmodcraft.slot.SlotJobs.locked()) {
			return new Result(Proto.ADMIN_BUSY, 0, 0, "a re-anchor is running: try again when it's done");
		}
		load(server);
		int world = ServerHost.linked() ? ServerHost.worldId() : 0;
		int n = 0;
		List<Integer> ids = new ArrayList<>(INSTANCES.keySet());
		ids.sort(java.util.Comparator.reverseOrder()); // newest first: each snapshot is what was there before it
		for (Integer id : ids) {
			Instance in = INSTANCES.get(id);
			if (in != null && in.worldId() == world) {
				clear(server, id);
				n++;
			}
		}
		return new Result(Proto.ADMIN_OK, 0, n, "cleared " + n + " demo(s)");
	}

	private static void removeEntities(ServerLevel level, Instance in) {
		DemoShape.Box b = in.box();
		String tag = TAG + "_" + in.id();
		List<Entity> tagged = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains(tag)) {
				tagged.add(e);
			}
		}
		tagged.forEach(Entity::discard); // minecarts, the range's arrows (tagged when dispensed)
		AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
		level.getEntitiesOfClass(ItemEntity.class, box).forEach(Entity::discard); // items inside the box only
	}

	/** Range demos only: remember where the instance's dispensers are. */
	// ---- P8 WP2: a slot re-anchor moves the map's instances with the blocks ------------------------
	/** The instance moved up by {@code blocks} (the box too; the cells and their block-entity NBT are position-free). */
	static Instance shifted(Instance in, int blocks) {
		return new Instance(in.id(), in.kind(), in.worldId(), in.x(), in.y() + blocks, in.z(), in.q(), in.placedBy(),
			dev.gmodcraft.slot.SlotShift.shiftBox(in.box(), blocks), in.cells());
	}

	/**
	 * Every live instance (and tombstone) of {@code worldId} moved up by {@code blocks}, then demos.dat
	 * written. False when it couldn't be written (the re-anchor's journal restores it after a crash).
	 */
	public static boolean shiftWorld(MinecraftServer server, int worldId, int blocks) {
		load(server);
		for (Map<Integer, Instance> m : List.of(INSTANCES, TOMBSTONES)) {
			for (Map.Entry<Integer, Instance> e : m.entrySet()) {
				if (e.getValue().worldId() == worldId) {
					e.setValue(shifted(e.getValue(), blocks));
				}
			}
		}
		ARROW_SOURCES.clear();
		for (Instance in : INSTANCES.values()) {
			trackArrowSources(in);
		}
		announceNow = true;
		return save(server);
	}

	/** Lines for the slot hash (P8 WP2): every live instance of {@code worldId}, relative to (ox, base y, oz). */
	public static List<String> hashLines(MinecraftServer server, int worldId, int ox, int oy, int oz) {
		load(server);
		List<String> out = new ArrayList<>();
		for (Instance in : INSTANCES.values()) {
			if (in.worldId() == worldId) {
				out.add("demo " + in.id() + " " + in.kind() + " " + (in.x() - ox) + " " + (in.y() - oy) + " " + (in.z() - oz) + " " + in.q());
			}
		}
		return out;
	}

	/** Forget what was loaded (demos.dat is read again; after a crash recovery restored it). */
	public static void reload() {
		INSTANCES.clear();
		TOMBSTONES.clear();
		ARROW_SOURCES.clear();
		loaded = false;
		nextId = 1;
	}

	private static void trackArrowSources(Instance in) {
		DemoShape shape = Demos.byKind(in.kind());
		if (in.kind() != Proto.DEMO_RANGE || shape == null) {
			return;
		}
		List<BlockPos> sources = new ArrayList<>();
		for (DemoShape.Block b : shape.blocks) {
			if (b.state().startsWith("minecraft:dispenser")) {
				int[] r = DemoShape.rotate(b.x(), b.z(), in.q());
				sources.add(new BlockPos(in.x() + r[0], in.y() + b.y(), in.z() + r[1]));
			}
		}
		ARROW_SOURCES.put(in.id(), sources);
	}

	private static boolean intersects(DemoShape.Box a, DemoShape.Box b) {
		return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minY() <= b.maxY() && b.minY() <= a.maxY() && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
	}

	/**
	 * A block just outside {@code box} (sharing a face with it) that isn't air and isn't a full
	 * block: torches, levers, buttons, rails, dust, signs, ... anything that may hang on a block
	 * inside. Conservative (a slab or a fence also counts).
	 */
	private static @Nullable BlockPos attachedOutside(ServerLevel level, DemoShape.Box box) {
		for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
			for (int y = box.minY() - 1; y <= box.maxY() + 1; y++) {
				for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
					int out = (x < box.minX() || x > box.maxX() ? 1 : 0) + (y < box.minY() || y > box.maxY() ? 1 : 0) + (z < box.minZ() || z > box.maxZ() ? 1 : 0);
					if (out != 1) {
						continue; // inside, or an edge / corner (shares no face with the box)
					}
					BlockPos p = new BlockPos(x, y, z);
					BlockState st = level.getBlockState(p);
					if (!st.isAir() && !st.isCollisionShapeFullBlock(level, p)) {
						return p;
					}
				}
			}
		}
		return null;
	}

	/**
	 * The range demo's arrows: an ownerless arrow less than 4 ticks old within 2.5 blocks of one of
	 * its dispensers was just dispensed by it and gets the instance's tag (so Clear removes it, and
	 * only it: players' arrows have an owner and are never touched).
	 */
	private static void tagDispensedArrows(MinecraftServer server) {
		if (ARROW_SOURCES.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		for (Map.Entry<Integer, List<BlockPos>> e : ARROW_SOURCES.entrySet()) {
			String tag = TAG + "_" + e.getKey();
			for (BlockPos src : e.getValue()) {
				if (!level.isLoaded(src)) {
					continue;
				}
				for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, new AABB(src).inflate(2.5),
					a -> a.getOwner() == null && a.tickCount < 4 && !a.entityTags().contains(tag))) {
					a.addTag(TAG);
					a.addTag(tag);
				}
			}
		}
	}

	// ---- GMod side ------------------------------------------------------------------------------
	private static void announce(Instance in, boolean again, long by) {
		if (!ServerLink.INSTANCE.pushEvent(Proto.EV_DEMO_PLACED, again ? 1 : 0, by, in.x(), in.y(), in.z(), in.q(), 0, in.kind(), in.id(), in.worldId())) {
			announceNow = true; // dropped (ring full / no link): the next announce batch carries it
		}
	}

	/** Once per server tick: announce every live instance of the map again when the session / map / slot changed. */
	public static void tick(MinecraftServer server) {
		dev.gmodcraft.tools.StructExport.tick();  // S2 (v38): export texts streaming to GMod
		load(server);
		tagDispensedArrows(server);
		if (!ServerHost.linked() || !ServerHost.slotKnown()) {
			announcedGeneration = -1;
			return;
		}
		load(server);
		int gen = ServerLink.INSTANCE.generation(), world = ServerHost.worldId(), ox = ServerHost.slotOriginX(), oz = ServerHost.slotOriginZ(), oy = ServerHost.slotOriginY();
		if (!announceNow && gen == announcedGeneration && world == announcedWorld && ox == announcedOx && oz == announcedOz && oy == announcedOy) {
			return;
		}
		int n = 0;
		boolean ok = true;
		for (Instance in : INSTANCES.values()) {
			if (in.worldId() == world) {
				ok &= ServerLink.INSTANCE.pushEvent(Proto.EV_DEMO_PLACED, 1, in.placedBy(), in.x(), in.y(), in.z(), in.q(), 0, in.kind(), in.id(), in.worldId());
				n++;
			}
		}
		// A partial batch must never end with kEvDemoSyncDone: GMod would drop the parts of every
		// instance whose event was lost. The whole batch again next tick (placed events are idempotent).
		if (!ok || !ServerLink.INSTANCE.pushEvent(Proto.EV_DEMO_SYNC_DONE, 0, 0L, n, 0, 0, 0, 0, 0, 0, world)) {
			return;
		}
		announceNow = false;
		announcedGeneration = gen;
		announcedWorld = world;
		announcedOx = ox;
		announcedOz = oz;
		announcedOy = oy;
		if (n > 0) {
			GmodCraft.LOG.info("GmodCraft: announced {} demo instance(s) to GMod", n);
		}
	}

	/**
	 * kHostEvAdminCommand + its kHostEvAdminText slot. Returns true when {@code ev} was one of them.
	 * A command whose text slot doesn't follow is answered kAdminMalformed.
	 */
	public static boolean accept(MinecraftServer server, ServerLink.HostEvent ev) {
		if (ev.type() == Proto.HOST_EV_ADMIN_TEXT) {
			ServerLink.HostEvent cmd = pending;
			pending = null;
			if (cmd == null || ev.text() == null) {
				GmodCraft.LOG.warn("GmodCraft: admin text slot without its command; dropped");
				return true;
			}
			run(server, cmd, textOf(ev.text()));
			return true;
		}
		endOfSequence();
		if (ev.type() != Proto.HOST_EV_ADMIN_COMMAND) {
			return false;
		}
		pending = ev;
		return true;
	}

	/** End of a host event drain (or another event): a command still missing its text slot is malformed. */
	public static void endOfSequence() {
		ServerLink.HostEvent cmd = pending;
		if (cmd != null) {
			pending = null;
			reply(cmd, new Result(Proto.ADMIN_MALFORMED, 0, 0, "no text slot"));
		}
	}

	private static String textOf(byte[] text) {
		int n = 0;
		while (n < text.length && text[n] != 0) {
			n++;
		}
		return new String(text, 0, n, StandardCharsets.UTF_8).trim();
	}

	private static void run(MinecraftServer server, ServerLink.HostEvent cmd, String name) {
		Result r;
		if (cmd.requestId() != 0 && dev.gmodcraft.ServerAdmin.handles(cmd.code())) {
			// v24: the server rules and the slot history answer with their own event fields
			try {
				dev.gmodcraft.ServerAdmin.run(server, cmd, name);
			} catch (RuntimeException e) {
				GmodCraft.LOG.error("GmodCraft: admin command {} failed", cmd.code(), e);
				reply(cmd, new Result(Proto.ADMIN_FAILED, 0, 0, e.toString()));
			}
			return;
		}
		if (cmd.requestId() == 0) {
			r = new Result(Proto.ADMIN_MALFORMED, 0, 0, "requestId 0");
		} else if (cmd.worldId() != 0 && cmd.worldId() != ServerHost.worldId()) {
			r = new Result(Proto.ADMIN_MALFORMED, 0, 0, "another map");
		} else {
			try {
				if (dev.gmodcraft.slot.SlotJobs.handles(cmd.code())) {
					// v23 slot jobs: count of the result = the job id
					dev.gmodcraft.slot.SlotJobs.Result t = dev.gmodcraft.slot.SlotJobs.run(server, cmd.code(), cmd.a(), cmd.flags());
					r = new Result(t.code(), 0, t.id(), t.message());
				} else if (dev.gmodcraft.tools.ToolWorld.handles(cmd.code()) && dev.gmodcraft.slot.SlotJobs.locked() && cmd.code() != Proto.ADMIN_RESYNC) {
					// review finding 1: tools edit the slot; not while a re-anchor moves it
					r = new Result(Proto.ADMIN_BUSY, 0, 0, "a re-anchor is running: try again when it's done");
				} else if (cmd.code() == Proto.ADMIN_STRUCT_EXPORT) {
					// S2 (v38): count = the text's length, instance (flags) = the block count
					dev.gmodcraft.tools.StructExport.Result t = dev.gmodcraft.tools.StructExport.run(server, cmd, name);
					r = new Result(t.code(), t.flags(), t.count(), t.message());
				} else if (cmd.code() == Proto.ADMIN_BRIDGE_LINK) {
					// R2 (v25): a bridge's map entity link
					dev.gmodcraft.wire.Bridges.LinkResult l = dev.gmodcraft.wire.Bridges.adminLink(server, cmd, name);
					r = new Result(l.code(), 0, l.code() == Proto.ADMIN_OK ? 1 : 0, l.message());
				} else if (dev.gmodcraft.tools.ToolWorld.handles(cmd.code())) {
					// v20 STools: flags of the result = what the tool reports there (repair: cells skipped)
					dev.gmodcraft.tools.ToolWorld.Result t = dev.gmodcraft.tools.ToolWorld.run(server, cmd, name);
					r = new Result(t.code(), t.flags(), t.count(), t.message());
				} else
				r = switch (cmd.code()) {
					case Proto.ADMIN_DEMO_PLACE -> place(server, name, BlockPos.containing(cmd.x(), cmd.y(), cmd.z()), DemoShape.quarter(cmd.yaw()),
						(cmd.flags() & Proto.ADMIN_FORCE) != 0, cmd.steamId());
					case Proto.ADMIN_DEMO_CLEAR -> clear(server, cmd.a());
					case Proto.ADMIN_DEMO_CLEAR_ALL -> clearAll(server);
					case Proto.ADMIN_DEMO_ANNOUNCE -> {
						announceNow = true;
						yield new Result(Proto.ADMIN_OK, 0, 0, "announcing");
					}
					default -> new Result(Proto.ADMIN_MALFORMED, 0, 0, "unknown admin command " + cmd.code());
				};
			} catch (RuntimeException e) {
				GmodCraft.LOG.error("GmodCraft: admin command {} failed", cmd.code(), e);
				r = new Result(Proto.ADMIN_FAILED, 0, 0, e.toString());
			}
		}
		GmodCraft.LOG.info("GmodCraft: admin command {} ({} '{}') from {}: {} - {}", cmd.requestId(), cmd.code(), name, Long.toUnsignedString(cmd.steamId()), r.code(),
			r.message());
		reply(cmd, r);
	}

	private static void reply(ServerLink.HostEvent cmd, Result r) {
		ServerLink.INSTANCE.pushEvent(Proto.EV_ADMIN_RESULT, 0, cmd.steamId(), r.count(), 0, 0, 0, r.instance(), 0, cmd.requestId(), r.code());
	}

	// ---- /gmodcraft demo ...----------------------------------------------------------------------
	private static String names() {
		return String.join(", ", Demos.all().stream().map(s -> s.name).toList());
	}

	private static int feedback(CommandContext<CommandSourceStack> c, Result r) {
		if (r.code() == Proto.ADMIN_OK) {
			c.getSource().sendSuccess(() -> Component.literal(r.message()), true);
			return Math.max(1, r.count());
		}
		c.getSource().sendFailure(Component.literal(r.message()));
		return 0;
	}

	private static int cmdPlace(CommandContext<CommandSourceStack> c, boolean force) {
		ServerPlayer p = c.getSource().getPlayer();
		if (p == null) {
			c.getSource().sendFailure(Component.literal("from the console: /gmodcraft demo placeat <name> <pos> <quarter>"));
			return 0;
		}
		int q = DemoShape.quarter(p.getYRot());
		int[] fwd = DemoShape.rotate(0, 2, q); // two blocks ahead of the player's feet
		BlockPos o = p.blockPosition().offset(fwd[0], 0, fwd[1]);
		return feedback(c, place(c.getSource().getServer(), StringArgumentType.getString(c, "name"), o, q, force, ServerHost.steamIdOf(p)));
	}

	private static int cmdPlaceAt(CommandContext<CommandSourceStack> c, boolean force) {
		BlockPos o = BlockPosArgument.getBlockPos(c, "pos");
		return feedback(c, place(c.getSource().getServer(), StringArgumentType.getString(c, "name"), o, IntegerArgumentType.getInteger(c, "quarter"), force, 0L));
	}

	private static int cmdClear(CommandContext<CommandSourceStack> c) {
		return feedback(c, clear(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "id")));
	}

	private static int cmdClearAll(CommandContext<CommandSourceStack> c) {
		return feedback(c, clearAll(c.getSource().getServer()));
	}

	private static int cmdList(CommandContext<CommandSourceStack> c) {
		load(c.getSource().getServer());
		StringBuilder sb = new StringBuilder();
		for (Instance in : INSTANCES.values()) {
			DemoShape s = Demos.byKind(in.kind());
			sb.append(sb.isEmpty() ? "" : "; ").append('#').append(in.id()).append(' ').append(s == null ? "?" : s.name).append(" at ").append(in.x()).append(' ')
				.append(in.y()).append(' ').append(in.z());
		}
		String text = INSTANCES.isEmpty() ? "no demos placed (demos: " + names() + ")" : sb.toString();
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return INSTANCES.size();
	}

	/** Debug / tests: a hash of every block (state + block entity) in a box, to compare before / after. */
	private static int cmdHash(CommandContext<CommandSourceStack> c) {
		BlockPos a = BlockPosArgument.getBlockPos(c, "from"), b = BlockPosArgument.getBlockPos(c, "to");
		DemoShape.Box box = new DemoShape.Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()),
			Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		if (box.volume() > 65536) {
			c.getSource().sendFailure(Component.literal("box too large"));
			return 0;
		}
		String h = Snapshot.hash(new LevelGrid(c.getSource().getLevel()), box, DemoWorld::cellText);
		c.getSource().sendSuccess(() -> Component.literal("hash " + h), false);
		return 1;
	}
}
