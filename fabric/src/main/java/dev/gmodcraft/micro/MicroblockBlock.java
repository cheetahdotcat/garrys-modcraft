package dev.gmodcraft.micro;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code gmodcraft:microblock}: one cell holding sub-block parts (covers, panels, slabs, posts,
 * corners) of any full-block material, kept in {@link MicroblockEntity}. Shapes come from the parts
 * (dynamic shape, no occlusion). Mining removes the part under the crosshair (see
 * {@link Microblocks#beforeBreak}); the last part takes the block with it. Drops: {@link #getDrops}.
 * No ticker: an empty cell turns into air when its block entity is loaded or its last part goes
 * ({@link MicroblockEntity#setLevel}, {@link MicroblockEntity#remove}).
 */
public final class MicroblockBlock extends Block implements EntityBlock {
	public MicroblockBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MicroblockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	private static VoxelShape shapeAt(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof MicroblockEntity be ? be.shape() : net.minecraft.world.phys.shapes.Shapes.block();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeAt(level, pos);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeAt(level, pos);
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		if (level.getBlockEntity(pos) instanceof MicroblockEntity be && !be.parts().isEmpty()) {
			MicroSpec spec = MicroSpec.of(be.parts().getFirst());
			if (spec != null) {
				return Microblocks.stack(spec, 1);
			}
		}
		return ItemStack.EMPTY;
	}

	/** Breaks as fast as the material of the part under the crosshair (stone parts like stone, wool like wool). */
	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof MicroblockEntity be && !be.parts().isEmpty()) {
			int i = pickPart(be, pos, player);
			return be.parts().get(Math.max(i, 0)).material().getDestroyProgress(player, level, pos);
		}
		return super.getDestroyProgress(state, player, level, pos);
	}

	/**
	 * Every part as its item: player breaking (the last part), explosions and pistons (push reaction
	 * POPPED) all drop through here with the block entity in the loot params. An explosion that decays
	 * drops (creepers, not TNT) keeps each part with chance 1 / radius, like a loot table's
	 * explosion_decay. The block has no loot table, so nothing else drops.
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof MicroblockEntity be)) {
			return List.of();
		}
		Float radius = params.getOptionalParameter(LootContextParams.EXPLOSION_RADIUS);
		RandomSource random = params.getLevel().getRandom();
		List<ItemStack> out = new ArrayList<>(be.parts().size());
		for (MicroPart part : be.parts()) {
			MicroSpec spec = MicroSpec.of(part);
			if (spec != null && (radius == null || random.nextFloat() <= 1.0F / radius)) {
				out.add(Microblocks.stack(spec, 1));
			}
		}
		return out;
	}

	/** The part the player looks at (nearest hit along their view, within reach), or -1. */
	static int pickPart(MicroblockEntity be, BlockPos pos, Player player) {
		Vec3 from = player.getEyePosition();
		Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1.0));
		List<MicroPart> parts = be.parts();
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < parts.size(); i++) {
			AABB box = parts.get(i).aabb().move(pos);
			Optional<Vec3> hit = box.clip(from, to);
			if (box.contains(from)) {
				return i;
			}
			if (hit.isPresent()) {
				double d = hit.get().distanceToSqr(from);
				if (d < bestDist) {
					bestDist = d;
					best = i;
				}
			}
		}
		return best;
	}
}
