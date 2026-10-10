package dev.gmodcraft.world;

import static dev.gmodcraft.link.Proto.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.GmodCraft;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Digging into Skyrim's world. Skyrim's ground, rocks and cave walls are geometry, not blocks; this
 * turns them into blocks one at a time, as they're dug:
 * <ul>
 * <li><b>open</b>: mining a Skyrim surface (as long as mining the block it's made of takes) takes
 * the cell the surface is in out: Skyrim's geometry in it is gone (not drawn, no collision,
 * nothing for NPCs to stand on), with the drops of that block. Nothing sticks up out of the
 * ground: the cell is empty.</li>
 * <li><b>reveal</b>: the cells around a mined one that are wholly inside Skyrim's geometry become
 * blocks (dirt under grass, stone with ores, bedrock a few blocks under the land; endless stone
 * inside rocks and cave walls). Cells only partly inside keep Skyrim's surface, and the part
 * under it shows as Minecraft walls, so a hole is always closed.</li>
 * </ul>
 * Which cells are dug is kept per chunk (saved with the world, synced to every player) and per
 * Skyrim world (worldspace or interior cell: interiors share coordinates), and sent to Skyrim.
 * Opening and revealing are decided by the digging player's client, which knows Skyrim's geometry
 * around them; the server just applies it.
 */
public final class SkyDig {
	private SkyDig() {
	}

