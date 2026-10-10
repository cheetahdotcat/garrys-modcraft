package dev.gmodcraft.slot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.demo.DemoWorld;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.wire.SlotArea;
import dev.gmodcraft.world.BlockDeltas;
import dev.gmodcraft.world.MapSlots;
import dev.gmodcraft.world.SkyDig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.Nullable;

/**
 * Slot re-anchor, undo and hash (P8 WP2), server thread only. One job at a time, tick-driven: the
 * slot's stored chunks (from its 16 region files' headers) are force-loaded a batch at a time, their
 * entities awaited, then each chunk column is visited.
 *
 * <ul>
 * <li><b>Re-anchor dy</b> (Source units): the slot's offset becomes oyUnits + dy exactly; its
 * Minecraft content (blocks with their block entities and scheduled ticks, dug cells, entities, demo
 * instances) moves up by round(dy / 40) whole blocks. Steps: checks (a slot, no MC player in it, no
 * other job, disk space) → save → SCAN (the occupied y range must still fit the dimension, else
 * nothing happens) → save + journal backup → MOVE → demos shifted, the slot table committed (offset +
 * history) → save → done (backup dropped). The block ring to GMod is held meanwhile and resent after.
 * <li><b>Undo</b> id: a re-anchor by -dy of job id (the exact inverse: the same whole blocks back).
 * <li><b>Hash</b>: SHA-256 over every block (state + block-entity NBT without its position), dug cell
 * (this map), entity (type, block position, custom name) and demo instance of the slot, twice:
 * absolute coordinates and relative ones (to the origin and the blocks moved so far), so a re-anchor
 * keeps the relative hash and an undo gives the absolute one back.
 * </ul>
 * A crash part-way: {@link ReanchorJournal#recover} restores the backup at the next start (before
 * the levels load). Players are refused while one is in the slot; one who walks in during the job
 * isn't moved (the GMod side holds MC players in WP3).
 */
public final class SlotJobs {
	private static final int BATCH = 8;                 // chunk columns per batch
	private static final int ENTITY_WAIT_TICKS = 100;   // at most this long for a batch's entities
	private static final long TICK_BUDGET_NS = 20_000_000L;
	private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;
	/** Dev test hook: -Dgmodcraft.test.reanchorHaltAt=moving (or GMODCRAFT_TEST_REANCHOR_HALT) halts the JVM half way through MOVE (crash test). */
	private static final String HALT_AT = haltAt();

	private static String haltAt() {
		String v = System.getProperty("gmodcraft.test.reanchorHaltAt");
		if (v == null || v.isBlank()) {
			v = System.getenv("GMODCRAFT_TEST_REANCHOR_HALT");
		}
		return v == null ? "" : v.trim();
	}

	/** REVIEW crash injection: halts the JVM at a named recovery point (after a full save when asked). */
	private static void haltIf(String point, @Nullable MinecraftServer server, boolean saveFirst, int id) {
		if (!point.equals(HALT_AT)) {
			return;
		}
		GmodCraft.LOG.warn("GmodCraft: test hook: halting the JVM at re-anchor {} point '{}'{}", id, point, saveFirst ? " (after a full save)" : "");
		if (saveFirst && server != null) {
			server.saveEverything(true, true, true);
		}
		Runtime.getRuntime().halt(3);
	}

	private static @Nullable Job job;
	private static String lastResult = "";
	// A job stopped half way through MOVE: the slot is half moved until the server restarts (the
	// journal restores it then). Nothing else may touch the slot meanwhile.
	private static boolean restartRequired;
	// The slot a re-anchor owns (from its start until it ends; after a failed one until the restart).
	private static MapSlots.@Nullable Slot lockedSlot;
	// Hash jobs have their own ids (from HASH_IDS up, per server run): re-anchor ids come from the slot table.
	private static final int HASH_IDS = 1_000_001;
	private static int nextHashId = HASH_IDS;

	/**
	 * Review finding 1/5: while a re-anchor runs (or one failed and the server must restart), nothing
	 * else edits the world: slot allocation, demos, tools, digging, block edits by players in the slot.
	 */
	public static boolean locked() {
		return lockedSlot != null;
	}

	/** locked() and block (x, z) is in the locked slot. */
	public static boolean lockedAt(int x, int z) {
		MapSlots.Slot s = lockedSlot;
		return s != null && SlotArea.contains(s.originX(), s.originZ(), x, z);
	}

	private SlotJobs() {
	}

	public static boolean busy() {
		return job != null;
	}

	/** What the last finished job said (info). */
	public static String lastResult() {
		return lastResult;
	}

