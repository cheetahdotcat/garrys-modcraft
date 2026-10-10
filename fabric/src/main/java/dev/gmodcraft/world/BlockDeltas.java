package dev.gmodcraft.world;

import static dev.gmodcraft.link.Proto.*;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.micro.MicroPart;
import dev.gmodcraft.micro.MicroblockBlock;
import dev.gmodcraft.micro.MicroblockEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The MC server's own blocks for the host, on the server link's block ring (kBlkSolids /
 * kBlkShapes (v26: octants of the blocks that aren't full cubes: slabs, stairs) / kBlkMicro (v36: microblocks' exact boxes) / kBlkDug, docs/DESIGN.md section 9, used for GMod-side collision of NPCs and props in P5). Server
 * thread only, server-side data only, so it works the same on a dedicated server.
 *
 * <p>A section is marked dirty when a block in it changes (ServerLevelBlockChangeMixin), when a
 * cell in it is dug (SkyDig), and when its chunk loads. Each tick dirty sections are re-read, for
 * at most TICK_BUDGET_NS of server-thread time ({@link TickBudget}), and sent whole (solid bits, and dug bits of the host's current map). A section that had bits and
 * now has none gets a count-0 message, so the host drops it. A new session or a new host map
 * starts over: kBlkClearAll, then every loaded section again. Only the overworld (the mirror
 * dimension) is mirrored.
 */
public final class BlockDeltas {
	// Server-thread time per tick for re-reading and sending sections (a 50 ms tick; a chunk-load
	// storm queues thousands of sections, which are then spread over several ticks).
	static final long TICK_BUDGET_NS = 4_000_000L;
	private static final TickBudget BUDGET = new TickBudget(TICK_BUDGET_NS, System::nanoTime);

	// Marked from any thread (world generation marks on Worker-Main threads), drained on the server thread.
	private static final DirtyQueue DIRTY = new DirtyQueue();
	private static final LongOpenHashSet LOADED_CHUNKS = new LongOpenHashSet();
	private static final LongOpenHashSet SENT_SOLID = new LongOpenHashSet();
	private static final LongOpenHashSet SENT_DUG = new LongOpenHashSet();
	private static final LongOpenHashSet SENT_SHAPES = new LongOpenHashSet(); // v26
	private static final LongOpenHashSet SENT_MICRO = new LongOpenHashSet(); // v36
	// v36 kBlkMicro records of the section being read: {u16 index, u8 n, n x 6 u8 eighth box}
	private static final ByteBuffer MICRO = ByteBuffer.allocate((int) MICRO_MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
	private static final long[] BITS = new long[64];
	private static final byte[] SHAPES = new byte[(int) BLOCK_SHAPE_BYTES];
	// octants per collision shape (shapes are shared, immutable objects: identity cache)
	private static final java.util.IdentityHashMap<VoxelShape, Integer> OCTANTS = new java.util.IdentityHashMap<>();
	private static int sentGeneration;
	private static int sentWorldId;
	private static boolean needClear;

	private BlockDeltas() {
	}

	public static void init() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (mirrored(level)) {
				LOADED_CHUNKS.add(chunk.getPos().pack());
				markChunk(level, chunk);
			}
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (mirrored(level)) {
				LOADED_CHUNKS.remove(chunk.getPos().pack());
			}
		});
	}

	private static boolean mirrored(Level level) {
		return !level.isClientSide() && level.dimension() == Level.OVERWORLD;
	}

	/** A block changed: on the server thread, or on a world generation worker (WorldGenRegion). */
	public static void blockChanged(Level level, BlockPos pos) {
		if (mirrored(level)) {
			DIRTY.mark(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		}
	}

	/** P7b-2 Resync (whole slot): next tick starts over (kBlkClearAll, every loaded section again). Returns the loaded chunks. */
	public static int resendAll() {
		sentGeneration = -1;
		return LOADED_CHUNKS.size();
	}

	/** P7b-2 Resync: the loaded sections within r blocks (horizontally and vertically) of (x, y, z) go again. Returns how many. */
	public static int resendNear(ServerLevel level, int x, int y, int z, int r) {
		int n = 0;
		for (int sx = (x - r) >> 4; sx <= (x + r) >> 4; sx++) {
			for (int sz = (z - r) >> 4; sz <= (z + r) >> 4; sz++) {
				if (!LOADED_CHUNKS.contains(ChunkPos.pack(sx, sz))) {
					continue;
				}
				for (int sy = Math.max(level.getMinSectionY(), (y - r) >> 4); sy <= Math.min(level.getMaxSectionY(), (y + r) >> 4); sy++) {
					DIRTY.mark(SectionPos.asLong(sx, sy, sz));
					n++;
				}
			}
		}
		return n;
	}

	private static void markChunk(ServerLevel level, LevelChunk chunk) {
		ChunkPos cp = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		SkyDig.DugColumn dug = SkyDig.column(chunk);
		for (int i = 0; i < sections.length; i++) {
			int sy = level.getSectionYFromSectionIndex(i);
			if (!sections[i].hasOnlyAir() || dug.bits(sentWorldId, sy) != null) {
				DIRTY.mark(SectionPos.asLong(cp.x(), sy, cp.z()));
			}
		}
	}

	// P8 WP2: while a slot is re-anchored nothing goes out (thousands of sections change); afterwards
	// everything is sent again (kBlkClearAll first), as for a new host session.
	private static volatile boolean paused;

	/** Holds the block ring while a re-anchor moves a slot's blocks (marks keep accumulating). */
	public static void pause(boolean on) {
		paused = on;
	}

	/** Every server tick, after the link was polled. */
	public static void tick(MinecraftServer server, int worldId) {
		DIRTY.bindOwner(Thread.currentThread());
		// Every tick, linked or not (ServerHost.endTick calls this before its unlinked return): the
		// owner takes the other threads' marks into its set.
		DIRTY.absorb();
		ServerLink link = ServerLink.INSTANCE;
		GLink.Session session = link.session();
		if (session == null || !link.active() || paused) {
			return;
		}
		ServerLevel level = server.overworld();
		if (session.generation() != sentGeneration || worldId != sentWorldId) {
			// New host session or new map: start over.
			sentGeneration = session.generation();
			sentWorldId = worldId;
			SENT_SOLID.clear();
			SENT_DUG.clear();
			SENT_SHAPES.clear();
			SENT_MICRO.clear();
			DIRTY.clear();
			BUDGET.reset();
			needClear = true;
			for (long packed : LOADED_CHUNKS.toLongArray()) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
				if (chunk != null) {
					markChunk(level, chunk);
				}
			}
			GmodCraft.LOG.info("GmodCraft: sending the MC server's blocks to GMod ({} sections)", DIRTY.absorb().size());
		}
		if (needClear) {
			if (!link.writeBlock(session, BLK_CLEAR_ALL, ByteBuffer.allocate(0), null)) {
				return;
			}
			needClear = false;
		}
		// Ring full: the section stays dirty for the next tick. Out of time: the same.
		BUDGET.drain(DIRTY.absorb(), key -> sendSection(link, session, level, key), backlog -> {
			if (backlog.ticks() > 1 || backlog.maxTickNs() > TICK_BUDGET_NS) {
				GmodCraft.LOG.info("GmodCraft: block backlog sent: {} sections over {} ticks, slowest tick {} ms (budget {} ms)", backlog.keys(), backlog.ticks(),
					String.format("%.2f", backlog.maxTickNs() / 1e6), TICK_BUDGET_NS / 1_000_000);
			}
		});
	}

	private static boolean sendSection(ServerLink link, GLink.Session session, ServerLevel level, long key) {
		int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
		LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
		if (chunk == null) {
			return true; // unloaded meanwhile: the host keeps what it has
		}
		int index = level.getSectionIndexFromSectionY(sy);
		LevelChunkSection section = index >= 0 && index < chunk.getSections().length ? chunk.getSections()[index] : null;
		java.util.Arrays.fill(BITS, 0L);
		java.util.Arrays.fill(SHAPES, (byte) 0);
		int solid = 0, shaped = 0, micro = 0;
		MICRO.clear();
		if (section != null && !section.hasOnlyAir()) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state.isAir()) {
							continue;
						}
						pos.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);
						int bit = x + 16 * z + 256 * y;
						// v36: a microblock's exact boxes (kBlkMicro), not its octants; past the
						// message cap it falls through to the solid bits + octants below
						if (state.getBlock() instanceof MicroblockBlock && chunk.getBlockEntity(pos) instanceof MicroblockEntity be) {
							java.util.List<MicroPart> parts = be.parts();
							if (parts.isEmpty()) {
								continue; // turns into air on its first tick
							}
							int n = Math.min(parts.size(), MICRO_MAX_PARTS);
							if (MICRO.remaining() >= MICRO_CELL_HEADER_BYTES + MICRO_BOX_BYTES * n) {
								MICRO.putShort((short) bit).put((byte) n);
								for (int i = 0; i < n; i++) {
									for (int v : parts.get(i).boxArray()) {
										MICRO.put((byte) v);
									}
								}
								micro++;
								continue;
							}
						}
						VoxelShape shape = state.getCollisionShape(level, pos);
						if (!shape.isEmpty()) {
							int oct = shape == Shapes.block() ? 0 : octants(shape);
							if (oct == ShapeOctants.NONE) {
								continue; // a carpet, a pressure plate, a thin snow layer: no GMod collision
							}
							BITS[bit >> 6] |= 1L << (bit & 63);
							solid++;
							if (oct != 0) {
								SHAPES[bit] = (byte) oct;
								shaped++;
							}
						}
					}
				}
			}
		}
		if (solid > 0 || SENT_SOLID.contains(key)) {
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(solid).flip();
			if (!link.writeBlock(session, BLK_SOLIDS, header, bitset(solid > 0 ? BITS : null))) {
				return false;
			}
			if (solid > 0) {
				SENT_SOLID.add(key);
			} else {
				SENT_SOLID.remove(key);
			}
		}
		// v26: the shapes of its blocks that aren't full cubes (after the solid bits they refine)
		if (shaped > 0 || SENT_SHAPES.contains(key)) {
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(shaped).flip();
			if (!link.writeBlock(session, BLK_SHAPES, header, shaped > 0 ? ByteBuffer.wrap(SHAPES.clone()) : ByteBuffer.allocate(0))) {
				return false;
			}
			if (shaped > 0) {
				SENT_SHAPES.add(key);
			} else {
				SENT_SHAPES.remove(key);
			}
		}
		// v36: the exact boxes of its microblocks (left out of the solid bits and octants above)
		if (micro > 0 || SENT_MICRO.contains(key)) {
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(micro).flip();
			ByteBuffer body = ByteBuffer.allocate(micro > 0 ? MICRO.position() : 0);
			if (micro > 0) {
				body.put(MICRO.array(), 0, MICRO.position()).flip();
			}
			if (!link.writeBlock(session, BLK_MICRO, header, body)) {
				return false;
			}
			if (micro > 0) {
				SENT_MICRO.add(key);
			} else {
				SENT_MICRO.remove(key);
			}
		}
		long[] dug = SkyDig.column(chunk).bits(sentWorldId, sy);
		int dugCount = 0;
		if (dug != null) {
			for (long word : dug) {
				dugCount += Long.bitCount(word);
			}
		}
		if (dugCount > 0 || SENT_DUG.contains(key)) {
			ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(dugCount).putInt(sentWorldId)
				.putInt(0).flip();
			if (!link.writeBlock(session, BLK_DUG, header, bitset(dugCount > 0 ? dug : null))) {
				return false;
			}
			if (dugCount > 0) {
				SENT_DUG.add(key);
			} else {
				SENT_DUG.remove(key);
			}
		}
		return true;
	}

	private static int octants(VoxelShape shape) {
		Integer cached = OCTANTS.get(shape);
		if (cached != null) {
			return cached;
		}
		java.util.List<double[]> boxes = new java.util.ArrayList<>();
		for (AABB b : shape.toAabbs()) {
			boxes.add(new double[] { b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ });
		}
		int oct = ShapeOctants.of(boxes);
		if (OCTANTS.size() < 4096) {
			OCTANTS.put(shape, oct);
		}
		return oct;
	}

	private static ByteBuffer bitset(long @org.jspecify.annotations.Nullable [] bits) {
		if (bits == null) {
			return ByteBuffer.allocate(0);
		}
		ByteBuffer out = ByteBuffer.allocate((int) BLOCK_BITS_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (long word : bits) {
			out.putLong(word);
		}
		return out.flip();
	}
}
