package dev.gmodcraft.tools;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.wire.SlotArea;
import dev.gmodcraft.world.BlockDeltas;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyDig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The Garry's Modcraft STools on the Minecraft server (P7b-2, protocol v20 admin commands 5-11),
 * server thread only. Every command needs kAdminByAdmin or kAdminEveryone (the GMod side decided);
 * none: kAdminNotAllowed. Undo stacks per steamId, in memory.
 */
public final class ToolWorld {
	private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NOT_DUG =
		new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("not dug"));
	/** Cells one repair command looks at, at most (a chunk column can hold many more dug cells). */
	public static final int MAX_CELLS = 4096;
	public static final int REPAIR_DEPTH = 5;
	public static final int BLOCK_DEPTH = 10;
	/** Terrain the digging itself makes (SkyDig.materialState over every material, stone and its ores). */
	private static final Set<Block> TERRAIN = new HashSet<>();
	private static final UndoStacks<RepairRecord> REPAIRS = new UndoStacks<>(REPAIR_DEPTH);
	private static final UndoStacks<BlockRecord> BLOCKS = new UndoStacks<>(BLOCK_DEPTH);

	record RepairRecord(int world, int ox, int oz, int oy, TerrainRepair.Result<BlockState> result) {  // oy: v21 vertical offset
	}

	record BlockRecord(BlockPos pos, BlockState before, @Nullable CompoundTag beforeEntity, BlockState after) {
	}

	/** What a command did: an AdminResult code, a count, flags, a chat message. */
	public record Result(int code, int count, int flags, String message) {
	}

	private ToolWorld() {
	}

	public static void init() {
		for (int m = 0; m < Proto.DIG_MATERIAL_COUNT; m++) {
			TERRAIN.add(SkyDig.materialState(m).getBlock());
		}
		for (Block b : new Block[] { Blocks.STONE, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.REDSTONE_ORE, Blocks.GOLD_ORE, Blocks.LAPIS_ORE,
			Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE }) {
			TERRAIN.add(b);
		}
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			REPAIRS.clear();
			BLOCKS.clear();
		});
		// Dev / test helpers (permission 2): make dug cells without digging, ask whether one is.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("gmodcraft")
			.then(Commands.literal("tool").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("dig").then(Commands.argument("from", BlockPosArgument.blockPos()).then(Commands.argument("to", BlockPosArgument.blockPos())
					.executes(c -> {
						BlockPos a = BlockPosArgument.getBlockPos(c, "from"), b = BlockPosArgument.getBlockPos(c, "to");
						if ((long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1) > 4096) {
							c.getSource().sendFailure(Component.literal("at most 4096 cells"));
							return 0;
						}
						int n = 0;
						for (BlockPos p : BlockPos.betweenClosed(a, b)) {
							n += SkyDig.markDug(c.getSource().getLevel(), currentWorld(), p.immutable()) ? 1 : 0;
						}
						int dug = n;
						c.getSource().sendSuccess(() -> Component.literal("dug " + dug + " cell(s) in world " + Integer.toHexString(currentWorld())), true);
						return Math.max(1, n);
					}))))
				// D3: remove the floating stone old blasts and reveals left in open air (see cleanFloating).
				.then(Commands.literal("cleanfloating").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
					.executes(c -> {
						Result r = cleanFloating(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()),
							com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius"));
						if (r.code() != Proto.ADMIN_OK && r.code() != Proto.ADMIN_NOTHING) {
							c.getSource().sendFailure(Component.literal(r.message()));
							return 0;
						}
						c.getSource().sendSuccess(() -> Component.literal(r.message()), true);
						return Math.max(1, r.count());
					})))
				.then(Commands.literal("isdug").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
					boolean d = SkyDig.isDug(c.getSource().getLevel(), currentWorld(), BlockPosArgument.getBlockPos(c, "pos"));
					if (!d) {
						throw NOT_DUG.create(); // a failed command (like a false `execute if`)
					}
					c.getSource().sendSuccess(() -> Component.literal("dug"), false);
					return 1;
				}))))));
	}

	private static int currentWorld() {
		return ServerHost.linked() ? ServerHost.worldId() : 0;
	}

	public static boolean isTerrain(BlockState s) {
		return TERRAIN.contains(s.getBlock());
	}

	private static boolean inSlot(BlockPos p) {
		return ServerHost.slotKnown() && SlotArea.contains(ServerHost.slotOriginX(), ServerHost.slotOriginZ(), p.getX(), p.getZ());
	}

	/** kHostEvAdminCommand codes 5-11. */
	public static Result run(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		if ((cmd.flags() & (Proto.ADMIN_BY_ADMIN | Proto.ADMIN_EVERYONE)) == 0) {
			return new Result(Proto.ADMIN_NOT_ALLOWED, 0, 0, "not allowed (admins only)");
		}
		ServerLevel level = server.overworld();
		long who = cmd.steamId();
		// a non-admin, allowed only because the server opened the tool to everyone
		boolean everyone = (cmd.flags() & Proto.ADMIN_BY_ADMIN) == 0;
		BlockPos p = BlockPos.containing(cmd.x(), cmd.y(), cmd.z());
		boolean needsSlot = cmd.code() != Proto.ADMIN_REPAIR_UNDO && cmd.code() != Proto.ADMIN_BLOCK_UNDO && !(cmd.code() == Proto.ADMIN_RESYNC && cmd.a() == 0);
		if (needsSlot && !inSlot(p)) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "outside the GMod map's Minecraft area");
		}
		return switch (cmd.code()) {
			case Proto.ADMIN_REPAIR_RADIUS -> repair(level, who, TerrainRepair.sphere(cmd.x(), cmd.y(), cmd.z(), Math.max(1, Math.min(8, cmd.a()))));
			case Proto.ADMIN_REPAIR_COLUMN -> {
				LevelChunk chunk = level.getChunkAt(p);
				List<BlockPos> dug = SkyDig.dugCells(chunk, ServerHost.worldId());
				List<int[]> cells = new ArrayList<>();
				for (BlockPos c : dug) {
					if (cells.size() >= MAX_CELLS) {
						break; // a bounded amount of work per command: the rest on the next click
					}
					cells.add(new int[] { c.getX(), c.getY(), c.getZ() });
				}
				Result r = repair(level, who, cells);
				yield dug.size() > MAX_CELLS && r.code() == Proto.ADMIN_OK
					? new Result(r.code(), r.count(), r.flags(), r.message() + " (the first " + MAX_CELLS + " of " + dug.size() + "; click again for the rest)") : r;
			}
			case Proto.ADMIN_REPAIR_UNDO -> repairUndo(level, who);
			case Proto.ADMIN_BLOCK_PLACE -> place(level, who, p, text, (cmd.flags() & Proto.ADMIN_ALLOW_LIQUID) != 0, everyone);
			case Proto.ADMIN_BLOCK_BREAK -> breakBlock(level, who, p, everyone);
			case Proto.ADMIN_BLOCK_UNDO -> blockUndo(level, who);
			case Proto.ADMIN_RESYNC -> {
				int n = cmd.a() <= 0 ? BlockDeltas.resendAll() : BlockDeltas.resendNear(level, p.getX(), p.getY(), p.getZ(), Math.min(cmd.a(), 256));
				yield new Result(Proto.ADMIN_OK, n, 0, cmd.a() <= 0 ? "re-sending every loaded chunk's blocks (" + n + " chunks)" : "re-sending " + n + " block section(s)");
			}
			default -> new Result(Proto.ADMIN_MALFORMED, 0, 0, "unknown tool command " + cmd.code());
		};
	}

	// ---- Terrain Repair ------------------------------------------------------------------------
	/**
	 * Dug-bit changes are collected and applied per chunk at {@link #flush} (one attachment change,
	 * i.e. one synced packet, per chunk instead of per cell).
	 */
	private static final class Access implements TerrainRepair.Access<BlockState> {
		final ServerLevel level;
		final int world;
		final int[] box = emptyBox();
		final java.util.Map<BlockPos, Boolean> pending = new java.util.LinkedHashMap<>();

		Access(ServerLevel level, int world) {
			this.level = level;
			this.world = world;
		}

		@Override
		public boolean isDug(int x, int y, int z) {
			BlockPos p = new BlockPos(x, y, z);
			Boolean b = this.pending.get(p);
			return b != null ? b : SkyDig.isDug(this.level, this.world, p);
		}

		@Override
		public void setDug(int x, int y, int z, boolean dug) {
			this.pending.put(new BlockPos(x, y, z), dug);
			this.box[0] = Math.min(this.box[0], x);
			this.box[1] = Math.min(this.box[1], y);
			this.box[2] = Math.min(this.box[2], z);
			this.box[3] = Math.max(this.box[3], x);
			this.box[4] = Math.max(this.box[4], y);
			this.box[5] = Math.max(this.box[5], z);
		}

		@Override
		public BlockState block(int x, int y, int z) {
			return this.level.getBlockState(new BlockPos(x, y, z));
		}

		@Override
		public void setBlock(int x, int y, int z, BlockState value) {
			this.level.setBlock(new BlockPos(x, y, z), value, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
		}

		void flush() {
			java.util.Map<Long, List<BlockPos>> byChunk = new java.util.LinkedHashMap<>();
			for (BlockPos p : this.pending.keySet()) {
				byChunk.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4), k -> new ArrayList<>()).add(p);
			}
			for (List<BlockPos> cells : byChunk.values()) {
				List<Boolean> states = new ArrayList<>(cells.size());
				for (BlockPos p : cells) {
					states.add(this.pending.get(p));
				}
				SkyDig.applyDug(this.level, this.world, this.level.getChunkAt(cells.get(0)), cells, states);
			}
			this.pending.clear();
			wallsChanged(this.box);
		}
	}

	private static int[] emptyBox() {
		return new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
	}

	private static void wallsChanged(int[] box) {
		if (box[0] <= box[3]) {
			SkyDig.wallsChanged(SkyCollision.SERVER, box[0], box[1], box[2], box[3], box[4], box[5]);
		}
	}

	/**
	 * D3 cleanup (admin command {@code /gmodcraft tool cleanfloating <radius>}): dug cells within
	 * {@code radius} of {@code center} that hold a terrain block but lie where the host has no
	 * geometry ({@link SkyDig.Probe#floating}: blasts and reveals before D3 took open sky over a brush floor
	 * for rock) go back to air and un-dug. Cells whose host geometry this server doesn't know yet are
	 * skipped (stand near them and run it again).
	 */
	static Result cleanFloating(ServerLevel level, BlockPos center, int radius) {
		if (!ServerHost.linked() || ServerHost.worldId() == 0) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "no GMod map is linked");
		}
		int world = ServerHost.worldId();
		SkyCollision store = SkyCollision.SERVER;
		SkyDig.Surfaces surfaces = store::originalSurfacesNear;
		Access acc = new Access(level, world);
		int removed = 0, unknown = 0;
		long r2 = (long) radius * radius;
		for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
			for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				for (BlockPos p : SkyDig.dugCells(level.getChunk(cx, cz), world)) {
					if (p.distSqr(center) > r2 || !isTerrain(level.getBlockState(p))) {
						continue;
					}
					if (!store.isKnown(p.getX(), p.getY(), p.getZ())) {
						unknown++;
						continue;
					}
					if (SkyDig.Probe.floating(surfaces, p.getX(), p.getY(), p.getZ())) {
						acc.setBlock(p.getX(), p.getY(), p.getZ(), Blocks.AIR.defaultBlockState());
						acc.setDug(p.getX(), p.getY(), p.getZ(), false);
						removed++;
					}
				}
			}
		}
		acc.flush();
		GmodCraft.LOG.info("GmodCraft: cleanfloating at {} r {}: {} floating block(s) removed, {} cell(s) not known yet", center.toShortString(), radius, removed,
			unknown);
		String msg = "removed " + removed + " floating block(s)" + (unknown > 0 ? "; " + unknown + " cell(s) skipped (host geometry not loaded there yet)" : "");
		return new Result(removed > 0 ? Proto.ADMIN_OK : Proto.ADMIN_NOTHING, removed, unknown, msg);
	}

	private static Result repair(ServerLevel level, long who, List<int[]> cells) {
		if (!ServerHost.linked() || ServerHost.worldId() == 0) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, 0, "no GMod map is linked");
		}
		int world = ServerHost.worldId();
		Access acc = new Access(level, world);
		TerrainRepair.Result<BlockState> r = TerrainRepair.repair(acc, cells, Blocks.AIR.defaultBlockState(), BlockState::isAir, ToolWorld::isTerrain);
		acc.flush();
		if (r.restored().isEmpty()) {
			return new Result(Proto.ADMIN_NOTHING, 0, r.skipped(),
				r.skipped() > 0 ? r.skipped() + " dug cell(s) there hold Minecraft blocks: left dug" : "no dug cells there");
		}
		REPAIRS.push(who, new RepairRecord(world, ServerHost.slotOriginX(), ServerHost.slotOriginZ(), ServerHost.slotOriginY(), r));
		GmodCraft.LOG.info("GmodCraft: terrain repair by {}: {} cell(s) solid again, {} terrain block(s) removed, {} skipped", Long.toUnsignedString(who),
			r.restored().size(), r.removed().size(), r.skipped());
		return new Result(Proto.ADMIN_OK, r.restored().size(), r.skipped(), "repaired " + r.restored().size() + " cell(s)"
			+ (r.skipped() > 0 ? "; " + r.skipped() + " cell(s) with Minecraft blocks in them stay dug" : ""));
	}

	private static Result repairUndo(ServerLevel level, long who) {
		RepairRecord rec = REPAIRS.pop(who);
		if (rec == null) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "no repair to undo");
		}
		if (!ServerHost.slotKnown() || rec.world() != ServerHost.worldId() || rec.ox() != ServerHost.slotOriginX() || rec.oz() != ServerHost.slotOriginZ()
			|| rec.oy() != ServerHost.slotOriginY()) {
			REPAIRS.push(who, rec); // still there for when that map is back
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "your last repair was on another map (or slot): not undone here");
		}
		Access acc = new Access(level, rec.world());
		int n = TerrainRepair.undo(acc, rec.result(), BlockState::isAir);
		acc.flush();
		GmodCraft.LOG.info("GmodCraft: terrain repair undone by {}: {} cell(s) dug again", Long.toUnsignedString(who), n);
		return new Result(Proto.ADMIN_OK, n, 0, "undone: " + n + " cell(s) dug again");
	}

	// ---- Block Tool ------------------------------------------------------------------------------
	private static Result place(ServerLevel level, long who, BlockPos p, String text, boolean allowLiquid, boolean everyone) {
		BlockRules.Verdict v = BlockRules.check(text, allowLiquid && !everyone, everyone);
		if (v == BlockRules.Verdict.LIQUID) {
			return new Result(Proto.ADMIN_LIQUID, 0, 0, "liquids only with 'allow liquids' ticked (admins)");
		}
		if (v != BlockRules.Verdict.OK) {
			return new Result(Proto.ADMIN_BAD_BLOCK, 0, 0, v == BlockRules.Verdict.OPERATOR ? "operator blocks can't be placed with the tool"
				: v == BlockRules.Verdict.RESTRICTED ? "only admins may place that block" : "not a block id: " + text);
		}
		allowLiquid = allowLiquid && !everyone;
		BlockState st;
		try {
			st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.trim(), false).blockState();
		} catch (Exception e) {
			return new Result(Proto.ADMIN_BAD_BLOCK, 0, 0, "unknown block: " + text);
		}
		if (st.getBlock() instanceof GameMasterBlock) {
			return new Result(Proto.ADMIN_BAD_BLOCK, 0, 0, "operator blocks can't be placed with the tool");
		}
		if (!allowLiquid && !st.getFluidState().isEmpty()) {
			return new Result(Proto.ADMIN_LIQUID, 0, 0, "liquids only with 'allow liquids' ticked");
		}
		BlockState before = level.getBlockState(p);
		if (!before.isAir() && !before.canBeReplaced()) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "there is a block there already (" + BlockStateParser.serialize(before) + ")");
		}
		level.setBlock(p, st, Block.UPDATE_ALL);
		BlockState after = level.getBlockState(p);
		BLOCKS.push(who, new BlockRecord(p.immutable(), before, null, after));
		GmodCraft.LOG.info("GmodCraft: block tool: {} placed {} at {}", Long.toUnsignedString(who), BlockStateParser.serialize(after), p.toShortString());
		return new Result(Proto.ADMIN_OK, 1, 0, "placed " + BlockStateParser.serialize(after));
	}

	private static Result breakBlock(ServerLevel level, long who, BlockPos p, boolean everyone) {
		BlockState before = level.getBlockState(p);
		if (before.isAir()) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "no Minecraft block there (GMod geometry can't be broken with this tool)");
		}
		if (before.getBlock() instanceof GameMasterBlock) {
			return new Result(Proto.ADMIN_BAD_BLOCK, 0, 0, "operator blocks can't be broken with the tool");
		}
		if (everyone && !BlockRules.everyoneMayBreak(BuiltInRegistries.BLOCK.getKey(before.getBlock()).getPath())) {
			return new Result(Proto.ADMIN_BAD_BLOCK, 0, 0, "only admins may break that block");
		}
		BlockEntity be = level.getBlockEntity(p);
		CompoundTag nbt = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
		if (be instanceof Container c) {
			c.clearContent(); // its contents are in the undo record: nothing drops
		}
		level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
		BLOCKS.push(who, new BlockRecord(p.immutable(), before, nbt, Blocks.AIR.defaultBlockState()));
		GmodCraft.LOG.info("GmodCraft: block tool: {} broke {} at {}", Long.toUnsignedString(who), BlockStateParser.serialize(before), p.toShortString());
		return new Result(Proto.ADMIN_OK, 1, 0, "broke " + BlockStateParser.serialize(before));
	}

	private static Result blockUndo(ServerLevel level, long who) {
		BlockRecord r = BLOCKS.pop(who);
		if (r == null) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "no block change to undo");
		}
		BlockState now = level.getBlockState(r.pos());
		if (!now.equals(r.after())) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "the block at " + r.pos().toShortString() + " changed since; not undone");
		}
		BlockEntity be = level.getBlockEntity(r.pos());
		if (be instanceof Container c) {
			c.clearContent();
		}
		level.setBlock(r.pos(), r.before(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
		if (r.beforeEntity() != null) {
			BlockEntity restored = BlockEntity.loadStatic(r.pos(), r.before(), r.beforeEntity(), level.registryAccess());
			if (restored != null) {
				level.setBlockEntity(restored);
			}
		}
		GmodCraft.LOG.info("GmodCraft: block tool: {} undid a change at {}", Long.toUnsignedString(who), r.pos().toShortString());
		return new Result(Proto.ADMIN_OK, 1, 0, "undone at " + r.pos().toShortString());
	}

	/** For DemoWorld's admin-command dispatch. */
	public static boolean handles(int code) {
		return code >= Proto.ADMIN_REPAIR_RADIUS && code <= Proto.ADMIN_RESYNC;
	}
}
