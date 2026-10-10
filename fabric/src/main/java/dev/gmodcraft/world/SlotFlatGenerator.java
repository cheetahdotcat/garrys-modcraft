package dev.gmodcraft.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.GmodCraftConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * gmodcraft:slot_flat (P8 WP3): the flat world types. A flat surface with its grass block at
 * floorY - 1, so a map whose floor sits on floorY (MapSlots) stands on it; under it (W1) a deep
 * vanilla-like column down to jagged bedrock at y -64 with ore veins (DeepColumns, seeded by the world
 * seed). void_maps (gmodcraft:flat_void_maps): columns inside the maps' footprints stay void
 * (FlatColumns); without it (gmodcraft:flat_everywhere) the layers run under the maps too. No
 * features, structures or mobs.
 *
 * <p>floor_y is stored with the world (level.dat); a world preset leaves it out, so a NEW world takes
 * the config's floorY when it is made.
 */
public final class SlotFlatGenerator extends ChunkGenerator {
	public static final MapCodec<SlotFlatGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
		Codec.BOOL.optionalFieldOf("void_maps", true).forGetter(SlotFlatGenerator::voidMaps),
		Codec.INT.optionalFieldOf("floor_y").forGetter(g -> Optional.of(g.floorY))
	).apply(i, (b, v, f) -> new SlotFlatGenerator(b, v, f.orElseGet(GmodCraftConfig::floorY))));

	/** DeepColumns' block codes. */
	private static final BlockState[] STATES = new BlockState[DeepColumns.CODES];

	static {
		STATES[DeepColumns.AIR] = Blocks.AIR.defaultBlockState();
		STATES[DeepColumns.BEDROCK] = Blocks.BEDROCK.defaultBlockState();
		STATES[DeepColumns.DIRT] = Blocks.DIRT.defaultBlockState();
		STATES[DeepColumns.GRASS] = Blocks.GRASS_BLOCK.defaultBlockState();
		STATES[DeepColumns.STONE] = Blocks.STONE.defaultBlockState();
		STATES[DeepColumns.DEEPSLATE] = Blocks.DEEPSLATE.defaultBlockState();
		STATES[DeepColumns.GRAVEL] = Blocks.GRAVEL.defaultBlockState();
		STATES[DeepColumns.ANDESITE] = Blocks.ANDESITE.defaultBlockState();
		STATES[DeepColumns.DIORITE] = Blocks.DIORITE.defaultBlockState();
		STATES[DeepColumns.GRANITE] = Blocks.GRANITE.defaultBlockState();
		STATES[DeepColumns.TUFF] = Blocks.TUFF.defaultBlockState();
		Block[][] ores = {
			{ Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE }, { Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE },
			{ Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE }, { Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE },
			{ Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE }, { Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE },
			{ Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE },
		};
		for (int o = 0; o < DeepColumns.ORES; o++) {
			STATES[DeepColumns.ORE_BASE + 2 * o] = ores[o][0].defaultBlockState();
			STATES[DeepColumns.ORE_BASE + 2 * o + 1] = ores[o][1].defaultBlockState();
		}
	}

	/** The block state of a DeepColumns code. */
	static BlockState state(int code) {
		return STATES[code];
	}

	private final boolean voidMaps;
	private final int floorY;

	public SlotFlatGenerator(BiomeSource biomeSource, boolean voidMaps, int floorY) {
		super(biomeSource);
		this.voidMaps = voidMaps;
		this.floorY = floorY;
	}

	public boolean voidMaps() {
		return this.voidMaps;
	}

	public int floorY() {
		return this.floorY;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	private boolean isVoid(int x, int z) {
		return this.voidMaps && FlatColumns.isVoid(FlatColumns.current(), x, z);
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structures,
		BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> biomeSet) {
		long t0 = TIMING ? System.nanoTime() : 0L;
		fill(chunk, randomState);
		if (TIMING) {
			GEN_NANOS.add(System.nanoTime() - t0);
			GEN_CHUNKS.increment();
			long n = GEN_CHUNKS.sum();
			if (n >= 16 && Long.bitCount(n) == 1) {
				dev.gmodcraft.GmodCraft.LOG.info("GmodCraft flat gen timing: {} chunks, avg {} us/chunk", n, GEN_NANOS.sum() / 1000 / n);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	/** GMODCRAFT_GEN_TIMING=1: log the average buildTerrain time at 16, 32, 64, ... chunks (W1 cost measurement). */
	private static final boolean TIMING = "1".equals(System.getenv("GMODCRAFT_GEN_TIMING"));
	private static final java.util.concurrent.atomic.LongAdder GEN_NANOS = new java.util.concurrent.atomic.LongAdder(), GEN_CHUNKS = new java.util.concurrent.atomic.LongAdder();

	private void fill(ChunkAccess chunk, RandomState randomState) {
		int lo = Math.max(chunk.getMinY(), DeepColumns.bottom(this.floorY)), hi = Math.min(chunk.getMaxY(), this.floorY - 1);
		if (hi < lo) {
			return;
		}
		FlatColumns.Snapshot snap = FlatColumns.current();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		boolean[] voids = null;
		if (this.voidMaps) {
			voids = new boolean[256];
			int n = 0;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (FlatColumns.isVoid(snap, bx + x, bz + z)) {
						voids[z * 16 + x] = true;
						n++;
					}
				}
			}
			if (n == 256) {
				return;
			}
		}
		int height = hi - lo + 1;
		int[] codes = new int[height * 256];
		DeepColumns.fillChunk(randomState.seed(), this.floorY, chunk.getPos().x(), chunk.getPos().z(), voids, lo, height, codes);
		// straight into the sections (as NoiseBasedChunkGenerator does): ~30k blocks a chunk
		for (int si = chunk.getSectionIndex(lo); si <= chunk.getSectionIndex(hi); si++) {
			LevelChunkSection section = chunk.getSection(si);
			int sy = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
			section.acquire();
			try {
				for (int y = Math.max(lo, sy); y <= Math.min(hi, sy + 15); y++) {
					int row = (y - lo) * 256;
					for (int i = 0; i < 256; i++) {
						int c = codes[row + i];
						if (c != DeepColumns.AIR) {
							section.setBlockState(i & 15, y - sy, i >> 4, STATES[c], false);
						}
					}
				}
			} finally {
				section.release();
			}
		}
		Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		for (int i = 0; i < 256; i++) {
			for (int y = hi; y >= lo; y--) {
				int c = codes[(y - lo) * 256 + i];
				if (c != DeepColumns.AIR) {
					ocean.update(i & 15, y, i >> 4, STATES[c]);
					surface.update(i & 15, y, i >> 4, STATES[c]);
					break;
				}
			}
		}
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		// the top block (grass, opaque for every heightmap type) at floorY - 1
		if (isVoid(x, z) || this.floorY - 1 < Math.max(level.getMinY(), DeepColumns.BEDROCK_Y) || this.floorY - 1 > level.getMaxY()) {
			return level.getMinY();
		}
		return this.floorY;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		BlockState[] states = new BlockState[level.getHeight()];
		Arrays.fill(states, Blocks.AIR.defaultBlockState());
		if (!isVoid(x, z)) {
			// the column without veins
			int lo = Math.max(level.getMinY(), DeepColumns.BEDROCK_Y), hi = Math.min(level.getMaxY(), this.floorY - 1);
			for (int y = lo; y <= hi; y++) {
				states[y - level.getMinY()] = STATES[DeepColumns.baseAt(randomState.seed(), this.floorY, x, y, z)];
			}
		}
		return new NoiseColumn(level.getMinY(), states);
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor level) {
		return this.floorY;
	}

	@Override
	public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
		return ChunkGeneratorStructureState.createForFlat(randomState, seed, getOrigin(randomState), this.biomeSource, java.util.stream.Stream.empty());
	}

	@Override
	public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
		StructureTemplateManager templates, ResourceKey<Level> dimension) {
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
		info.add("GmodCraft flat: floor y " + this.floorY + (this.voidMaps ? ", void under maps" : ", everywhere") + (isVoid(pos.getX(), pos.getZ()) ? " (void here)" : ""));
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getMinY() {
		return 0;
	}

	@Override
	public int getGenDepth() {
		return 384;
	}

	@Override
	public int getSeaLevel() {
		return -63;
	}
}
