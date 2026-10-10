package dev.gmodcraft.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.GmodCraftConfig;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * gmodcraft:underground (W2, roadmap 0.5 "mining below the map"): vanilla overworld terrain (noise,
 * surface rules, carvers: caves, aquifers, noise ore veins, deepslate; the vanilla overworld biome
 * source) masked by UndergroundColumns: air from floorY up, a 4-block stone cap under the maps'
 * footprints, the flat_everywhere grass/dirt surface elsewhere, bedrock at y -64 and air under it.
 * No biome decoration (W1's veins stand in for vanilla's ores), no structures, no original mobs.
 *
 * <p>A subclass, not a wrapper (access widener): ChunkMap builds the level's RandomState from the
 * noise settings only for an instanceof NoiseBasedChunkGenerator. floor_y is stored with the world;
 * the preset leaves it out, so a NEW world takes the config's floorY.
 */
public final class UndergroundGenerator extends NoiseBasedChunkGenerator {
	public static final MapCodec<UndergroundGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
		NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings),
		Codec.INT.optionalFieldOf("floor_y").forGetter(g -> Optional.of(g.floorY))
	).apply(i, (b, s, f) -> new UndergroundGenerator(b, s, f.orElseGet(GmodCraftConfig::floorY))));

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** Vanilla blocks W1's veins may replace, as DeepColumns codes. */
	private static final Map<Block, Integer> CODE_OF = new IdentityHashMap<>();

	static {
		CODE_OF.put(Blocks.STONE, DeepColumns.STONE);
		CODE_OF.put(Blocks.DEEPSLATE, DeepColumns.DEEPSLATE);
		CODE_OF.put(Blocks.ANDESITE, DeepColumns.ANDESITE);
		CODE_OF.put(Blocks.DIORITE, DeepColumns.DIORITE);
		CODE_OF.put(Blocks.GRANITE, DeepColumns.GRANITE);
		CODE_OF.put(Blocks.TUFF, DeepColumns.TUFF);
	}

	private final int floorY;

	public UndergroundGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int floorY) {
		super(biomeSource, settings);
		this.floorY = floorY;
	}

	public int floorY() {
		return this.floorY;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structures,
		BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> biomeSet) {
		long t0 = TIMING ? System.nanoTime() : 0L;
		return super.buildTerrain(chunk, blender, randomState, structures, biomes, region, biomeSet).thenApply(c -> {
			long t1 = TIMING ? System.nanoTime() : 0L;
			mask(c, randomState.seed());
			if (TIMING) {
				long t2 = System.nanoTime();
				VANILLA_NANOS.add(t1 - t0);
				MASK_NANOS.add(t2 - t1);
				GEN_CHUNKS.increment();
				long n = GEN_CHUNKS.sum();
				if (n >= 16 && Long.bitCount(n) == 1) {
					dev.gmodcraft.GmodCraft.LOG.info("GmodCraft underground gen timing: {} chunks, avg {} us/chunk (vanilla terrain {} us incl. queueing, mask {} us)", n,
						(VANILLA_NANOS.sum() + MASK_NANOS.sum()) / 1000 / n, VANILLA_NANOS.sum() / 1000 / n, MASK_NANOS.sum() / 1000 / n);
				}
			}
			return c;
		});
	}

	/** GMODCRAFT_GEN_TIMING=1: log the average buildTerrain time at 16, 32, 64, ... chunks (W2 cost). */
	private static final boolean TIMING = "1".equals(System.getenv("GMODCRAFT_GEN_TIMING"));
	private static final java.util.concurrent.atomic.LongAdder VANILLA_NANOS = new java.util.concurrent.atomic.LongAdder(),
		MASK_NANOS = new java.util.concurrent.atomic.LongAdder(), GEN_CHUNKS = new java.util.concurrent.atomic.LongAdder();

	private void mask(ChunkAccess chunk, long seed) {
		FlatColumns.Snapshot snap = FlatColumns.current();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		boolean[] foot = new boolean[256];
		for (int i = 0; i < 256; i++) {
			foot[i] = FlatColumns.isVoid(snap, bx + (i & 15), bz + (i >> 4));
		}
		// air above the floor and under the bedrock (whole sections at a time where they're empty already)
		clear(chunk, Math.max(chunk.getMinY(), this.floorY), chunk.getMaxY());
		clear(chunk, chunk.getMinY(), Math.min(chunk.getMaxY(), DeepColumns.BEDROCK_Y - 1));
		int lo = Math.max(chunk.getMinY(), DeepColumns.BEDROCK_Y), hi = Math.min(chunk.getMaxY(), this.floorY - 1);
		if (hi >= lo) {
			int height = hi - lo + 1;
			int[] codes = new int[height * 256];
			forEach(chunk, lo, hi, (section, y, i) -> {
				Integer c = CODE_OF.get(section.getBlockState(i & 15, y & 15, i >> 4).getBlock());
				codes[(y - lo) * 256 + i] = c == null ? UndergroundColumns.KEEP : c;
			});
			UndergroundColumns.fill(seed, this.floorY, chunk.getPos().x(), chunk.getPos().z(), foot, lo, height, codes);
			forEach(chunk, lo, hi, (section, y, i) -> {
				int c = codes[(y - lo) * 256 + i];
				if (c != UndergroundColumns.KEEP) {
					section.setBlockState(i & 15, y & 15, i >> 4, SlotFlatGenerator.state(c), false);
				}
			});
		}
		Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
	}

	private interface Cell {
		void at(LevelChunkSection section, int y, int i);
	}

	/** Every block of y in [lo, hi], section by section (acquired). */
	private static void forEach(ChunkAccess chunk, int lo, int hi, Cell cell) {
		for (int si = chunk.getSectionIndex(lo); si <= chunk.getSectionIndex(hi); si++) {
			LevelChunkSection section = chunk.getSection(si);
			int sy = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
			section.acquire();
			try {
				for (int y = Math.max(lo, sy); y <= Math.min(hi, sy + 15); y++) {
					for (int i = 0; i < 256; i++) {
						cell.at(section, y, i);
					}
				}
			} finally {
				section.release();
			}
		}
	}

	/** Air for y in [lo, hi]. */
	private static void clear(ChunkAccess chunk, int lo, int hi) {
		if (hi < lo) {
			return;
		}
		for (int si = chunk.getSectionIndex(lo); si <= chunk.getSectionIndex(hi); si++) {
			LevelChunkSection section = chunk.getSection(si);
			if (section.hasOnlyAir()) {
				continue;
			}
			int sy = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
			section.acquire();
			try {
				for (int y = Math.max(lo, sy); y <= Math.min(hi, sy + 15); y++) {
					for (int i = 0; i < 256; i++) {
						if (!section.getBlockState(i & 15, y & 15, i >> 4).isAir()) {
							section.setBlockState(i & 15, y & 15, i >> 4, AIR, false);
						}
					}
				}
			} finally {
				section.release();
			}
		}
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		// the cap / the grass block at floorY - 1 is solid inside and outside the footprints
		if (this.floorY - 1 < Math.max(level.getMinY(), DeepColumns.BEDROCK_Y) || this.floorY - 1 > level.getMaxY()) {
			return level.getMinY();
		}
		return this.floorY;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		NoiseColumn vanilla = super.getBaseColumn(x, z, level, randomState);
		boolean foot = FlatColumns.isVoid(FlatColumns.current(), x, z);
		BlockState[] states = new BlockState[level.getHeight()];
		for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
			int m = UndergroundColumns.at(this.floorY, foot, y);
			states[y - level.getMinY()] = m == UndergroundColumns.KEEP ? vanilla.getBlock(y) : SlotFlatGenerator.state(m);
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
		info.add("GmodCraft underground: floor y " + this.floorY + (FlatColumns.isVoid(FlatColumns.current(), pos.getX(), pos.getZ()) ? " (under a map)" : ""));
		super.addDebugScreenInfo(info, randomState, pos, context);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}
}
