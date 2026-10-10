package dev.gmodcraft.world;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The hull world type (gmodcraft:hull, {@link HullGenerator}): every solid block of a GMod map is a
 * real Minecraft block of its material, a "mirror" block, generated from the map's hull file
 * (protocol/hull_format.md) that the GMod server's module traces.
 *
 * <ul>
 * <li>Files: the module writes {@code /dev/shm/gmodcraft/hull/<map>.<hash16>.bin}. While the current
 * map's slot is known, the newest file of that map is read (off the server thread), checked (the
 * header, the hash against the name, the slot offsets against the slot's) and copied to
 * {@code <world>/data/gmodcraft/hull/}, which is what the server reads at start. A file whose
 * offsets differ from the slot's (the slot was re-anchored) is not used: logged once.</li>
 * <li>Chunks generated before their map's file is there are void with a pending mark; when the file
 * arrives they are filled in (bounded per tick), and on load later.</li>
 * <li>{@link #MIRROR} marks the mirror blocks per section. Any change of such a block clears its bit
 * (it is a normal block then); a removal (mining, explosions, anything) digs the map's cell out
 * (SkyDig.markDug, if this server digs into the map). Grass turning into dirt and back keeps it.</li>
 * <li>Mirror blocks are not sent to GMod (it draws the map itself) and have no Minecraft collision:
 * the host's collision is there (BlockCollisionsMixin).</li>
 * <li>Breaking a mirror block is refused where the map can't be dug: digging off, a slot being
 * re-anchored, a thin wall with the rule off, or a building (the host's surfaces there aren't
 * diggable; the file itself has no such flag).</li>
 * </ul>
 */
public final class HullWorld {
	private HullWorld() {
	}

	public static final AttachmentType<MirrorColumn> MIRROR = AttachmentRegistry.<MirrorColumn>builder()
		.persistent(MirrorColumn.CODEC)
		.syncWith(MirrorColumn.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "hull_mirror"));

	/** A chunk generated before its map's hull file was there (to be filled in when it arrives). */
	public static final AttachmentType<Boolean> PENDING = AttachmentRegistry.<Boolean>builder()
		.persistent(com.mojang.serialization.Codec.BOOL)
		.buildAndRegister(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "hull_pending"));

	/** Where the module writes the files (GMODCRAFT_HULL_DIR overrides it for tests). */
	static Path shmDir() {
		String env = System.getenv("GMODCRAFT_HULL_DIR");
		return Path.of(env != null && !env.isBlank() ? env : "/dev/shm/gmodcraft/hull");
	}

	static Path worldDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATA).resolve("gmodcraft").resolve("hull");
	}

	// ---- the loaded files ------------------------------------------------------------------

	/** A loaded file and the map it is for. */
	public record Entry(String map, int worldId, HullFile file) {
	}

	/** Slot cell (slotX, slotZ packed as a ChunkPos long) -> its map's file. Immutable; replaced whole. */
	private static volatile Long2ObjectMap<Entry> files = new Long2ObjectOpenHashMap<>();

	/** The slot cell holding block column (x, z): slots are SLOT_BLOCKS wide, centred on their origin. */
	public static long cellOf(int x, int z) {
		return HullFile.slotCell(x, z);
	}

	/** The file covering block column (x, z), or null (none yet). Any thread. */
	public static @Nullable Entry fileAt(int x, int z) {
		return files.get(cellOf(x, z));
	}

	private static void publish(MapSlots.Slot slot, HullFile file) {
		Long2ObjectOpenHashMap<Entry> next = new Long2ObjectOpenHashMap<>(files);
		long cell = ChunkPos.pack(slot.slotX(), slot.slotZ());
		next.put(cell, new Entry(slot.map(), slot.worldId(), file));
		files = next;
		// the loaded chunks of that slot still waiting for it
		for (long c : PENDING_LOADED) {
			if (cellOf(ChunkPos.getX(c) << 4, ChunkPos.getZ(c) << 4) == cell) {
				FILL.add(c);
			}
		}
	}

	// ---- server lifecycle ------------------------------------------------------------------

	private static boolean hullWorld;
	private static final LongOpenHashSet PENDING_LOADED = new LongOpenHashSet();
	private static final LongLinkedOpenHashSet FILL = new LongLinkedOpenHashSet();
	private static final TickBudget BUDGET = new TickBudget(3_000_000L, System::nanoTime);
	private static int ticks;
	// the last /dev/shm file looked at (path + mtime + size): read once, and a refusal logged once
	private static String lastSeen = "";
	private static @Nullable CompletableFuture<Loaded> loading;

	private record Loaded(Path path, HullFile.Name name, @Nullable HullFile file, @Nullable String error) {
	}

	public static void init() {
		GmodCraft.LOG.info("GmodCraft: hull world type registered ({}, {})", MIRROR.identifier(), PENDING.identifier());
		ServerLifecycleEvents.SERVER_STARTING.register(HullWorld::loadStored);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			hullWorld = server.overworld() != null && server.overworld().getChunkSource().getGenerator() instanceof HullGenerator;
			if (hullWorld) {
				GmodCraft.LOG.info("GmodCraft: hull world: {} map file(s) loaded; watching {}", files.size(), shmDir());
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			files = new Long2ObjectOpenHashMap<>();
			PENDING_LOADED.clear();
			FILL.clear();
			hullWorld = false;
			RESTORE.clear();
			lastSeen = "";
			loading = null;
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (level.dimension() == Level.OVERWORLD && Boolean.TRUE.equals(chunk.getAttached(PENDING))) {
				long key = chunk.getPos().pack();
				PENDING_LOADED.add(key);
				if (fileAt(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ()) != null) {
					FILL.add(key);
				}
			}
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (level.dimension() == Level.OVERWORLD) {
				PENDING_LOADED.remove(chunk.getPos().pack());
				FILL.remove(chunk.getPos().pack());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(HullWorld::tick);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> mayBreak(level, player, pos));
	}

	/** At start: the files stored with the world, each for its map's slot (offsets checked). */
	private static void loadStored(MinecraftServer server) {
		files = new Long2ObjectOpenHashMap<>();
		Path dir = worldDir(server);
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
			for (Path p : ds) {
				HullFile.Name name = HullFile.Name.parse(p.getFileName().toString());
				if (name == null) {
					continue;
				}
				MapSlots.Slot slot;
				try {
					slot = MapSlots.byName(server, name.map());
				} catch (IllegalStateException e) {
					GmodCraft.LOG.error("GmodCraft: hull file {} not loaded: the map slot table can't be read", p);
					return;
				}
				if (slot == null) {
					GmodCraft.LOG.warn("GmodCraft: hull file {}: map {} has no slot; not used", p, name.map());
					continue;
				}
				Loaded l = read(p, name);
				if (l.file == null) {
					GmodCraft.LOG.error("GmodCraft: hull file {} is bad, not used: {}", p, l.error);
					continue;
				}
				if (!l.file.offsetsMatch(slot.originX(), slot.oyUnits(), slot.originZ())) {
					GmodCraft.LOG.warn("GmodCraft: hull file {} was traced for offsets {} (slot now {}): not used until the map is traced again", p,
						offsets(l.file), offsets(slot.originX(), slot.oyUnits(), slot.originZ()));
					continue;
				}
				publish(slot, l.file);
				GmodCraft.LOG.info("GmodCraft: hull file {} loaded: map {}, {} regions, {} blocks", p.getFileName(), slot.map(), l.file.regions.size(),
					l.file.solidCount);
			}
		} catch (IOException e) {
			GmodCraft.LOG.error("GmodCraft: can't list the hull files in {}", dir, e);
		}
	}

	private static Loaded read(Path p, HullFile.Name name) {
		try {
			long size = Files.size(p);
			if (size > HullFile.MAX_FILE_BYTES) {
				return new Loaded(p, name, null, "too big: " + size + " bytes");
			}
			HullFile f = HullFile.parse(Files.readAllBytes(p));
			if (f.hash != name.hash()) {
				return new Loaded(p, name, null, "the header's map hash " + HullFile.hex(f.hash) + " isn't the name's");
			}
			return new Loaded(p, name, f, null);
		} catch (HullFile.BadFile e) {
			return new Loaded(p, name, null, e.getMessage());
		} catch (IOException | RuntimeException e) {
			return new Loaded(p, name, null, e.toString());
		}
	}

	private static String offsets(HullFile f) {
		return offsets(f.originX, f.originYUnits, f.originZ);
	}

	private static String offsets(int ox, int oy, int oz) {
		return "(" + ox + " blocks, " + oy + " units, " + oz + " blocks)";
	}

	private static void tick(MinecraftServer server) {
		if (!hullWorld) {
			return;
		}
		ServerLevel level = server.overworld();
		restore(level);
		if (!FILL.isEmpty()) {
			BUDGET.drain(FILL, key -> {
				fill(level, key);
				return true;
			}, backlog -> {
				if (backlog.keys() > 0) {
					GmodCraft.LOG.info("GmodCraft: hull: {} waiting chunk(s) filled in over {} tick(s)", backlog.keys(), backlog.ticks());
				}
			});
		}
		CompletableFuture<Loaded> l = loading;
		if (l != null && l.isDone()) {
			loading = null;
			accept(server, l.join());
		}
		if (++ticks % 20 == 0 && loading == null) {
			watch(server);
		}
	}

	/** Once a second: the current map's newest file in /dev/shm, if it is new. */
	private static void watch(MinecraftServer server) {
		String map = dev.gmodcraft.ServerHost.currentMap().toLowerCase(Locale.ROOT);
		if (map.isEmpty() || !dev.gmodcraft.ServerHost.slotKnown()) {
			return;
		}
		Path dir = shmDir();
		Path best = null;
		HullFile.Name bestName = null;
		long bestTime = Long.MIN_VALUE;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
			for (Path p : ds) {
				HullFile.Name name = HullFile.Name.parse(p.getFileName().toString());
				if (name == null || !name.map().toLowerCase(Locale.ROOT).equals(map)) {
					continue;
				}
				long t = Files.getLastModifiedTime(p).toMillis();
				if (t > bestTime) {
					best = p;
					bestName = name;
					bestTime = t;
				}
			}
		} catch (java.nio.file.NoSuchFileException | java.nio.file.NotDirectoryException e) {
			return;
		} catch (IOException e) {
			return;
		}
		if (best == null) {
			return;
		}
		String seen;
		try {
			seen = best + "@" + bestTime + "/" + Files.size(best);
		} catch (IOException e) {
			return;
		}
		if (seen.equals(lastSeen)) {
			return;
		}
		lastSeen = seen;
		Entry have = fileAt(dev.gmodcraft.ServerHost.slotOriginX(), dev.gmodcraft.ServerHost.slotOriginZ());
		if (have != null && have.file.hash == bestName.hash() && have.map.equals(map)
			&& have.file.offsetsMatch(dev.gmodcraft.ServerHost.slotOriginX(), dev.gmodcraft.ServerHost.slotOriginY(), dev.gmodcraft.ServerHost.slotOriginZ())) {
			return; // the one in use
		}
		Path p = best;
		HullFile.Name n = bestName;
		loading = CompletableFuture.supplyAsync(() -> read(p, n), net.minecraft.util.Util.backgroundExecutor());
	}

	/** A file from /dev/shm was read: checked against the current slot, stored with the world, used. */
	private static void accept(MinecraftServer server, Loaded l) {
		if (l.file == null) {
			GmodCraft.LOG.error("GmodCraft: hull file {} is bad, not used: {}", l.path, l.error);
			return;
		}
		String map = dev.gmodcraft.ServerHost.currentMap().toLowerCase(Locale.ROOT);
		if (!dev.gmodcraft.ServerHost.slotKnown() || !l.name.map().toLowerCase(Locale.ROOT).equals(map)) {
			lastSeen = ""; // the map changed meanwhile: looked at again when it's back
			return;
		}
		int ox = dev.gmodcraft.ServerHost.slotOriginX(), oy = dev.gmodcraft.ServerHost.slotOriginY(), oz = dev.gmodcraft.ServerHost.slotOriginZ();
		if (!l.file.offsetsMatch(ox, oy, oz)) {
			GmodCraft.LOG.warn("GmodCraft: hull file {} was traced for offsets {}, the slot of {} has {}: not used (the map needs tracing again)", l.path,
				offsets(l.file), map, offsets(ox, oy, oz));
			return;
		}
		MapSlots.Slot slot;
		try {
			MapSlots.Lookup found = MapSlots.find(server, map, dev.gmodcraft.ServerHost.worldId());
			slot = found != null ? found.slot() : null;
		} catch (IllegalStateException e) {
			slot = null;
		}
		if (slot == null) {
			return;
		}
		// stored with the world first (tmp + atomic rename), the map's older files there removed
		Path dir = worldDir(server);
		String fileName = HullFile.fileName(map, l.file.hash);
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve(fileName + ".tmp");
			Files.copy(l.path, tmp, StandardCopyOption.REPLACE_EXISTING);
			// the copy must be the file that was checked: read it back
			HullFile copy = HullFile.parse(Files.readAllBytes(tmp));
			if (copy.hash != l.file.hash || copy.solidCount != l.file.solidCount) {
				Files.deleteIfExists(tmp);
				lastSeen = "";
				return; // replaced while being copied: next time
			}
			Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
				for (Path old : ds) {
					HullFile.Name n = HullFile.Name.parse(old.getFileName().toString());
					if (n != null && n.map().equals(map) && n.hash() != l.file.hash) {
						Files.deleteIfExists(old);
					}
				}
			}
		} catch (IOException | HullFile.BadFile e) {
			GmodCraft.LOG.error("GmodCraft: couldn't store hull file {} in {}; not used", l.path, dir, e);
			return;
		}
		publish(slot, l.file);
		GmodCraft.LOG.info("GmodCraft: hull file {} loaded: map {}, {} regions, {} blocks; {} waiting chunk(s) to fill in", fileName, map,
			l.file.regions.size(), l.file.solidCount, FILL.size());
	}

	// ---- blocks ----------------------------------------------------------------------------

	/** The block a mirror block of this dig material is (stone has ores, as dug ground does). */
	static BlockState stateFor(int material, long seed, int x, int y, int z) {
		if (material == Proto.DIG_STONE) {
			return SkyDig.stoneOrOre(seed, new BlockPos(x, y, z));
		}
		return material == Proto.DIG_NONE ? Blocks.STONE.defaultBlockState() : SkyDig.materialState(material);
	}

	static final HullFile.Underground UNDERGROUND = SkyDig::underground;

	/** A pending chunk whose file is there now: its mirror blocks (where nothing is, and the map isn't dug). */
	private static void fill(ServerLevel level, long key) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
		if (chunk == null || !Boolean.TRUE.equals(chunk.getAttached(PENDING))) {
			return;
		}
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		Entry e = fileAt(bx, bz);
		if (e == null) {
			return;
		}
		SkyDig.DugColumn dug = SkyDig.column(chunk);
		Map<Integer, long[]> bits = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int placed = 0;
		long seed = level.getSeed();
		for (int y = level.getMinY() & ~7; y < level.getMaxY(); y += 8) {
			for (int rz = bz; rz < bz + 16; rz += 8) {
				for (int rx = bx; rx < bx + 16; rx += 8) {
					byte[] codes = e.file.regions.get(HullFile.regionKey(rx, y, rz));
					if (codes == null) {
						continue;
					}
					for (int i = 0; i < HullFile.REGION_CELLS; i++) {
						if (codes[i] == 0) {
							continue;
						}
						int x = rx + (i & 7), z = rz + ((i >> 3) & 7), yy = y + (i >> 6);
						if (yy < level.getMinY() || yy > level.getMaxY() || dug.isDug(e.worldId, x, yy, z)) {
							continue;
						}
						pos.set(x, yy, z);
						if (!chunk.getBlockState(pos).isAir()) {
							continue; // something was built there meanwhile: it stays
						}
						int material = e.file.material(x, yy, z, UNDERGROUND);
						level.setBlock(pos, stateFor(material, seed, x, yy, z), 2 | 16);
						MirrorColumn.set(bits, x, yy, z);
						placed++;
					}
				}
			}
		}
		// the bits after the blocks: setting a block clears a bit that is already there
		if (!bits.isEmpty()) {
			chunk.setAttached(MIRROR, column(chunk).with(bits));
		}
		chunk.removeAttached(PENDING);
		PENDING_LOADED.remove(key);
		if (placed > 0) {
			GmodCraft.LOG.debug("GmodCraft: hull: chunk {} filled in ({} blocks)", chunk.getPos(), placed);
		}
	}

	public static MirrorColumn column(ChunkAccess chunk) {
		MirrorColumn c = chunk.getAttached(MIRROR);
		return c != null ? c : MirrorColumn.EMPTY;
	}

	/** Is block (x, y, z) of this chunk a mirror block? */
	public static boolean isMirror(@Nullable ChunkAccess chunk, int x, int y, int z) {
		if (chunk == null) {
			return false;
		}
		MirrorColumn c = chunk.getAttached(MIRROR);
		return c != null && c.isMirror(x, y, z);
	}

	/** Is the block at pos a mirror block? Any thread; false where the chunk isn't loaded. */
	public static boolean isMirror(@Nullable BlockGetter getter, BlockPos pos) {
		// hot path (every collision / suffocation query): nothing to look up before any mirror block exists in this game
		if (!MirrorColumn.seen || !(getter instanceof Level level)) {
			return false;
		}
		// getChunkNow: never loads or waits for a chunk (safe from any thread)
		return isMirror(level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4), pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * Every block change on the server (ServerLevelBlockChangeMixin): a mirror block that changed is
	 * a normal block now, and one that was removed takes the map's cell with it.
	 */
	public static void blockChanged(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
		if (level.dimension() != Level.OVERWORLD || !(level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) instanceof LevelChunk chunk)) {
			return;
		}
		MirrorColumn c = chunk.getAttached(MIRROR);
		if (restoring || c == null || !c.isMirror(pos.getX(), pos.getY(), pos.getZ()) || naturalSwap(before, after)) {
			return;
		}
		if (after.isAir() && !dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ()) && !mapMayGo(level, pos)) {
			// the map can't be dug here (fire, a command, anything but a refused break): the block comes back
			// at the end of the tick and stays a mirror block (a re-anchor moving the slot is left alone)
			RESTORE.put(pos.asLong(), before);
			return;
		}
		chunk.setAttached(MIRROR, c.without(pos.getX(), pos.getY(), pos.getZ()));
		if (after.isAir()) {
			SkyDig.markDug(level, worldIdAt(level, pos), pos);
		}
	}

	private static final Long2ObjectOpenHashMap<BlockState> RESTORE = new Long2ObjectOpenHashMap<>();
	private static boolean restoring;

	/** Mirror blocks that went where the map can't be dug: back (where nothing else was put meanwhile). */
	private static void restore(ServerLevel level) {
		if (RESTORE.isEmpty()) {
			return;
		}
		restoring = true;
		try {
			for (var e : RESTORE.long2ObjectEntrySet()) {
				BlockPos pos = BlockPos.of(e.getLongKey());
				if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
					level.setBlock(pos, e.getValue(), 2 | 16);
				}
			}
		} finally {
			restoring = false;
			RESTORE.clear();
		}
	}

	/** Grass dying under a block / spreading back: the block stays a mirror block (GMod draws its ground anyway). */
	static boolean naturalSwap(BlockState before, BlockState after) {
		return (before.is(Blocks.GRASS_BLOCK) && after.is(Blocks.DIRT)) || (before.is(Blocks.DIRT) && after.is(Blocks.GRASS_BLOCK));
	}

	/** The map (its world id) whose slot holds pos; the host's current one if no slot does. */
	private static int worldIdAt(ServerLevel level, BlockPos pos) {
		Entry e = fileAt(pos.getX(), pos.getZ());
		return e != null ? e.worldId : dev.gmodcraft.ServerHost.worldId();
	}

	/**
	 * May this mirror block go (a player breaking it, an explosion)? Not when this server doesn't dig
	 * into the map, the slot is being re-anchored, or the map can't be dug there (a building, by the
	 * host's surfaces, when they are known there).
	 */
	static boolean mapMayGo(ServerLevel level, BlockPos pos) {
		if (!SkyDig.digs() || dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ())) {
			return false;
		}
		SkyCollision store = SkyCollision.of(level);
		if (!store.active() || !store.isKnown(pos.getX(), pos.getY(), pos.getZ())) {
			return true;
		}
		return SkyDig.classify(store, pos.getX(), pos.getY(), pos.getZ()) != SkyDig.KEEP;
	}

	private static boolean mayBreak(Level level, net.minecraft.world.entity.player.Player player, BlockPos pos) {
		if (!(level instanceof ServerLevel server) || level.dimension() != Level.OVERWORLD || !isMirror(level, pos)) {
			return true;
		}
		if (!mapMayGo(server, pos)) {
			return false;
		}
		return !(player instanceof ServerPlayer sp && SkyDig.refusesThin(sp, server, worldIdAt(server, pos), pos));
	}

	/** An explosion's blocks: the mirror blocks it may not take are left out (ServerExplosionMixin). */
	public static List<BlockPos> blastTargets(ServerLevel level, List<BlockPos> targets) {
		if (!hullWorld || level.dimension() != Level.OVERWORLD) {
			return targets;
		}
		List<BlockPos> out = null;
		for (int i = 0; i < targets.size(); i++) {
			BlockPos p = targets.get(i);
			boolean keep = !isMirror(level, p) || (mapMayGo(level, p) && !SkyDig.thinForBlast(level, worldIdAt(level, p), p));
			if (!keep && out == null) {
				out = new ArrayList<>(targets.subList(0, i));
			} else if (keep && out != null) {
				out.add(p);
			}
		}
		return out != null ? out : targets;
	}

	/** Test/report: how many files are loaded. */
	public static int loadedFiles() {
		return files.size();
	}
}
