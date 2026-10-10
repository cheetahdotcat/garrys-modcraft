package dev.gmodcraft.client.micro;

import dev.gmodcraft.micro.MicroPart;
import dev.gmodcraft.micro.Microblocks;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The microblock's Minecraft model: no static quads; {@link #emitQuads} reads the block entity's parts
 * (render data) and emits {@link MicroQuads}, tinted per material (the host block has no tint of its own).
 */
public final class MicroModel implements BlockStateModel {
	private static final Direction[] DIRS = Direction.values();

	public static void register() {
		ModelLoadingPlugin.register(ctx -> ctx.registerBlockStateResolver(Microblocks.BLOCK,
			resolver -> resolver.setModel(Microblocks.BLOCK.defaultBlockState(), new Unbaked())));
	}

	private static final class Unbaked implements BlockStateModel.UnbakedRoot {
		@Override
		public void resolveDependencies(ResolvableModel.Resolver resolver) {
		}

		@Override
		public BlockStateModel bake(BlockState state, ModelBaker baker) {
			return new MicroModel();
		}

		@Override
		public Object visualEqualityGroup(BlockState state) {
			return this;
		}
	}

	@SuppressWarnings("unchecked")
	static List<MicroPart> parts(BlockAndTintGetter level, BlockPos pos) {
		Object data = level.getBlockEntityRenderData(pos);
		return data instanceof List<?> list ? (List<MicroPart>) list : List.of();
	}

	@Override
	public void collectParts(RandomSource random, List<BlockStateModelPart> out) {
		// nothing without a position: the parts live in the block entity
	}

	@Override
	public Material.Baked particleMaterial() {
		return MicroQuads.modelOf(Blocks.STONE.defaultBlockState()).particleMaterial();
	}

	@Override
	public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		List<MicroPart> parts = parts(level, pos);
		return parts.isEmpty() ? particleMaterial() : MicroQuads.modelOf(parts.getFirst().material()).particleMaterial();
	}

	@Override
	public int materialFlags() {
		return 0;
	}

	@Override
	public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		int flags = 0;
		for (MicroQuads.Group g : MicroQuads.groups(parts(level, pos))) {
			flags |= g.part().materialFlags();
		}
		return flags;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
		Predicate<Direction> cullTest) {
		List<MicroPart> parts = parts(level, pos);
		if (parts.isEmpty()) {
			return;
		}
		var colors = Minecraft.getInstance().getBlockColors();
		for (MicroQuads.Group g : MicroQuads.groups(parts)) {
			for (int i = 0; i < 7; i++) {
				Direction cull = i < 6 ? DIRS[i] : null;
				if (cull != null && cullTest.test(cull)) {
					continue;
				}
				for (BakedQuad q : g.part().getQuads(cull)) {
					emitter.fromBakedQuad(q);
					emitter.cullFace(cull);
					int ti = q.materialInfo().tintIndex();
					if (ti >= 0) {
						BlockTintSource src = colors.getTintSource(g.material(), ti);
						int rgb = src == null ? -1 : src.colorInWorld(g.material(), level, pos);
						for (int v = 0; v < 4; v++) {
							emitter.color(v, 0xFF000000 | rgb);
						}
						emitter.tintIndex(-1);
					}
					emitter.emit();
				}
			}
		}
	}
}
