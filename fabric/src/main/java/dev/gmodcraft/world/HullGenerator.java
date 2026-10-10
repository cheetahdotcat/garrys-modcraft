package dev.gmodcraft.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * gmodcraft:hull: void like the mirror world type, except where a GMod map is: there every solid
 * block of the map's hull file ({@link HullWorld}) is a Minecraft block of its material, a mirror
 * block (marked in {@link HullWorld#MIRROR}). A chunk whose map has no file yet stays void and is
 * marked pending ({@link HullWorld#PENDING}); it is filled in when the file arrives. No features,
 * structures or mobs.
 */
public final class HullGenerator extends ChunkGenerator {
	public static final MapCodec<HullGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
	).apply(i, HullGenerator::new));

	public HullGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structures,
		BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> biomeSet) {
		fill(chunk, region.getSeed());
		return CompletableFuture.completedFuture(chunk);
	}

	private static void fill(ChunkAccess chunk, long seed) {
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		HullWorld.Entry e = HullWorld.fileAt(bx, bz);
		if (e == null) {
			chunk.setAttached(HullWorld.PENDING, Boolean.TRUE);
			return;
		}
		HullFile file = e.file();
		Map<Integer, long[]> bits = new HashMap<>();
		Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		int minY = chunk.getMinY(), maxY = chunk.getMaxY();
		for (int si = 0; si < chunk.getSectionsCount(); si++) {
			int sy = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
			LevelChunkSection section = null;
			for (int ry = sy; ry < sy + 16; ry += 8) {
				for (int rz = bz; rz < bz + 16; rz += 8) {
					for (int rx = bx; rx < bx + 16; rx += 8) {
						byte[] codes = file.regions.get(HullFile.regionKey(rx, ry, rz));
						if (codes == null) {
							continue;
						}
						if (section == null) {
							section = chunk.getSection(si);
							section.acquire();
						}
						for (int i = 0; i < HullFile.REGION_CELLS; i++) {
							if (codes[i] == 0) {
								continue;
							}
							int x = rx + (i & 7), z = rz + ((i >> 3) & 7), y = ry + (i >> 6);
							if (y < minY || y > maxY) {
								continue;
							}
							BlockState state = HullWorld.stateFor(file.material(x, y, z, HullWorld.UNDERGROUND), seed, x, y, z);
							section.setBlockState(x & 15, y - sy, z & 15, state, false);
							ocean.update(x & 15, y, z & 15, state);
							surface.update(x & 15, y, z & 15, state);
							MirrorColumn.set(bits, x, y, z);
						}
					}
				}
			}
			if (section != null) {
				section.release();
			}
		}
		if (!bits.isEmpty()) {
			chunk.setAttached(HullWorld.MIRROR, MirrorColumn.EMPTY.with(bits));
		}
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		return level.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		BlockState[] states = new BlockState[level.getHeight()];
		Arrays.fill(states, Blocks.AIR.defaultBlockState());
		return new NoiseColumn(level.getMinY(), states);
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor level) {
		return dev.gmodcraft.GmodCraftConfig.floorY();
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
		HullWorld.Entry e = HullWorld.fileAt(pos.getX(), pos.getZ());
		info.add("GmodCraft hull: " + (e != null ? "map " + e.map() + " (" + e.file().solidCount + " blocks)" : "no map file here yet"));
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
