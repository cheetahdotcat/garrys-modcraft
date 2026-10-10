package dev.gmodcraft.combat;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.GLink;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Test only (tools/test_mc.sh --hazards sets GMODCRAFT_TEST_HAZARDS=1; off otherwise): builds the
 * block an actor named {@code hz:<kind>} stands in or on, where the host first lists it, so the
 * harness can check that proxies take Minecraft's block effects. Kinds: plate, lava, fire, cactus,
 * magma. The blocks are kept in place (rain puts fire out) and a plate's power changes are logged
 * ("GmodCraft test: plate ... powered=...") for the harness to read.
 */
final class HazardTestHook {
	static final boolean ENABLED = "1".equals(System.getenv("GMODCRAFT_TEST_HAZARDS"));
	private static final String PREFIX = "hz:";

	private record Site(String kind, BlockPos feet) {
	}

	private static final Map<Integer, Site> SITES = new HashMap<>();
	private static final Map<Integer, Boolean> PLATE_POWERED = new HashMap<>();

	private HazardTestHook() {
	}

	static void tick(ServerLevel level, Collection<GLink.Actor> actors) {
		if (level.isRaining()) {
			level.getServer().setWeatherParameters(6000, 0, false, false); // rain puts a burning actor out
		}
		for (GLink.Actor a : actors) {
			if (!a.name().startsWith(PREFIX) || SITES.containsKey(a.entId())) {
				continue;
			}
			Site site = new Site(a.name().substring(PREFIX.length()), BlockPos.containing(a.x(), a.y() + 0.01, a.z()));
			SITES.put(a.entId(), site);
			GmodCraft.LOG.info("GmodCraft test: hazard {} for ent {} at {}", site.kind(), a.entId(), site.feet().toShortString());
		}
		// Sites stay after their actor leaves (the plate must be seen to release).
		for (Map.Entry<Integer, Site> e : SITES.entrySet()) {
			Site site = e.getValue();
			build(level, site);
			if ("plate".equals(site.kind())) {
				BlockState plate = level.getBlockState(site.feet());
				if (plate.hasProperty(BlockStateProperties.POWERED)) {
					boolean powered = plate.getValue(BlockStateProperties.POWERED);
					if (!Boolean.valueOf(powered).equals(PLATE_POWERED.put(e.getKey(), powered))) {
						GmodCraft.LOG.info("GmodCraft test: plate of ent {} at {} powered={}", e.getKey(), site.feet().toShortString(), powered);
					}
				}
			}
		}
	}

	private static void build(ServerLevel level, Site site) {
		BlockPos feet = site.feet();
		BlockPos below = feet.below();
		ensure(level, feet.above(), Blocks.AIR);
		ensure(level, feet.above(2), Blocks.AIR);
		switch (site.kind()) {
			case "plate" -> {
				ensure(level, below, Blocks.STONE);
				ensure(level, feet, Blocks.STONE_PRESSURE_PLATE);
			}
			case "lava" -> {
				ensure(level, below, Blocks.STONE);
				for (Direction d : Direction.Plane.HORIZONTAL) {
					ensure(level, feet.relative(d), Blocks.STONE); // a basin: the lava stays put
				}
				ensure(level, feet, Blocks.LAVA);
			}
			case "fire" -> {
				ensure(level, below, Blocks.NETHERRACK); // burns forever
				ensure(level, feet, Blocks.FIRE);
			}
			case "cactus" -> {
				ensure(level, below.below(), Blocks.STONE);
				ensure(level, below, Blocks.SAND);
				ensure(level, feet, Blocks.CACTUS);
			}
			case "magma" -> {
				ensure(level, below, Blocks.MAGMA_BLOCK);
				ensure(level, feet, Blocks.AIR);
			}
			default -> {
			}
		}
	}

	private static void ensure(ServerLevel level, BlockPos pos, Block block) {
		if (!level.getBlockState(pos).is(block)) {
			level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
		}
	}
}