	/** One section's dug cells in one Skyrim world: bit x + 16z + 256y. */
	public record DugSection(int world, int sectionY, long[] bits) {
		static final Codec<DugSection> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("world").forGetter(DugSection::world),
			Codec.INT.fieldOf("y").forGetter(DugSection::sectionY),
			Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).fieldOf("bits").forGetter(DugSection::bits)
		).apply(i, DugSection::new));
	}

	/** A chunk's dug cells. Immutable: changes make a new one (so the attachment syncs). */
	public record DugColumn(List<DugSection> sections) {
		public static final DugColumn EMPTY = new DugColumn(List.of());
		static final Codec<DugColumn> CODEC = DugSection.CODEC.listOf().xmap(DugColumn::new, DugColumn::sections);
		static final StreamCodec<ByteBuf, DugColumn> STREAM_CODEC = new StreamCodec<>() {
			@Override
			public DugColumn decode(ByteBuf buf) {
				int n = ByteBufCodecs.VAR_INT.decode(buf);
				List<DugSection> sections = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					int world = buf.readInt();
					int y = ByteBufCodecs.VAR_INT.decode(buf);
					long[] bits = new long[64];
					for (int k = 0; k < 64; k++) {
						bits[k] = buf.readLong();
					}
					sections.add(new DugSection(world, y, bits));
				}
				return new DugColumn(List.copyOf(sections));
			}

			@Override
			public void encode(ByteBuf buf, DugColumn column) {
				ByteBufCodecs.VAR_INT.encode(buf, column.sections.size());
				for (DugSection s : column.sections) {
					buf.writeInt(s.world);
					ByteBufCodecs.VAR_INT.encode(buf, s.sectionY);
					for (int k = 0; k < 64; k++) {
						buf.writeLong(s.bits.length == 64 ? s.bits[k] : 0L);
					}
				}
			}
		};

		public long @Nullable [] bits(int world, int sectionY) {
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sectionY && s.bits.length == 64) {
					return s.bits;
				}
			}
			return null;
		}

		public boolean isDug(int world, int x, int y, int z) {
			long[] bits = bits(world, y >> 4);
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			return bits != null && ((bits[bit >> 6] >>> (bit & 63)) & 1L) != 0;
		}

		DugColumn with(int world, int x, int y, int z) {
			int sy = y >> 4;
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			List<DugSection> out = new ArrayList<>(this.sections.size() + 1);
			boolean found = false;
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sy && s.bits.length == 64) {
					long[] bits = s.bits.clone();
					bits[bit >> 6] |= 1L << (bit & 63);
					out.add(new DugSection(world, sy, bits));
					found = true;
				} else {
					out.add(s);
				}
			}
			if (!found) {
				long[] bits = new long[64];
				bits[bit >> 6] |= 1L << (bit & 63);
				out.add(new DugSection(world, sy, bits));
			}
			return new DugColumn(List.copyOf(out));
		}
	}

	/** P7b-2: the column without cell (x, y, z) of {@code world} (an empty section is dropped). */
	static DugColumn without(DugColumn column, int world, int x, int y, int z) {
		int sy = y >> 4;
		int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
		List<DugSection> out = new ArrayList<>(column.sections().size());
		for (DugSection s : column.sections()) {
			if (s.world() == world && s.sectionY() == sy && s.bits().length == 64) {
				long[] bits = s.bits().clone();
				bits[bit >> 6] &= ~(1L << (bit & 63));
				boolean any = false;
				for (long b : bits) {
					any |= b != 0;
				}
				if (any) {
					out.add(new DugSection(world, sy, bits));
				}
			} else {
				out.add(s);
			}
		}
		return new DugColumn(List.copyOf(out));
	}

	/**
	 * P7b-2 Terrain Repair: cell (x, y, z) of {@code world} is no longer dug (GMod's geometry is solid
	 * there again for Minecraft). The attachment change syncs to clients (their walls and hole
	 * stencils follow); the server link gets the section again. Returns false if it wasn't dug.
	 */
	public static boolean unmarkDug(ServerLevel level, int world, BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (!column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		chunk.setAttached(DUG, without(column, world, pos.getX(), pos.getY(), pos.getZ()));
		BlockDeltas.dugChanged(level, pos);
		return true;
	}

	/**
	 * P7b-2: many cells of one chunk dug / un-dug at once: one new attachment (one synced packet)
	 * and one server-link mark per touched section. {@code dug[i]} is the new state of {@code cells[i]}.
	 */
	public static void applyDug(ServerLevel level, int world, LevelChunk chunk, List<BlockPos> cells, List<Boolean> dug) {
		DugColumn column = column(chunk);
		java.util.Set<Integer> sections = new java.util.HashSet<>();
		for (int i = 0; i < cells.size(); i++) {
			BlockPos p = cells.get(i);
			boolean now = column.isDug(world, p.getX(), p.getY(), p.getZ());
			if (now == dug.get(i)) {
				continue;
			}
			column = dug.get(i) ? column.with(world, p.getX(), p.getY(), p.getZ()) : without(column, world, p.getX(), p.getY(), p.getZ());
			sections.add(p.getY() >> 4);
			BlockDeltas.dugChanged(level, p); // its section, and a neighbour's across a face it lies on
		}
		if (!sections.isEmpty()) {
			chunk.setAttached(DUG, column);
		}
	}

	/** P7b-2: every dug cell of {@code world} in this chunk column. */
	public static List<BlockPos> dugCells(LevelChunk chunk, int world) {
		List<BlockPos> out = new ArrayList<>();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (DugSection s : column(chunk).sections()) {
			if (s.world() != world || s.bits().length != 64) {
				continue;
			}
			for (int bit = 0; bit < 4096; bit++) {
				if (((s.bits()[bit >> 6] >>> (bit & 63)) & 1L) != 0) {
					out.add(new BlockPos(bx + (bit & 15), (s.sectionY() << 4) + (bit >> 8), bz + ((bit >> 4) & 15)));
				}
			}
		}
		return out;
	}

	public static final AttachmentType<DugColumn> DUG = AttachmentRegistry.<DugColumn>builder()
		.persistent(DugColumn.CODEC)
		.syncWith(DugColumn.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "dug"));

	/** Loads the class (registers the attachment) at mod start. */
	public static void init() {
		GmodCraft.LOG.info("GmodCraft: digging into GMod registered ({})", DUG.identifier());
		// D5: breaking a Minecraft block by hand digs the map surface passing through its cell too
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (level instanceof ServerLevel server && level.dimension() == Level.OVERWORLD) {
				SkyDigBlast.handBroke(server, pos);
			}
		});
	}

	public static DugColumn column(LevelChunk chunk) {
		DugColumn column = chunk.getAttached(DUG);
		return column != null ? column : DugColumn.EMPTY;
	}

	public static boolean isDug(Level level, int world, BlockPos pos) {
		return column(level.getChunkAt(pos)).isDug(world, pos.getX(), pos.getY(), pos.getZ());
	}

	// ---- server --------------------------------------------------------------------------------

	private static final double REACH = 8.0;

	private static boolean inReach(ServerPlayer player, BlockPos pos, double reach) {
		return player.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach;
	}

	/**
	 * A player mined Skyrim's geometry out of this cell (the cell the surface is in): it's gone,
	 * with the drops and the sound of mining what it was made of.
	 */
	public static void open(ServerPlayer player, int world, BlockPos pos, int material) {
		ServerLevel level = player.level();
		if (!digs() || !inReach(player, pos, REACH) || !level.isLoaded(pos) || player.isSpectator()
			|| dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ())) {  // a re-anchor moves this slot: nothing is dug meanwhile
			return;
		}
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		if (refusesThin(player, level, world, pos)) {
			return;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		BlockDeltas.dugChanged(level, pos); // the dug bits go to the host on the server link too
		BlockState state = materialState(material);
		if (!player.isCreative()) {
			ItemStack tool = player.getMainHandItem();
			ItemStack used = tool.copy();
			boolean harvest = player.hasCorrectToolForDrops(state);
			tool.mineBlock(level, state, pos, player);
			if (harvest) {
				state.getBlock().playerDestroy(level, player, pos, state, null, used);
			}
		}
		level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state));
	}

	/** Cells around a mined one that are wholly inside Skyrim's geometry: blocks now. */
	public static void reveal(ServerPlayer player, int world, List<BlockPos> cells, int[] materials) {
		if (!digs() || dev.gmodcraft.slot.SlotJobs.locked()) {
			return;
		}
		ServerLevel level = player.level();
		for (int i = 0; i < cells.size() && i < materials.length; i++) {
			BlockPos pos = cells.get(i);
			if (inReach(player, pos, REACH + 4.0) && level.isLoaded(pos) && (digThinWalls || nextToDug(level, world, pos))) {
				digCell(level, world, pos, materials[i]);
			}
		}
	}

	/**
	 * Is a face neighbour of this cell dug? Rule digThinWalls off: the client asks to reveal around a
	 * mined cell before it knows the server refused it (a thin wall), so only cells next to a dug one
	 * are revealed (else the thick floor under a refused wall would be dug).
	 */
	private static boolean nextToDug(ServerLevel level, int world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (level.isLoaded(n) && isDug(level, world, n)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether Minecraft digs into Skyrim at all: the pause menu's "GMod destruction" button, saved
	 * in config/gmodcraft.properties. Off, mining Skyrim's surfaces and explosions leave it alone (and
	 * breaking blocks in old holes digs no further); holes already dug stay. In a friend's world it's
	 * the host's setting that counts (their server does the digging).
	 */
	public static volatile boolean destruction = true;

	/**
	 * v24 (P8 WP3): the server rule digIntoMap (config/gmodcraft.properties, kAdminSetRules). Off, this
	 * server digs nothing into GMod's map, whatever the client's "GMod destruction" says.
	 */
	public static volatile boolean digIntoMap = true;

	/**
	 * v38: the server rule digThinWalls (default on). Off, players can't dig a cell of the map that is
	 * a thin wall or floor (see {@link ThinWall#thin}): digging one would cut every surface in it, the
	 * room's floor and ceiling too. Explosions don't dig them either (D4); reveals only happen next to a dug cell then.
	 */
	public static volatile boolean digThinWalls = true;

	/** The server's lookup for {@link ThinWall#thin}: its collision store and the level's dug cells. */
	private record ServerThin(SkyCollision store, ServerLevel level, int world) implements ThinWall.Lookup {
		@Override
		public int solidCount(int x, int y, int z) {
			return this.store.solidCount(new BlockPos(x, y, z));
		}

		@Override
		public int[] extents(int x, int y, int z) {
			VoxelShape shape = this.store.shapeAt(new BlockPos(x, y, z));
			if (shape == null || shape.isEmpty()) {
				return new int[] { 8, 8, 8 };
			}
			return new int[] { layers(shape, Direction.Axis.X), layers(shape, Direction.Axis.Y), layers(shape, Direction.Axis.Z) };
		}

		private static int layers(VoxelShape shape, Direction.Axis axis) {
			return (int) Math.round((shape.max(axis) - shape.min(axis)) * 8.0);
		}

		@Override
		public boolean isDug(int x, int y, int z) {
			BlockPos p = new BlockPos(x, y, z);
			return this.level.isLoaded(p) && SkyDig.isDug(this.level, this.world, p);
		}

		@Override
		public boolean terrain(int x, int y, int z) {
			List<SkyTri> near = new ArrayList<>();
			this.store.originalSurfacesNear(new AABB(x + 0.001, y + 0.001, z + 0.001, x + 0.999, y + 0.999, z + 0.999), near);
			for (SkyTri t : near) {
				if (t.terrain && !t.dynamic) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Rule digThinWalls off and this cell a thin wall ({@link ThinWall#thin}): the player is told on the
	 * action bar, and true (refused). False when it may be dug.
	 */
	public static boolean refusesThin(ServerPlayer player, ServerLevel level, int world, BlockPos pos) {
		if (digThinWalls || !ThinWall.thin(new ServerThin(SkyCollision.of(level), level, world), pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("gmodcraft.dig.too_thin"));
		return true;
	}

	/**
	 * D4: rule digThinWalls off and this cell a thin wall ({@link ThinWall#thin}) for a blast: it isn't
	 * dug (and stops the blast like a building). No message: nobody dug it by hand.
	 */
	static boolean thinForBlast(ServerLevel level, int world, BlockPos pos) {
		return !digThinWalls && ThinWall.thin(new ServerThin(SkyCollision.of(level), level, world), pos.getX(), pos.getY(), pos.getZ());
	}

	/** {@link ThinWall#fillable} over the server's collision store (D4). */
	static boolean fillable(ServerLevel level, int world, BlockPos pos) {
		ServerThin l = new ServerThin(SkyCollision.of(level), level, world);
		int count = l.solidCount(pos.getX(), pos.getY(), pos.getZ());
		return ThinWall.fillable(count, count > 0 && count <= ThinWall.THIN_MAX && l.terrain(pos.getX(), pos.getY(), pos.getZ()));
	}

	/** Does this server dig into GMod's map now (the server rule and the destruction toggle)? */
	public static boolean digs() {
		return destruction && digIntoMap;
	}

	/** Marks a cell dug out of Skyrim's geometry (in that Skyrim world). Returns false if it was already. */
	public static boolean markDug(ServerLevel level, int world, BlockPos pos) {
		if (dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ())) {
			return false;  // a re-anchor moves this slot (review finding 1): refused, the dig is lost rather than misplaced
		}
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		BlockDeltas.dugChanged(level, pos); // the dug bits go to the host on the server link too
		return true;
	}

	/** A cell wholly inside Skyrim's geometry: dug out of it, and the block it's made of put there. */
	public static void digCell(ServerLevel level, int world, BlockPos pos, int material) {
		if (HullWorld.isMirror(level, pos)) {
			return; // hull world: the map's block is already there (it is dug when it is mined)
		}
		if (!markDug(level, world, pos)) {
			return;
		}
		BlockState here = level.getBlockState(pos);
		// Keep whatever Minecraft block is already there (someone built into the ground).
		if (material > 0 && (here.isAir() || here.canBeReplaced())) {
			level.setBlock(pos, blockFor(level, pos, material), 3);
		}
	}

	/** The block a Proto.DIG_* material stands for (stone without ores). */
	public static BlockState materialState(int material) {
		return switch (material) {
			case DIG_GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
			case DIG_DIRT -> Blocks.DIRT.defaultBlockState();
			case DIG_COBBLE -> Blocks.COBBLESTONE.defaultBlockState();
			case DIG_SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
			case DIG_ICE -> Blocks.PACKED_ICE.defaultBlockState();
			case DIG_SAND -> Blocks.SAND.defaultBlockState();
			case DIG_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
			case DIG_MUD -> Blocks.MUD.defaultBlockState();
			case DIG_OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
			case DIG_SPRUCE_LOG -> Blocks.SPRUCE_LOG.defaultBlockState();
			case DIG_BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
			case DIG_PLANKS -> Blocks.SPRUCE_PLANKS.defaultBlockState();
			case DIG_METAL -> Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();
			case DIG_GLASS -> Blocks.GLASS.defaultBlockState();
			case DIG_ORGANIC -> Blocks.MOSS_BLOCK.defaultBlockState();
			case DIG_CLOTH -> Blocks.WOOL.pick(DyeColor.BROWN).defaultBlockState();
			case DIG_BONE -> Blocks.BONE_BLOCK.defaultBlockState();
			case DIG_WEB -> Blocks.COBWEB.defaultBlockState();
			case DIG_ASH -> Blocks.CONCRETE_POWDER.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
			case DIG_BEDROCK -> Blocks.BEDROCK.defaultBlockState();
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** The block a Proto.DIG_* material becomes here. Stone has ores in it. */
	public static BlockState blockFor(ServerLevel level, BlockPos pos, int material) {
		return material == DIG_STONE || material == DIG_NONE ? stoneOrOre(level.getSeed(), pos) : materialState(material);
	}

	/** Ores come in little clusters (a 2x2x2 cell picks one ore, then each block in it maybe). */
	static BlockState stoneOrOre(long seed, BlockPos pos) {
		long cell = mix(seed ^ mix(((long) (pos.getX() >> 1) * 73856093L) ^ ((long) (pos.getY() >> 1) * 19349663L) ^ ((long) (pos.getZ() >> 1) * 83492791L)));
		long block = mix(cell ^ ((pos.getX() & 1) | (pos.getY() & 1) << 1 | (pos.getZ() & 1) << 2));
		if ((block & 0xFF) >= 150) { // about 60% of a vein cell's blocks
			return Blocks.STONE.defaultBlockState();
		}
		int roll = (int) ((cell >>> 16) % 10000L);
		// Chances per vein cell, in 1/10000.
		if ((roll -= 330) < 0) {
			return Blocks.COAL_ORE.defaultBlockState();
		}
		if ((roll -= 220) < 0) {
			return Blocks.IRON_ORE.defaultBlockState();
		}
		if ((roll -= 170) < 0) {
			return Blocks.COPPER_ORE.defaultBlockState();
		}
		if ((roll -= 80) < 0) {
			return Blocks.REDSTONE_ORE.defaultBlockState();
		}
		if ((roll -= 45) < 0) {
			return Blocks.GOLD_ORE.defaultBlockState();
		}
		if ((roll -= 35) < 0) {
			return Blocks.LAPIS_ORE.defaultBlockState();
		}
		if ((roll -= 14) < 0) {
			return Blocks.DIAMOND_ORE.defaultBlockState();
		}
		if ((roll -= 5) < 0) {
			return Blocks.EMERALD_ORE.defaultBlockState();
		}
		return Blocks.STONE.defaultBlockState();
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	// ---- client: what's inside Skyrim's geometry ----------------------------------------------

	/** Leave the cell alone: Skyrim geometry that can't be dug (a building) is there. */
	public static final int KEEP = -1;
	/** Open air, or only partly inside Skyrim's geometry (its surface stays; walls are drawn). */
	public static final int AIR = 0;
	/** Blocks this far under the land's surface are bedrock. */
	public static final double BEDROCK_DEPTH = 5.0;
	/** A cell becomes a block only when this many of its 27 sample points are inside. */
	private static final int WHOLE = 25;

	private static final double SEARCH = 2.5;
	private static final double LAND_REACH = 16.0; // how far above a point the land is looked for
	private static final double[] SAMPLE = { 1.0 / 6.0, 0.5, 5.0 / 6.0 };

	/** Is a point inside Skyrim's geometry? Its nearest original surface (within SEARCH) decides. */
	/** Where a probe finds the host's surfaces as they were before digging (SkyCollision#originalSurfacesNear). */
	public interface Surfaces {
		void near(AABB box, List<SkyTri> out);
	}

	/** The host's collision voxels (SkyCollision's SHAPES): is this point inside one? */
	public interface Voxels {
		boolean solid(double x, double y, double z);
	}

	/** Above the land, its own surface layer of voxels (displacements are voxelized as surfaces) is this thick. */
	private static final double LAND_SKIN = 0.25;

	public static final class Probe {
		/** The land (terrain) around: a height field, so "below it" is "inside". */
		public final List<SkyTri> land = new ArrayList<>();
		/** Everything else (rocks, cliffs, cave walls, trees, buildings). */
		public final List<SkyTri> tris = new ArrayList<>();
		private final List<SkyTri> gathered = new ArrayList<>();
		private final double[] q = new double[3];
		private final Surfaces store;
		/** After {@link #test}: the surface deciding it (null: none near, deep inside). */
		public @Nullable SkyTri surface;
		/** After {@link #test}: how far behind that surface (infinite if none near). */
		public double depth;
		private double boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
		// Far above and below the box (gathered only if a point is far from every surface), and
		// that by block column.
		private @Nullable List<SkyTri> column;
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<SkyTri>> columns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
		// Per block: the surfaces that could be within SEARCH of a point in it. A blast's probe holds
		// thousands, nearly all far from any one point; kept in this.tris's order (ties go the same way).
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<SkyTri>> nearCells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

		/** A probe over one side's copy of the host's geometry (SkyCollision.CLIENT or SERVER). */
		public Probe(SkyCollision store) {
			this.store = store::originalSurfacesNear;
		}

		/** How far around a cell {@link #floating} wants no host surface at all (blocks). */
		static final double FLOATING_MARGIN = 1.5;

		/**
		 * D3 cleanup: is a dug cell holding a block one the old open-air rule made (blasts and reveals
		 * took open sky over a brush floor for rock)? Yes when no original host surface comes within
		 * {@link #FLOATING_MARGIN} of it and none of its 27 sample points is inside geometry. A block
		 * revealed deep in a thick rock or near any surface stays. (Here, not in SkyDig: runs without
		 * SkyDig's class init, for unit tests.)
		 */
		public static boolean floating(Surfaces store, int x, int y, int z) {
			List<SkyTri> near = new ArrayList<>();
			store.near(new AABB(x - FLOATING_MARGIN, y - FLOATING_MARGIN, z - FLOATING_MARGIN, x + 1 + FLOATING_MARGIN, y + 1 + FLOATING_MARGIN,
				z + 1 + FLOATING_MARGIN), near);
			for (SkyTri t : near) {
				if (!t.stairHelper) {
					return false;
				}
			}
			Probe probe = new Probe(store).around(x, y, z, x + 1, y + 1, z + 1);
			double[] samples = { 1.0 / 6.0, 0.5, 5.0 / 6.0 };
			for (double sy : samples) {
				for (double sz : samples) {
					for (double sx : samples) {
						if (probe.test(x + sx, y + sy, z + sz) != AIR) {
							return false;
						}
					}
				}
			}
			return true;
		}

		/** A probe over any source of original surfaces (tests). */
		public Probe(Surfaces surfaces) {
			this.store = surfaces;
		}

		/** Does part of a host entity (a prop, a door: it moves) reach into this box (touching it doesn't count)? */
		public boolean dynamicIn(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			for (SkyTri t : this.tris) {
				if (t.dynamic && t.maxX > minX && t.minX < maxX && t.maxY > minY && t.minY < maxY && t.maxZ > minZ && t.minZ < maxZ) {
					return true;
				}
			}
			return false;
		}

		/** Gathers the surfaces around a box (MC coords) once, for many tests inside it. */
		public Probe around(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			this.gathered.clear();
			this.land.clear();
			this.tris.clear();
			this.column = null;
			this.columns.clear();
			this.nearCells.clear();
			this.rayTris = null;
			this.boxMinX = minX;
			this.boxMinY = minY;
			this.boxMinZ = minZ;
			this.boxMaxX = maxX;
			this.boxMaxY = maxY;
			this.boxMaxZ = maxZ;
			// The land well above too: a point deep under it is still under it.
			this.store.near(new AABB(minX - SEARCH, minY - SEARCH, minZ - SEARCH, maxX + SEARCH, maxY + SEARCH + LAND_REACH, maxZ + SEARCH), this.gathered);
			for (SkyTri t : this.gathered) {
				if (t.stairHelper) {
					continue;
				}
				if (t.terrain) {
					this.land.add(t);
				} else if (t.maxY >= minY - SEARCH && t.minY <= maxY + SEARCH) {
					this.tris.add(t);
				}
			}
			return this;
		}

		/** The land's height at (x, z), or NaN where there's none near. */
		public double landHeight(double x, double z) {
			double best = Double.NaN;
			for (SkyTri t : this.land) {
				double h = t.heightAt(x, z);
				if (!Double.isNaN(h) && (Double.isNaN(best) || h > best)) {
					best = h;
					this.surface = t;
				}
			}
			return best;
		}

		/**
		 * What a dug cell's wall shows at a point just inside its neighbour: a Proto.DIG_* material, or
		 * AIR for nothing there. Solid is what the probe finds inside (under the land, behind a
		 * diggable surface) or what the host collides with there ({@code voxels}: any brush or model,
		 * diggable or not; null when a prop is near, since props move). Called after {@link #around}.
		 * Sets {@link #depth} for {@link SkyDig#underground}.
		 */
		public int wallMaterial(@Nullable Voxels voxels, double px, double py, double pz) {
			int r = this.test(px, py, pz);
			if (r > AIR) {
				return r;
			}
			SkyTri near = this.surface;
			if (voxels == null || !voxels.solid(px, py, pz)) {
				return AIR;
			}
			double ground = this.landHeight(px, pz);
			if (!Double.isNaN(ground) && py - ground < LAND_SKIN) {
				return AIR; // the land's surface layer: its part under the surface is drawn exactly
			}
			// D6, for any surface: the voxelizer's half-sub-voxel slack makes the host "solid" up to about 1/8
			// block in front of a surface (gm_construct's sidewalk: a sliver of the 0.4-block slab in the cell
			// above; a wall's foot: a strip over the hole along the wall). A point the probe finds in open air
			// just in front of its nearest surface is open air, unless rays from it show it lies inside
			// something else after all (brushes overlap:
			// gm_construct's garage, a slab z -173..-152 buried in the floor z -160..-144, whose top is the
			// nearest surface for the floor's upper part; a wall standing on the floor, its foot nearest the
			// floor's top). Before 2026-10-10 only the floor case (a surface facing up) was handled, and pieces
			// stood out of walls and roof rims.
			if (r == AIR && near != null) {
				closestPoint(near, px, py, pz, this.q);
				double dx = px - this.q[0], dy = py - this.q[1], dz = pz - this.q[2];
				if (dx * dx + dy * dy + dz * dz < LAND_SKIN * LAND_SKIN && !this.inside(px, py, pz)) {
					return AIR;
				}
			}
			this.depth = 0.5;
			return near != null && near.diggable && near.material != DIG_NONE ? near.material : DIG_STONE;
		}

		/**
		 * Is the first surface straight above the point, within {@code reach} blocks, a top (facing
		 * up)? Then the point is inside whatever that top belongs to. Called after {@link #around}.
		 */
		public boolean underTopFace(double px, double py, double pz, double reach) {
			SkyTri above = null;
			double aboveY = py + reach;
			for (SkyTri t : this.columnAt(px, pz)) {
				double h = t.heightAt(px, pz);
				if (!Double.isNaN(h) && h > py && h <= aboveY) {
					aboveY = h;
					above = t;
				}
			}
			return above != null && above.ny > 0.0;
		}

		/** How far {@link #inside}'s rays look. */
		static final double RAY_REACH = 8.0;
		private final List<SkyTri> ray = new ArrayList<>();

		/**
		 * Is the point inside the host's solids? Judged along six rays from it (the axes), each within
		 * {@link #RAY_REACH}: every surface a ray crosses counts, leaving a solid (meeting a surface from
		 * behind) +1, entering one -1, and the sum is how many solids the point lies in as far as that ray can
		 * tell. Brushes touch and overlap (gm_construct's wall foot: three convexes share faces at z 48 and
		 * 48.4), so the first surface met alone can't tell; a solid reaching past {@link #RAY_REACH} hides its
		 * far side from one ray, not from all six. The land (handled exactly elsewhere) and stair helpers don't
		 * count; crossings at one distance in one sense (a face's diagonal, an edge) count once.
		 */
		public boolean inside(double px, double py, double pz) {
			if (this.rayTris == null || px < this.rayMinX || px > this.rayMaxX || py < this.rayMinY || py > this.rayMaxY || pz < this.rayMinZ || pz > this.rayMaxZ) {
				this.gatherRayTris(px, py, pz);
			}
			// an axis ray from p can only meet a surface whose box holds p in the two other axes: one pass
			this.cross.clear();
			for (SkyTri t : this.rayTris) {
				boolean inX = t.minX <= px && px <= t.maxX, inY = t.minY <= py && py <= t.maxY, inZ = t.minZ <= pz && pz <= t.maxZ;
				if (inX ? inY || inZ : inY && inZ) {
					this.cross.add(t);
				}
			}
			return this.depthAlong(px, py, pz, 1, 0, 0) > 0 || this.depthAlong(px, py, pz, -1, 0, 0) > 0 || this.depthAlong(px, py, pz, 0, 1, 0) > 0
				|| this.depthAlong(px, py, pz, 0, -1, 0) > 0 || this.depthAlong(px, py, pz, 0, 0, 1) > 0 || this.depthAlong(px, py, pz, 0, 0, -1) > 0;
		}

		private int depthAlong(double px, double py, double pz, double dx, double dy, double dz) {
			double ex = px + dx * RAY_REACH, ey = py + dy * RAY_REACH, ez = pz + dz * RAY_REACH;
			double x0 = Math.min(px, ex), x1 = Math.max(px, ex), y0 = Math.min(py, ey), y1 = Math.max(py, ey), z0 = Math.min(pz, ez), z1 = Math.max(pz, ez);
			int n = 0;
			int depth = 0;
			for (SkyTri t : this.cross) {
				if (t.maxX < x0 || t.minX > x1 || t.maxY < y0 || t.minY > y1 || t.maxZ < z0 || t.minZ > z1) {
					continue;
				}
				double d = rayHit(t, px, py, pz, dx, dy, dz);
				if (!(d > 1e-6 && d < RAY_REACH)) {
					continue;
				}
				double facing = t.nx * dx + t.ny * dy + t.nz * dz;
				int sense = facing > 1e-9 ? 1 : facing < -1e-9 ? -1 : 0;
				if (sense == 0) {
					continue;
				}
				boolean seen = false;
				for (int h = 0; h < n; h++) {
					if (Math.abs(this.hitDist[h] - d) < 1e-6 && this.hitSense[h] == sense) {
						seen = true;
						break;
					}
				}
				if (!seen) {
					if (n == this.hitDist.length) {
						this.hitDist = java.util.Arrays.copyOf(this.hitDist, n * 2);
						this.hitSense = java.util.Arrays.copyOf(this.hitSense, n * 2);
					}
					this.hitDist[n] = d;
					this.hitSense[n++] = sense;
					depth += sense;
				}
			}
			return depth;
		}

		/** The surfaces {@link #inside}'s rays can meet from points around the box (once per {@link #around}). */
		private void gatherRayTris(double px, double py, double pz) {
			// the box given to around(), grown so its points' rays all stay inside the gathered region
			this.rayMinX = Math.min(this.boxMinX, px) - 1;
			this.rayMinY = Math.min(this.boxMinY, py) - 1;
			this.rayMinZ = Math.min(this.boxMinZ, pz) - 1;
			this.rayMaxX = Math.max(this.boxMaxX, px) + 1;
			this.rayMaxY = Math.max(this.boxMaxY, py) + 1;
			this.rayMaxZ = Math.max(this.boxMaxZ, pz) + 1;
			this.ray.clear();
			this.store.near(new AABB(this.rayMinX - RAY_REACH, this.rayMinY - RAY_REACH, this.rayMinZ - RAY_REACH, this.rayMaxX + RAY_REACH,
				this.rayMaxY + RAY_REACH, this.rayMaxZ + RAY_REACH), this.ray);
			this.rayTris = new ArrayList<>(this.ray.size());
			for (SkyTri t : this.ray) {
				if (!t.stairHelper && !t.terrain) {
					this.rayTris.add(t);
				}
			}
		}

		private @Nullable List<SkyTri> rayTris;
		private final List<SkyTri> cross = new ArrayList<>();
		private double rayMinX, rayMinY, rayMinZ, rayMaxX, rayMaxY, rayMaxZ;
		private double[] hitDist = new double[16];
		private int[] hitSense = new int[16];

		/** Distance along the ray to the triangle (Moller-Trumbore, both sides), or NaN. */
		static double rayHit(SkyTri t, double px, double py, double pz, double dx, double dy, double dz) {
			double e1x = t.bx - t.ax, e1y = t.by - t.ay, e1z = t.bz - t.az;
			double e2x = t.cx - t.ax, e2y = t.cy - t.ay, e2z = t.cz - t.az;
			double hx = dy * e2z - dz * e2y, hy = dz * e2x - dx * e2z, hz = dx * e2y - dy * e2x;
			double a = e1x * hx + e1y * hy + e1z * hz;
			if (Math.abs(a) < 1e-12) {
				return Double.NaN;
			}
			double f = 1.0 / a;
			double sx = px - t.ax, sy = py - t.ay, sz = pz - t.az;
			double u = f * (sx * hx + sy * hy + sz * hz);
			if (u < 0.0 || u > 1.0) {
				return Double.NaN;
			}
			double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
			double v = f * (dx * qx + dy * qy + dz * qz);
			if (v < 0.0 || u + v > 1.0) {
				return Double.NaN;
			}
			return f * (e2x * qx + e2y * qy + e2z * qz);
		}

		/**
		 * {@link #wallMaterial} result: the piece shows the neighbour's own block. A hull world's mirror
		 * block stands for the map's solid, but the map's collision may cover only part of its cell (a
		 * floor slab thinner than a block, with nothing under it); where it has nothing and the point
		 * isn't standing in open air over a floor, the hole still has a wall there.
		 */
		public static final int MIRROR_FACE = -2;

		/**
		 * Does a dug cell get a wall toward its neighbour? Not when the dug cell is filled again (a solid
		 * block in it), nor toward a dug neighbour or a Minecraft block that draws its own faces; a
		 * mirror block is solid in Minecraft but not drawn in GMod (the map is), so it does get one.
		 */
		public static boolean wallBetween(boolean cellDug, boolean cellOccludes, boolean nbrDug, boolean nbrOccludes, boolean nbrMirror) {
			return cellDug && !cellOccludes && !nbrDug && (!nbrOccludes || nbrMirror);
		}

		/**
		 * {@link #wallMaterial} for a wall toward a mirror block ({@code mirror}): where the host has
		 * nothing, {@link #MIRROR_FACE} unless the point is in open air over a floor or the land.
		 */
		public int wallMaterial(@Nullable Voxels voxels, boolean mirror, double px, double py, double pz) {
			int m = this.wallMaterial(voxels, px, py, pz);
			if (m > AIR || !mirror) {
				return m;
			}
			return this.overFloor(px, py, pz) ? AIR : MIRROR_FACE;
		}

		/**
		 * Is the point in open air: over the land, or over the top of something (the nearest surface
		 * straight down faces up)? Under a slab with nothing below it (the void under a map's floor)
		 * it isn't. Called after {@link #around}.
		 */
		public boolean overFloor(double px, double py, double pz) {
			double ground = this.landHeight(px, pz);
			if (!Double.isNaN(ground) && py >= ground) {
				return true;
			}
			SkyTri below = null;
			double belowY = Double.NEGATIVE_INFINITY;
			for (SkyTri t : this.columnAt(px, pz)) {
				double h = t.heightAt(px, pz);
				if (!Double.isNaN(h) && h <= py && h > belowY) {
					belowY = h;
					below = t;
				}
			}
			return below != null && below.ny > 0.0;
		}

		/** KEEP (inside something that stays), AIR (in front of a surface) or a material (inside). */
		public int test(double px, double py, double pz) {
			// Under the land: inside, whatever else is buried nearby (rocks, roots).
			double ground = landHeight(px, pz);
			SkyTri landSurface = this.surface;
			if (!Double.isNaN(ground) && py < ground) {
				this.surface = landSurface;
				this.depth = ground - py;
				return landSurface.material == DIG_NONE ? DIG_STONE : landSurface.material;
			}
			// Above the land (or where there's none, as in interiors): inside an object if behind its
			// nearest surface (surfaces face out of their solid side).
			SkyTri nearest = null;
			double best = SEARCH * SEARCH;
			double bx = 0, by = 0, bz = 0;
			for (SkyTri t : nearCell(px, py, pz)) {
				// Its box already farther than the best so far: it can't be nearer.
				double ex = Math.max(0.0, Math.max(t.minX - px, px - t.maxX));
				double ey = Math.max(0.0, Math.max(t.minY - py, py - t.maxY));
				double ez = Math.max(0.0, Math.max(t.minZ - pz, pz - t.maxZ));
				if (ex * ex + ey * ey + ez * ez >= best) {
					continue;
				}
				closestPoint(t, px, py, pz, this.q);
				double dx = px - this.q[0], dy = py - this.q[1], dz = pz - this.q[2];
				double d2 = dx * dx + dy * dy + dz * dz;
				if (d2 < best) {
					best = d2;
					nearest = t;
					bx = this.q[0];
					by = this.q[1];
					bz = this.q[2];
				}
			}
			this.surface = nearest;
			if (nearest == null) {
				this.depth = Double.POSITIVE_INFINITY;
				return farFromSurfaces(px, py, pz, ground);
			}
			if ((px - bx) * nearest.nx + (py - by) * nearest.ny + (pz - bz) * nearest.nz >= 0.0) {
				return AIR;
			}
			if (!nearest.diggable) {
				return KEEP;
			}
			this.depth = Math.sqrt(best);
			return nearest.material == DIG_NONE ? DIG_STONE : nearest.material;
		}

		/**
		 * A point with no surface near (open sky, the middle of a big room, deep in rock): straight
		 * up decides. Under the top of something (a surface facing up, away from it) it's inside
		 * that; under an underside (an overhang, a ceiling) or nothing at all it's in the open, as
		 * long as there's land under it, or a floor: D3, the nearest surface under it faces up (a brush
		 * plaza, as on gm_bigcity, isn't terrain; open sky over it was rock, and blasts made blocks
		 * there). No ground anywhere (interiors): deep in the rock.
		 */
		private int farFromSurfaces(double px, double py, double pz, double ground) {
			boolean landBelow = !Double.isNaN(ground);
			SkyTri above = null, below = null;
			double aboveY = Double.POSITIVE_INFINITY, belowY = Double.NEGATIVE_INFINITY;
			for (SkyTri t : columnAt(px, pz)) {
				double h = t.heightAt(px, pz);
				if (Double.isNaN(h)) {
					continue;
				}
				if (h > py) {
					if (h < aboveY) {
						aboveY = h;
						above = t;
					}
				} else {
					if (t.terrain) {
						landBelow = true;
					}
					if (h > belowY) {
						belowY = h;
						below = t;
					}
				}
			}
			if (below != null && below.ny > 0.0) {
				landBelow = true; // over a floor's top (a brush, a prop): open air
			}
			if (above != null && above.ny > 0.0) {
				this.surface = above;
				this.depth = aboveY - py;
				if (!above.diggable) {
					return KEEP;
				}
				return above.material == DIG_NONE ? DIG_STONE : above.material;
			}
			return above != null || landBelow ? AIR : DIG_STONE;
		}

		private List<SkyTri> nearCell(double px, double py, double pz) {
			int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
			long key = net.minecraft.core.BlockPos.asLong(cx, cy, cz);
			List<SkyTri> list = this.nearCells.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.tris) {
					if (t.maxX >= cx - SEARCH && t.minX <= cx + 1 + SEARCH && t.maxY >= cy - SEARCH && t.minY <= cy + 1 + SEARCH && t.maxZ >= cz - SEARCH
						&& t.minZ <= cz + 1 + SEARCH) {
						list.add(t);
					}
				}
				this.nearCells.put(key, list);
			}
			return list;
		}

		/** The surfaces over or under the block column holding (x, z). */
		private List<SkyTri> columnAt(double x, double z) {
			if (this.column == null) {
				List<SkyTri> all = new ArrayList<>();
				this.store.near(new AABB(Math.min(x, this.boxMinX), this.boxMinY - COLUMN_REACH, Math.min(z, this.boxMinZ),
					Math.max(x, this.boxMaxX), this.boxMaxY + COLUMN_REACH, Math.max(z, this.boxMaxZ)), all);
				this.column = new ArrayList<>();
				for (SkyTri t : all) {
					if (!t.stairHelper && Math.abs(t.ny) >= 0.05) {
						this.column.add(t);
					}
				}
			}
			int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
			long key = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
			List<SkyTri> list = this.columns.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.column) {
					if (t.maxX >= bx && t.minX <= bx + 1 && t.maxZ >= bz && t.minZ <= bz + 1) {
						list.add(t);
					}
				}
				this.columns.put(key, list);
			}
			return list;
		}

		/** Closest point on the triangle to p (Ericson, Real-Time Collision Detection 5.1.5). */
		// Static here, not in SkyDig: a probe runs without SkyDig's class init (unit tests).
		static void closestPoint(SkyTri t, double px, double py, double pz, double[] out) {
			double abx = t.bx - t.ax, aby = t.by - t.ay, abz = t.bz - t.az;
			double acx = t.cx - t.ax, acy = t.cy - t.ay, acz = t.cz - t.az;
			double apx = px - t.ax, apy = py - t.ay, apz = pz - t.az;
			double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
			if (d1 <= 0 && d2 <= 0) {
				set(out, t.ax, t.ay, t.az);
				return;
			}
			double bpx = px - t.bx, bpy = py - t.by, bpz = pz - t.bz;
			double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
			if (d3 >= 0 && d4 <= d3) {
				set(out, t.bx, t.by, t.bz);
				return;
			}
			double vc = d1 * d4 - d3 * d2;
			if (vc <= 0 && d1 >= 0 && d3 <= 0) {
				double v = d1 / (d1 - d3);
				set(out, t.ax + abx * v, t.ay + aby * v, t.az + abz * v);
				return;
			}
			double cpx = px - t.cx, cpy = py - t.cy, cpz = pz - t.cz;
			double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
			if (d6 >= 0 && d5 <= d6) {
				set(out, t.cx, t.cy, t.cz);
				return;
			}
			double vb = d5 * d2 - d1 * d6;
			if (vb <= 0 && d2 >= 0 && d6 <= 0) {
				double w = d2 / (d2 - d6);
				set(out, t.ax + acx * w, t.ay + acy * w, t.az + acz * w);
				return;
			}
			double va = d3 * d6 - d5 * d4;
			if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
				double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
				set(out, t.bx + (t.cx - t.bx) * w, t.by + (t.cy - t.by) * w, t.bz + (t.cz - t.bz) * w);
				return;
			}
			double denom = 1.0 / (va + vb + vc);
			double v = vb * denom, w = vc * denom;
			set(out, t.ax + abx * v + acx * w, t.ay + aby * v + acy * w, t.az + abz * v + acz * w);
		}

		private static void set(double[] out, double x, double y, double z) {
			out[0] = x;
			out[1] = y;
			out[2] = z;
		}
	}

	private static final double COLUMN_REACH = 96.0; // how far up and down a lone point looks

	/**
	 * Is this cell wholly inside Skyrim's geometry, and if so what block is it (a Proto.DIG_*
	 * material)? Cells only partly inside stay Skyrim's (AIR): its surface stays, and the part
	 * under it shows as Minecraft walls wherever it's been dug next to (see the client's DigWalls).
	 * Only for cells next to a dug one: far from every surface means deep inside there.
	 */
	public static int classify(SkyCollision store, int x, int y, int z) {
		Probe probe = new Probe(store).around(x, y, z, x + 1, y + 1, z + 1);
		int inside = 0;
		int centreMaterial = DIG_STONE;
		double centreDepth = Double.POSITIVE_INFINITY;
		for (double sy : SAMPLE) {
			for (double sz : SAMPLE) {
				for (double sx : SAMPLE) {
					int r = probe.test(x + sx, y + sy, z + sz);
					if (r == KEEP) {
						return KEEP;
					}
					if (r == AIR) {
						continue;
					}
					inside++;
					if (sx == 0.5 && sy == 0.5 && sz == 0.5) {
						centreMaterial = r;
						centreDepth = probe.depth;
					}
				}
			}
		}
		if (inside < WHOLE) {
			return AIR;
		}
		// Under the land: dirt, stone, then bedrock a few blocks down.
		SkyTri land = landAbove(store, x + 0.5, y + 0.5, z + 0.5);
		if (land != null) {
			double depth = land.heightAt(x + 0.5, z + 0.5) - (y + 0.5);
			if (depth >= BEDROCK_DEPTH) {
				return DIG_BEDROCK;
			}
			if (Double.isInfinite(centreDepth)) {
				centreMaterial = land.material == DIG_NONE ? DIG_STONE : land.material;
				centreDepth = depth;
			}
		}
		return underground(centreMaterial, centreDepth);
	}

	/** The nearest stretch of land (Skyrim's terrain) above a point, within 32 blocks, or null. */
	public static @Nullable SkyTri landAbove(SkyCollision store, double x, double y, double z) {
		List<SkyTri> column = new ArrayList<>();
		store.originalSurfacesNear(new AABB(x - 0.01, y, z - 0.01, x + 0.01, y + 32, z + 0.01), column);
		SkyTri best = null;
		double bestHeight = Double.POSITIVE_INFINITY;
		for (SkyTri t : column) {
			if (!t.terrain) {
				continue;
			}
			double h = t.heightAt(x, z);
			if (!Double.isNaN(h) && h >= y && h < bestHeight) {
				bestHeight = h;
				best = t;
			}
		}
		return best;
	}

	/** What's this deep behind a surface of this material: grass has dirt under it, then stone. */
	public static int underground(int surface, double depth) {
		return switch (surface) {
			case DIG_GRASS, DIG_DIRT -> depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_MUD -> depth < 3.5 ? DIG_MUD : DIG_STONE;
			case DIG_SNOW -> depth < 1.5 ? DIG_SNOW : depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_SAND -> depth < 3.5 ? DIG_SAND : DIG_STONE;
			case DIG_GRAVEL -> depth < 2.5 ? DIG_GRAVEL : DIG_STONE;
			case DIG_ASH -> depth < 3.5 ? DIG_ASH : DIG_STONE;
			case DIG_ICE -> depth < 2.5 ? DIG_ICE : DIG_STONE;
			case DIG_COBBLE -> depth < 1.5 ? DIG_COBBLE : DIG_STONE;
			case DIG_OAK_LOG, DIG_SPRUCE_LOG, DIG_BIRCH_LOG -> surface;
			case DIG_PLANKS -> depth < 2.5 ? DIG_PLANKS : DIG_STONE;
			case DIG_METAL -> depth < 1.5 ? DIG_METAL : DIG_STONE;
			case DIG_GLASS, DIG_ORGANIC, DIG_CLOTH, DIG_BONE, DIG_WEB -> depth < 1.0 ? surface : DIG_STONE;
			default -> DIG_STONE;
		};
	}

	// ---- collision for the walls of dug holes -------------------------------------------------

	// Worked-out walls by section (they depend only on the host's surfaces, not on what's dug), shared
	// by every thread; one cache per side's store. NO_WALL stands for "none" (a map can't hold null).
	private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, VoxelShape>> CLIENT_WALLS =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, VoxelShape>> SERVER_WALLS =
		new java.util.concurrent.ConcurrentHashMap<>();

	private static java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, VoxelShape>> walls(SkyCollision store) {
		return store == SkyCollision.CLIENT ? CLIENT_WALLS : SERVER_WALLS;
	}
	private static final VoxelShape NO_WALL = Shapes.empty();

	/** The host's world changed: every wall of this side is worked out again. */
	public static void wallsChanged(SkyCollision store) {
		walls(store).clear();
	}

	/** Skyrim's surfaces in this box changed: the walls that could see them are worked out again. */
	public static void wallsChanged(SkyCollision store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		var cache = walls(store);
		int reach = (int) Math.ceil(SEARCH) + 1;
		int down = reach + (int) Math.ceil(LAND_REACH); // a cell looks this far up for the land over it
		for (int sx = (minX - reach) >> 4; sx <= (maxX + reach) >> 4; sx++) {
			for (int sy = (minY - down) >> 4; sy <= (maxY + reach) >> 4; sy++) {
				for (int sz = (minZ - reach) >> 4; sz <= (maxZ + reach) >> 4; sz++) {
					cache.remove(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
				}
			}
		}
	}

	private static int worldFor(Level level) {
		if (level.isClientSide()) {
			DugLookup lookup = clientDug;
			return lookup != null ? clientWorld : 0;
		}
		return dev.gmodcraft.ServerHost.worldId(); // read from the server link every server tick
	}

	/** Set by the client with clientDug: the Skyrim world its dug cells are for. */
	public static volatile int clientWorld;

	private static @Nullable DugColumn columnFor(net.minecraft.world.level.CollisionGetter level, int x, int z) {
		var chunk = level.getChunkForCollisions(x >> 4, z >> 4);
		return chunk instanceof LevelChunk lc ? lc.getAttached(DUG) : null;
	}

	/**
	 * The solid part of an undug cell next to a dug one: under the land's surface (the wall of a
	 * hole, drawn by the client's DigWalls), which Skyrim's own geometry (just a surface) doesn't
	 * make solid. Null for none. Any thread.
	 */
	public static @Nullable VoxelShape wallShape(net.minecraft.world.level.CollisionGetter getter, BlockPos pos) {
		return wallShape(getter, pos, false);
	}

	/**
	 * A hull world's mirror block (BlockCollisionsMixin): its wall as for air, and a full cube where it
	 * borders a dug cell but the host's geometry gives no wall there (the hull fill under terrain and in
	 * the map's solid: a hole's floor and walls exist only in Minecraft, see MirrorExposure).
	 */
	public static @Nullable VoxelShape mirrorWallShape(net.minecraft.world.level.CollisionGetter getter, BlockPos pos) {
		return wallShape(getter, pos, true);
	}

	private static @Nullable VoxelShape wallShape(net.minecraft.world.level.CollisionGetter getter, BlockPos pos, boolean mirror) {
		if (!(getter instanceof Level level)) {
			return null;
		}
		SkyCollision store = SkyCollision.of(level);
		if (!store.active() && !mirror) {
			return null;
		}
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		DugColumn here = columnFor(getter, x, z);
		int world = worldFor(level);
		if (here != null && here.isDug(world, x, y, z)) {
			return null;
		}
		boolean nextToDug = false;
		for (Direction d : Direction.values()) {
			DugColumn column = d.getAxis() == Direction.Axis.Y ? here : columnFor(getter, x + d.getStepX(), z + d.getStepZ());
			if (column != null && column.isDug(world, x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
				nextToDug = true;
				break;
			}
		}
		if (!nextToDug) {
			return null;
		}
		if (!store.active()) {
			return Shapes.block(); // a mirror block: no host geometry to shape a wall from
		}
		var cache = walls(store);
		if (cache.size() > 2048) {
			cache.clear();
		}
		var shapes = cache.computeIfAbsent(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new java.util.concurrent.ConcurrentHashMap<>());
		long key = pos.asLong();
		VoxelShape cached = shapes.get(key);
		if (cached != null) {
			return cached == NO_WALL ? (mirror ? Shapes.block() : null) : cached;
		}
		VoxelShape shape = computeWall(store, x, y, z);
		shapes.put(key, shape == null ? NO_WALL : shape);
		return shape == null && mirror ? Shapes.block() : shape;
	}

	private static @Nullable VoxelShape computeWall(SkyCollision store, int x, int y, int z) {
		Probe probe = new Probe(store).around(x, y, z, x + 1, y + 1, z + 1);
		if (!probe.land.isEmpty()) {
			// Solid up to the lowest the land gets over the cell (never above the ground).
			double lowest = Double.POSITIVE_INFINITY;
			for (double[] c : new double[][] { { 0.05, 0.05 }, { 0.95, 0.05 }, { 0.05, 0.95 }, { 0.95, 0.95 }, { 0.5, 0.5 } }) {
				double h = probe.landHeight(x + c[0], z + c[1]);
				if (!Double.isNaN(h)) {
					lowest = Math.min(lowest, h);
				}
			}
			if (Double.isFinite(lowest) && lowest - y > 0.05) {
				return lowest - y >= 0.999 ? Shapes.block() : Shapes.box(0, 0, 0, 1, lowest - y, 1);
			}
			if (probe.tris.isEmpty()) {
				return null;
			}
		}
		// Rocks, cave walls: solid if the middle is inside.
		return probe.test(x + 0.5, y + 0.5, z + 0.5) > AIR ? Shapes.block() : null;
	}

	/**
	 * Looks up whether a cell is dug in the client's world (set by the client; null on a server).
	 * Used by the crosshair pick to hit the walls of dug holes.
	 */
	public interface DugLookup {
		boolean isDug(int x, int y, int z);
	}

	public static volatile @Nullable DugLookup clientDug;
}