	public static void init() {
		// Crash recovery, before the levels load (SERVER_STARTING: the world folder is open, nothing is read yet).
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path world = server.getWorldPath(LevelResource.ROOT);
			try {
				ReanchorJournal.State s = ReanchorJournal.read(world);
				ReanchorJournal.Recovery r = ReanchorJournal.recover(world);
				if (r != ReanchorJournal.Recovery.NOTHING) {
					GmodCraft.LOG.warn("GmodCraft: an unfinished slot re-anchor (job {}, map {}, phase {}) was found at start: {}", s != null ? s.id : -1,
						s != null ? s.map : "?", s != null ? s.phase : "?", r == ReanchorJournal.Recovery.RESTORED
							? "the backup was put back, the world is as before the job" : "nothing had moved yet, dropped");
					MapSlots.reload();
					DemoWorld.reload();
				}
			} catch (IOException | RuntimeException e) {
				throw new IllegalStateException("GmodCraft: can't recover the unfinished slot re-anchor in " + ReanchorJournal.dirOf(world)
					+ "; refusing to start (fix or remove that folder)", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			restartRequired = false;
			lockedSlot = null;
			nextHashId = HASH_IDS;
		});
		// MC players don't edit blocks in a slot that is being moved (finding 1)
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (level.dimension() == Level.OVERWORLD && lockedAt(pos.getX(), pos.getZ())) {
				player.sendOverlayMessage(Component.literal("A re-anchor is moving this map's blocks: wait a moment"));
				return false;
			}
			return true;
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() && level.dimension() == Level.OVERWORLD && lockedAt(hit.getBlockPos().getX(), hit.getBlockPos().getZ())) {
				return net.minecraft.world.InteractionResult.FAIL;
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (job != null) {
				GmodCraft.LOG.warn("GmodCraft: server stopping during slot job {} ({}); {}", job.id, job.kind, job.journal != null
					? "its journal restores the slot at the next start" : "nothing had changed");
				job = null;
				lockedSlot = null;
				BlockDeltas.pause(false);
				ServerHost.setSlotBusy(false);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(SlotJobs::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("gmodcraft")
			.then(Commands.literal("slot").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("info").executes(SlotJobs::cmdInfo))
				.then(Commands.literal("hash").executes(c -> feedback(c, startHash(c.getSource().getServer(), msg -> say(c, msg)))))
				.then(Commands.literal("reanchor").then(Commands.argument("dyUnits", IntegerArgumentType.integer(-65536, 65536))
					.executes(c -> feedback(c, startReanchor(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "dyUnits"), false, 0, msg -> say(c, msg)))))
					.then(Commands.literal("dry").then(Commands.argument("dyUnits", IntegerArgumentType.integer(-65536, 65536))
						.executes(c -> feedback(c, startReanchor(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "dyUnits"), true, 0,
							msg -> say(c, msg)))))))
				.then(Commands.literal("undo").then(Commands.argument("id", IntegerArgumentType.integer(1))
					.executes(c -> feedback(c, startUndo(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "id"), msg -> say(c, msg)))))))));
	}

	private static String entityText(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + (e.hasCustomName() ? " " + e.getCustomName().getString() : "");
	}

	private static void say(CommandContext<CommandSourceStack> c, String msg) {
		c.getSource().sendSuccess(() -> Component.literal(msg), true);
	}

	private static int feedback(CommandContext<CommandSourceStack> c, Result r) {
		if (r.code() == Proto.ADMIN_OK) {
			c.getSource().sendSuccess(() -> Component.literal(r.message()), true);
			return Math.max(1, r.id());
		}
		c.getSource().sendFailure(Component.literal(r.message()));
		return 0;
	}

	/** A request's answer: AdminResult code, the job id (0: none), a message. */
	public record Result(int code, int id, String message) {
	}

	private static int cmdInfo(CommandContext<CommandSourceStack> c) {
		MinecraftServer server = c.getSource().getServer();
		MapSlots.Slot s = currentSlot(server);
		if (s == null) {
			c.getSource().sendFailure(Component.literal("no map slot (no GMod map linked)"));
			return 0;
		}
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "slot of %s: (%d, %d), origin (%d, %d), vertical offset %d units (%.3f blocks), anchor source %d",
			s.map(), s.slotX(), s.slotZ(), s.originX(), s.originZ(), s.oyUnits(), s.oyUnits() / 40.0, s.anchorSrc()));
		for (MapSlots.Reanchor r : s.reanchors()) {
			b.append(String.format(Locale.ROOT, "\n  re-anchor %d: %+d units (%+d blocks)%s", r.id(), r.dyUnits(), r.blocks(), r.undoOf() != 0 ? " undoing " + r.undoOf() : ""));
		}
		b.append(job != null ? "\n  job " + job.id + " running (" + job.kind + ", " + job.step + ")" : "\n  no job running").append(lastResult.isEmpty() ? "" : "\n  last: " + lastResult);
		c.getSource().sendSuccess(() -> Component.literal(b.toString()), false);
		return 1;
	}

	static MapSlots.@Nullable Slot currentSlot(MinecraftServer server) {
		String map = ServerHost.currentMap();
		if (map.isEmpty() || !ServerHost.slotKnown()) {
			return null;
		}
		MapSlots.Lookup l = MapSlots.find(server, map, ServerHost.worldId());
		return l == null ? null : l.slot();
	}

	// ---- requests --------------------------------------------------------------------------------

	/** The admin channel (v23 kAdminReanchor / kAdminReanchorUndo / kAdminSlotHash). */
	public static boolean handles(int code) {
		return code == Proto.ADMIN_REANCHOR || code == Proto.ADMIN_REANCHOR_UNDO || code == Proto.ADMIN_SLOT_HASH;
	}

	public static Result run(MinecraftServer server, int code, int a, int flags) {
		if ((flags & (Proto.ADMIN_BY_ADMIN)) == 0) {
			return new Result(Proto.ADMIN_NOT_ALLOWED, 0, "slot commands are for admins");
		}
		Consumer<String> log = m -> GmodCraft.LOG.info("GmodCraft: {}", m);
		return switch (code) {
			case Proto.ADMIN_REANCHOR -> startReanchor(server, a, (flags & Proto.ADMIN_DRY_RUN) != 0, 0, log);
			case Proto.ADMIN_REANCHOR_UNDO -> startUndo(server, a, log);
			case Proto.ADMIN_SLOT_HASH -> startHash(server, log);
			default -> new Result(Proto.ADMIN_MALFORMED, 0, "not a slot command");
		};
	}

	public static Result startHash(MinecraftServer server, Consumer<String> report) {
		Result pre = preflight(server, false);
		if (pre != null) {
			return pre;
		}
		MapSlots.Slot s = currentSlot(server);
		int id = nextHashId++;
		Job j = new Job(id, Kind.HASH, s, 0, 0, false, 0, report);
		job = j;
		j.start(server);
		return new Result(Proto.ADMIN_OK, id, "hashing slot of " + s.map() + " (job " + id + ", " + j.stored.size() + " stored chunks)");
	}

	public static Result startUndo(MinecraftServer server, int id, Consumer<String> report) {
		MapSlots.Slot s = currentSlot(server);
		if (s == null) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, "no map slot (no GMod map linked)");
		}
		for (MapSlots.Reanchor r : s.reanchors()) {
			if (r.id() == id) {
				for (MapSlots.Reanchor later : s.reanchors()) {
					if (later.undoOf() == id) {
						return new Result(Proto.ADMIN_NOTHING, 0, "re-anchor " + id + " was undone already (by " + later.id() + ")");
					}
				}
				return startReanchor(server, -r.dyUnits(), false, id, report, -r.blocks());
			}
		}
		return new Result(Proto.ADMIN_NOTHING, 0, "no re-anchor " + id + " in the slot of " + s.map());
	}

	public static Result startReanchor(MinecraftServer server, int dyUnits, boolean dryRun, int undoOf, Consumer<String> report) {
		MapSlots.Slot s = currentSlot(server);
		// finding 2: rounded cumulatively (the blocks follow the total offset change, no drift per step)
		int blocks = s == null ? SlotShift.blocksFor(dyUnits) : SlotShift.blocksForStep(s, dyUnits);
		return startReanchor(server, dyUnits, dryRun, undoOf, report, blocks);
	}

	private static Result startReanchor(MinecraftServer server, int dyUnits, boolean dryRun, int undoOf, Consumer<String> report, int blocks) {
		if (dyUnits == 0) {
			return new Result(Proto.ADMIN_NOTHING, 0, "dy 0: nothing to do");
		}
		Result pre = preflight(server, true);
		if (pre != null) {
			return pre;
		}
		MapSlots.Slot s = currentSlot(server);
		long oy = (long) s.oyUnits() + dyUnits;
		if (oy < Integer.MIN_VALUE / 2 || oy > Integer.MAX_VALUE / 2) {
			return new Result(Proto.ADMIN_OUT_OF_RANGE, 0, "offset out of range");
		}
		int id = dryRun ? nextHashId++ : MapSlots.nextReanchorId(server);
		Job j = new Job(id, dryRun ? Kind.DRY_RUN : Kind.REANCHOR, s, dyUnits, blocks, dryRun, undoOf, report);
		job = j;
		if (!dryRun) {
			lockedSlot = s;
		}
		j.start(server);
		return new Result(Proto.ADMIN_OK, id, String.format(Locale.ROOT, "%s slot of %s by %+d units (%+d blocks): job %d, %d stored chunks", dryRun ? "checking a re-anchor of"
			: undoOf != 0 ? "undoing re-anchor " + undoOf + ": moving" : "re-anchoring", s.map(), dyUnits, blocks, id, j.stored.size()));
	}

	/** Checks every request makes; null = fine. moving: also no MC player in the slot. */
	private static @Nullable Result preflight(MinecraftServer server, boolean moving) {
		if (job != null) {
			return new Result(Proto.ADMIN_BUSY, 0, "job " + job.id + " is running (" + job.kind + ")");
		}
		if (restartRequired) {
			return new Result(Proto.ADMIN_BUSY, 0, "a re-anchor stopped half way: restart the server (its journal restores the slot)");
		}
		MapSlots.Slot s = currentSlot(server);
		if (s == null) {
			return new Result(Proto.ADMIN_OUTSIDE_SLOT, 0, "no map slot (no GMod map linked)");
		}
		if (moving) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.level() == server.overworld() && SlotArea.contains(s.originX(), s.originZ(), p.getBlockX(), p.getBlockZ())) {
					return new Result(Proto.ADMIN_SLOT_OCCUPIED, 0, p.getGameProfile().name() + " is in the slot: everyone has to leave it (or log out) first");
				}
			}
		}
		return null;
	}

	private static void tick(MinecraftServer server) {
		Job j = job;
		if (j == null) {
			return;
		}
		try {
			j.tick(server);
		} catch (IOException | RuntimeException e) {
			GmodCraft.LOG.error("GmodCraft: slot job {} failed", j.id, e);
			j.fail(server, "failed: " + e);
		}
	}

	// ---- the job ---------------------------------------------------------------------------------

	enum Kind {
		HASH, DRY_RUN, REANCHOR
	}

	enum Step {
		SCAN, MOVE, VERIFY
	}

	private static final class Job {
		final int id;
		final Kind kind;
		final MapSlots.Slot slot;
		final int dyUnits;
		final int blocks;
		final int undoOf;
		final Consumer<String> report;
		final List<long[]> chunks = new ArrayList<>();
		// The stored chunks' status, being read (only FULL ones hold anything: lower ones are the
		// world generation's leftovers around them, void, and loading them would only make more).
		final List<java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>>> statuses = new ArrayList<>();
		final List<long[]> stored = new ArrayList<>();
		final Set<UUID> movedEntities = new HashSet<>();
		Step step = Step.SCAN;
		int next;            // next chunk index of the current pass
		List<long[]> batch = new ArrayList<>();
		final Set<Long> ticketed = new HashSet<>();
		int waited;
		int columnsDone;
		// scan results
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		@Nullable ReanchorJournal journal;
		@Nullable MessageDigest abs, rel;
		int blocksSeen, dugSeen, entitiesSeen;
		final java.util.TreeMap<String, Integer> entityTypes = new java.util.TreeMap<>();
		// VERIFY (finding 3): the moved chunks read back from disk after the post-commit save
		final List<java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>>> verify = new ArrayList<>();
		long savedBeforeMove;  // the game time of the save before the backup: moved chunks must be newer on disk

		Job(int id, Kind kind, MapSlots.Slot slot, int dyUnits, int blocks, boolean dry, int undoOf, Consumer<String> report) {
			this.id = id;
			this.kind = kind;
			this.slot = slot;
			this.dyUnits = dyUnits;
			this.blocks = blocks;
			this.undoOf = undoOf;
			this.report = report;
		}

		Path world(MinecraftServer server) {
			return server.getWorldPath(LevelResource.ROOT);
		}

		/** Relative paths (to the world folder) of the slot's region / entities / poi files. */
		List<String> slotFiles(MinecraftServer server) {
			Path world = world(server);
			Path dim = DimensionType.getStorageFolder(Level.OVERWORLD, world);
			List<String> out = new ArrayList<>();
			for (String sub : new String[] { "region", "entities", "poi" }) {
				for (int[] r : SlotShift.regionFiles(this.slot.originX(), this.slot.originZ())) {
					out.add(world.relativize(dim.resolve(sub).resolve("r." + r[0] + "." + r[1] + ".mca")).toString().replace('\\', '/'));
				}
			}
			return out;
		}

		void start(MinecraftServer server) {
			if (this.kind == Kind.REANCHOR) {
				ServerHost.setSlotBusy(true);  // a dry run changes nothing: the slot stays usable (nit 8)
			}
			server.saveEverything(true, true, true);  // the region headers below must list every chunk that exists
			Path dim = DimensionType.getStorageFolder(Level.OVERWORLD, world(server));
			try {
				for (int[] r : SlotShift.regionFiles(this.slot.originX(), this.slot.originZ())) {
					for (int[] c : SlotShift.chunksIn(dim.resolve("region").resolve("r." + r[0] + "." + r[1] + ".mca"), r[0], r[1])) {
						this.stored.add(new long[] { c[0], c[1] });
						this.statuses.add(server.overworld().getChunkSource().chunkMap.read(new ChunkPos(c[0], c[1])));
					}
				}
			} catch (IOException e) {
				throw new IllegalStateException("can't read the slot's region files", e);
			}
			if (this.kind == Kind.HASH) {
				try {
					this.abs = MessageDigest.getInstance("SHA-256");
					this.rel = MessageDigest.getInstance("SHA-256");
				} catch (NoSuchAlgorithmException e) {
					throw new IllegalStateException(e);
				}
			}
		}

		/** True once every stored chunk's status has been read (the FULL ones are in {@link #chunks}). */
		boolean listed() {
			if (this.statuses.isEmpty()) {
				return true;
			}
			for (java.util.concurrent.CompletableFuture<?> f : this.statuses) {
				if (!f.isDone()) {
					return false;
				}
			}
			for (int i = 0; i < this.stored.size(); i++) {
				java.util.Optional<CompoundTag> tag = this.statuses.get(i).join();
				if (tag.isPresent() && "minecraft:full".equals(tag.get().getStringOr("Status", ""))) {
					this.chunks.add(this.stored.get(i));
				}
			}
			GmodCraft.LOG.info("GmodCraft: slot job {} ({}) for {}: {} stored chunks, {} of them full; dy {} units = {} blocks", this.id, this.kind, this.slot.map(),
				this.stored.size(), this.chunks.size(), this.dyUnits, this.blocks);
			this.statuses.clear();
			return true;
		}

		void tick(MinecraftServer server) throws IOException {
			ServerLevel level = server.overworld();
			if (this.step == Step.VERIFY) {
				verifyTick(server);
				return;
			}
			if (!listed()) {
				return;
			}
			long t0 = System.nanoTime();
			while (System.nanoTime() - t0 < TICK_BUDGET_NS) {
				if (this.batch.isEmpty()) {
					if (this.next >= this.chunks.size()) {
						endOfPass(server);
						return;
					}
					for (int i = 0; i < BATCH && this.next < this.chunks.size(); i++) {
						long[] c = this.chunks.get(this.next++);
						// a loading-only ticket, radius 0: the chunk (with its entities) and no FULL neighbours
						level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, new ChunkPos((int) c[0], (int) c[1]), 0);
						this.ticketed.add(ChunkPos.pack((int) c[0], (int) c[1]));
						this.batch.add(c);
					}
					this.waited = 0;
					return;  // entities arrive asynchronously: look again next tick
				}
				boolean ready = true;
				for (long[] c : this.batch) {
					ready &= level.areEntitiesLoaded(ChunkPos.pack((int) c[0], (int) c[1]));
				}
				if (!ready && ++this.waited < ENTITY_WAIT_TICKS) {
					return;
				}
				if (!ready) {
					// finding 4: never move (or hash) a column without its entities: they'd stay behind
					throw new IOException("the entities of some of " + this.batch.size() + " columns didn't load within " + ENTITY_WAIT_TICKS + " ticks");
				}
				for (long[] c : this.batch) {
					LevelChunk chunk = level.getChunk((int) c[0], (int) c[1]);
					visit(server, level, chunk);
					this.columnsDone++;
					if ("moving".equals(HALT_AT) && this.step == Step.MOVE && this.columnsDone * 2 >= this.chunks.size()) {
						GmodCraft.LOG.warn("GmodCraft: test hook: halting the JVM in the middle of re-anchor {} ({} of {} columns moved)", this.id, this.columnsDone,
							this.chunks.size());
						server.saveEverything(true, true, true);  // half-moved chunks on disk: the worst case for the recovery
						Runtime.getRuntime().halt(3);
					}
				}
				for (long[] c : this.batch) {
					if (this.ticketed.remove(ChunkPos.pack((int) c[0], (int) c[1]))) {
						level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, new ChunkPos((int) c[0], (int) c[1]), 0);
					}
				}
				this.batch = new ArrayList<>();
			}
		}

		private void visit(MinecraftServer server, ServerLevel level, LevelChunk chunk) {
			switch (this.step) {
			case SCAN -> {
				if (this.kind == Kind.HASH) {
					hashColumn(server, level, chunk);
				} else {
					scanColumn(level, chunk);
				}
			}
			case MOVE -> moveColumn(level, chunk);
			default -> {
			}
			}
		}

		private void endOfPass(MinecraftServer server) throws IOException {
			ServerLevel level = server.overworld();
			switch (this.step) {
			case SCAN -> {
				if (this.kind == Kind.HASH) {
					finishHash(server);
					return;
				}
				int minY = level.getMinY(), maxY = level.getMaxY();
				boolean fits = SlotShift.fits(this.lo, this.hi, this.blocks, minY, maxY);
				String range = this.lo > this.hi ? "nothing built" : "content y " + this.lo + " .. " + this.hi;
				if (!fits) {
					end(server, String.format(Locale.ROOT, "re-anchor %d refused: %s moved by %+d blocks leaves the dimension (y %d .. %d)", this.id, range, this.blocks, minY,
						maxY), false);
					return;
				}
				if (this.kind == Kind.DRY_RUN) {
					end(server, String.format(Locale.ROOT, "dry run %d: %s, moved by %+d blocks it fits (y %d .. %d); nothing changed", this.id, range, this.blocks,
						minY, maxY), true);
					return;
				}
				// backup, then move
				List<String> files = slotFiles(server);
				files.add(rel(server, DemoWorld.file(server)));
				files.add(rel(server, MapSlots.file(server)));
				Path world = world(server);
				long need = ReanchorJournal.sizeOf(world, files) * 2 + (64L << 20);
				long free = Files.getFileStore(world).getUsableSpace();
				if (free < need) {
					end(server, "re-anchor " + this.id + " refused: " + (free >> 20) + " MiB free, the backup needs about " + (need >> 20) + " MiB", false);
					return;
				}
				haltIf("before-backup", server, false, this.id);
				if ("mid-backup".equals(HALT_AT)) {
					ReanchorJournal.midBackupHook = () -> haltIf("mid-backup", null, false, this.id);
				}
				BlockDeltas.pause(true);
				server.saveEverything(true, true, true);
				this.savedBeforeMove = level.getGameTime();
				ReanchorJournal.State st = new ReanchorJournal.State();
				st.id = this.id;
				st.map = this.slot.map();
				st.worldId = this.slot.worldId();
				st.dyUnits = this.dyUnits;
				st.blocks = this.blocks;
				st.oyBefore = this.slot.oyUnits();
				st.startedAtMs = System.currentTimeMillis();
				try {
					this.journal = ReanchorJournal.begin(world, st, files);
				} catch (IOException | RuntimeException e) {
					ReanchorJournal.deleteTree(ReanchorJournal.dirOf(world));  // nothing moved yet: no stale journal blocking the next try
					throw e;
				}
				this.journal.phase(ReanchorJournal.Phase.MOVING);
				this.step = Step.MOVE;
				this.next = 0;
				this.columnsDone = 0;
				GmodCraft.LOG.info("GmodCraft: re-anchor {}: {} fits; backup of {} files taken, moving {} columns by {} blocks", this.id, range, files.size(),
					this.chunks.size(), this.blocks);
			}
			case MOVE -> {
				haltIf("after-move", server, true, this.id);
				if (!DemoWorld.shiftWorld(server, this.slot.worldId(), this.blocks)) {
					throw new IOException("demos.dat couldn't be written");
				}
				MapSlots.Slot now = MapSlots.commitReanchor(server, this.slot.map(), this.slot.worldId(),
					new MapSlots.Reanchor(this.id, System.currentTimeMillis(), this.dyUnits, this.blocks, this.slot.oyUnits(), this.undoOf));
				this.journal.phase(ReanchorJournal.Phase.COMMITTED);
				haltIf("committed", server, false, this.id);
				server.saveEverything(true, true, true);
				haltIf("committed-saved", server, false, this.id);
				this.committed = now;
				// finding 3: read every moved chunk back before the journal goes
				for (long[] c : this.chunks) {
					this.verify.add(level.getChunkSource().chunkMap.read(new ChunkPos((int) c[0], (int) c[1])));
				}
				this.step = Step.VERIFY;
			}
			default -> {
			}
			}
		}

		MapSlots.@Nullable Slot committed;

		/** VERIFY: once every read is back, each moved chunk must be on disk, FULL and saved after the move began. */
		private void verifyTick(MinecraftServer server) throws IOException {
			for (java.util.concurrent.CompletableFuture<?> f : this.verify) {
				if (!f.isDone()) {
					return;
				}
			}
			int bad = 0;
			String first = "";
			for (int i = 0; i < this.chunks.size(); i++) {
				java.util.Optional<CompoundTag> tag;
				try {
					tag = this.verify.get(i).join();
				} catch (RuntimeException e) {
					tag = java.util.Optional.empty();
				}
				boolean ok = tag.isPresent() && "minecraft:full".equals(tag.get().getStringOr("Status", ""))
					&& tag.get().getLongOr("LastUpdate", Long.MIN_VALUE) > this.savedBeforeMove;
				if (!ok) {
					if (bad++ == 0) {
						first = this.chunks.get(i)[0] + "," + this.chunks.get(i)[1] + (tag.isEmpty() ? " missing" : " not rewritten");
					}
				}
			}
			if (bad > 0) {
				throw new IOException(bad + " moved chunk(s) aren't on disk as moved (first: " + first + ")");
			}
			this.journal.finish();
			this.journal = null;
			PlayerAnchors.markOnline(server, this.committed);
			end(server, String.format(Locale.ROOT, "re-anchor %d done%s: slot of %s moved by %+d blocks (%d columns, %d entities, all read back); vertical offset %d -> %d units",
				this.id, this.undoOf != 0 ? " (undid " + this.undoOf + ")" : "", this.slot.map(), this.blocks, this.chunks.size(), this.movedEntities.size(),
				this.slot.oyUnits(), this.committed.oyUnits()), true);
		}

		private String rel(MinecraftServer server, Path p) {
			return world(server).relativize(p).toString().replace('\\', '/');
		}

		void end(MinecraftServer server, String message, boolean ok) {
			for (long key : this.ticketed) {
				server.overworld().getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING,
					new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)), 0);
			}
			this.ticketed.clear();
			if (this.journal != null) {
				try {
					this.journal.abandon();
				} catch (IOException | IllegalStateException e) {
					GmodCraft.LOG.error("GmodCraft: re-anchor {}: the journal stays (restored at the next start)", this.id, e);
				}
			}
			job = null;
			lastResult = message;
			BlockDeltas.pause(false);
			if (this.step == Step.MOVE || this.step == Step.VERIFY) {
				BlockDeltas.resendAll();
			}
			if (!restartRequired) {
				lockedSlot = null;
				if (this.kind == Kind.REANCHOR) {
					ServerHost.setSlotBusy(false);
				}
			}
			ServerHost.reanswerSlot();
			if (ok) {
				GmodCraft.LOG.info("GmodCraft: {}", message);
			} else {
				GmodCraft.LOG.warn("GmodCraft: {}", message);
			}
			this.report.accept(message);
		}

		void fail(MinecraftServer server, String why) {
			if (this.step == Step.MOVE || this.step == Step.VERIFY) {
				// Part-moved: leave the journal; the server must restart to restore it.
				GmodCraft.LOG.error("GmodCraft: re-anchor {} stopped while moving; the slot is half moved: RESTART the server, the journal restores the backup",
					this.id);
				this.journal = null;
				restartRequired = true;
			}
			end(server, "job " + this.id + " " + why, false);
		}

		// ---- visitors ----------------------------------------------------------------------------

		private void scanColumn(ServerLevel level, LevelChunk chunk) {
			LevelChunkSection[] sections = chunk.getSections();
			for (int i = 0; i < sections.length; i++) {
				if (sections[i].hasOnlyAir()) {
					continue;
				}
				int base = level.getSectionYFromSectionIndex(i) * 16;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							if (!sections[i].getBlockState(x, y, z).isAir()) {
								this.lo = Math.min(this.lo, base + y);
								this.hi = Math.max(this.hi, base + y);
							}
						}
					}
				}
			}
			for (BlockPos p : SkyDig.dugCells(chunk, this.slot.worldId())) {
				this.lo = Math.min(this.lo, p.getY());
				this.hi = Math.max(this.hi, p.getY());
			}
			for (Entity e : entitiesIn(level, chunk)) {
				this.lo = Math.min(this.lo, e.getBlockY());
				this.hi = Math.max(this.hi, e.getBlockY());
			}
		}

		private List<Entity> entitiesIn(ServerLevel level, LevelChunk chunk) {
			ChunkPos cp = chunk.getPos();
			List<Entity> out = new ArrayList<>();
			level.getEntities(EntityTypeTest.forClass(Entity.class), e -> !(e instanceof Player) && e.getVehicle() == null && e.chunkPosition().equals(cp), out);
			return out;
		}

		private record Moved(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
		}

		@SuppressWarnings("unchecked")
		private void moveColumn(ServerLevel level, LevelChunk chunk) {
			List<Moved> cells = new ArrayList<>();
			LevelChunkSection[] sections = chunk.getSections();
			ChunkPos cp = chunk.getPos();
			for (int i = 0; i < sections.length; i++) {
				if (sections[i].hasOnlyAir()) {
					continue;
				}
				int base = level.getSectionYFromSectionIndex(i) * 16;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState st = sections[i].getBlockState(x, y, z);
							if (!st.isAir()) {
								BlockPos p = new BlockPos(cp.getMinBlockX() + x, base + y, cp.getMinBlockZ() + z);
								BlockEntity be = level.getBlockEntity(p);
								cells.add(new Moved(p, st, be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
							}
						}
					}
				}
			}
			// scheduled ticks of the column (pending redstone, fluids): moved with their blocks
			long now = level.getGameTime();
			List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
			List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
			if (chunk.getBlockTicks() instanceof LevelChunkTicks<Block> bt) {
				bt.getAll().forEach(blockTicks::add);
				bt.removeIf(t -> true);
			}
			if (chunk.getFluidTicks() instanceof LevelChunkTicks<Fluid> ft) {
				ft.getAll().forEach(fluidTicks::add);
				ft.removeIf(t -> true);
			}
			// clear, then write shifted (the column may overlap itself)
			for (Moved m : cells) {
				if (level.getBlockEntity(m.pos()) instanceof Container c) {
					c.clearContent();  // its contents are in the NBT: never drop them
				}
				level.setBlock(m.pos(), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
			}
			for (Moved m : cells) {
				BlockPos to = m.pos().above(this.blocks);
				level.setBlock(to, m.state(), PLACE_FLAGS);
				if (m.nbt() != null) {
					BlockEntity be = BlockEntity.loadStatic(to, m.state(), m.nbt(), level.registryAccess());
					if (be != null) {
						level.setBlockEntity(be);
					}
				}
			}
			for (ScheduledTick<Block> t : blockTicks) {
				level.scheduleTick(t.pos().above(this.blocks), t.type(), (int) Math.max(0, t.triggerTick() - now), t.priority());
			}
			for (ScheduledTick<Fluid> t : fluidTicks) {
				level.scheduleTick(t.pos().above(this.blocks), t.type(), (int) Math.max(0, t.triggerTick() - now), t.priority());
			}
			SkyDig.DugColumn dug = SkyDig.column(chunk);
			SkyDig.DugColumn moved = SlotShift.shiftDug(dug, this.slot.worldId(), this.blocks, level.getMinY(), level.getMaxY());
			if (moved != dug) {
				chunk.setAttached(SkyDig.DUG, moved);
			}
			for (Entity e : entitiesIn(level, chunk)) {
				if (this.movedEntities.add(e.getUUID())) {
					e.teleportTo(e.getX(), e.getY() + this.blocks, e.getZ());
				}
			}
			chunk.markUnsaved();
		}

		private void hashColumn(MinecraftServer server, ServerLevel level, LevelChunk chunk) {
			int ox = this.slot.originX(), oz = this.slot.originZ(), base = this.slot.blocksMoved(this.slot.reanchors().size());
			LevelChunkSection[] sections = chunk.getSections();
			ChunkPos cp = chunk.getPos();
			// column by column, bottom to top, so the order doesn't depend on where the section
			// boundaries fall (a move by a non-multiple of 16 keeps the relative hash)
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					for (int i = 0; i < sections.length; i++) {
						if (sections[i].hasOnlyAir()) {
							continue;
						}
						int sy = level.getSectionYFromSectionIndex(i) * 16;
						for (int y = 0; y < 16; y++) {
							BlockState st = sections[i].getBlockState(x, y, z);
							if (st.isAir()) {
								continue;
							}
							BlockPos p = new BlockPos(cp.getMinBlockX() + x, sy + y, cp.getMinBlockZ() + z);
							BlockEntity be = level.getBlockEntity(p);
							String text = BlockStateParser.serialize(st) + (be == null ? "" : stripPos(be.saveWithFullMetadata(level.registryAccess())));
							line(p.getX(), p.getY(), p.getZ(), ox, base, oz, "b " + text);
							this.blocksSeen++;
						}
					}
				}
			}
			List<BlockPos> dug = new ArrayList<>(SkyDig.dugCells(chunk, this.slot.worldId()));
			dug.sort(Comparator.comparingInt((BlockPos b) -> b.getX()).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY));
			for (BlockPos p : dug) {
				line(p.getX(), p.getY(), p.getZ(), ox, base, oz, "d");
				this.dugSeen++;
			}
			// numeric order (x, z, y, then the text): the same after a vertical move
			List<Entity> ents = new ArrayList<>(entitiesIn(level, chunk));
			ents.sort(Comparator.comparingInt((Entity e) -> e.getBlockX()).thenComparingInt(Entity::getBlockZ).thenComparingInt(Entity::getBlockY)
				.thenComparing(SlotJobs::entityText));
			for (Entity e : ents) {
				line(e.getBlockX(), e.getBlockY(), e.getBlockZ(), ox, base, oz, "e " + entityText(e) + " " + entityPayload(level, e));
				this.entitiesSeen++;
				this.entityTypes.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), 1, Integer::sum);
			}
		}

		private void line(int x, int y, int z, int ox, int base, int oz, String what) {
			this.abs.update((x + " " + y + " " + z + " " + what + "\n").getBytes(StandardCharsets.UTF_8));
			this.rel.update(((x - ox) + " " + (y - base) + " " + (z - oz) + " " + what + "\n").getBytes(StandardCharsets.UTF_8));
		}

		private void finishHash(MinecraftServer server) {
			int base = this.slot.blocksMoved(this.slot.reanchors().size());
			for (String d : DemoWorld.hashLines(server, this.slot.worldId(), 0, 0, 0)) {
				this.abs.update((d + "\n").getBytes(StandardCharsets.UTF_8));
			}
			for (String d : DemoWorld.hashLines(server, this.slot.worldId(), this.slot.originX(), base, this.slot.originZ())) {
				this.rel.update((d + "\n").getBytes(StandardCharsets.UTF_8));
			}
			String a = HexFormat.of().formatHex(this.abs.digest()).substring(0, 32);
			String r = HexFormat.of().formatHex(this.rel.digest()).substring(0, 32);
			StringBuilder types = new StringBuilder();
			this.entityTypes.forEach((k, v) -> types.append(types.length() == 0 ? "" : ", ").append(k).append(' ').append(v));
			end(server, String.format(Locale.ROOT, "slot hash %d of %s: abs=%s rel=%s (%d chunks, %d blocks, %d dug, %d entities; offset %d units, moved %+d blocks; entities: %s)",
				this.id, this.slot.map(), a, r, this.chunks.size(), this.blocksSeen, this.dugSeen, this.entitiesSeen, this.slot.oyUnits(), base,
				types.length() == 0 ? "none" : types), true);
		}

		/** An entity's saved data without what a move changes (UUID, position, motion, fall state): items, inventories, names. */
		private static String entityPayload(ServerLevel level, Entity e) {
			net.minecraft.world.level.storage.TagValueOutput out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
				net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
			e.saveWithoutId(out);
			CompoundTag t = out.buildResult();
			for (String k : new String[] { "UUID", "Pos", "Motion", "FallDistance", "OnGround", "fall_distance", "on_ground" }) {
				t.remove(k);
			}
			return t.toString();
		}

		private static String stripPos(CompoundTag t) {
			CompoundTag c = t.copy();
			c.remove("x");
			c.remove("y");
			c.remove("z");
			return c.toString();
		}
	}
}
