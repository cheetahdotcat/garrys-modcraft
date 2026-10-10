package dev.gmodcraft.micro;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * {@code gmodcraft:microblock}: one cell holding sub-block parts (covers, panels, slabs, posts,
 * corners) of any full-block material, kept in {@link MicroblockEntity}. Shapes come from the parts
 * (dynamic shape, no occlusion). Mining removes the part under the crosshair (see
 * {@link Microblocks#beforeBreak}); the last part takes the block with it.
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
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != Microblocks.BLOCK_ENTITY) {
			return null;
		}
		return (BlockEntityTicker<T>) (BlockEntityTicker<MicroblockEntity>) (l, p, s, be) -> be.serverTick();
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

	/** The last part is being broken: drop what's left (the block has no loot table). */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (level instanceof ServerLevel && !player.isCreative() && level.getBlockEntity(pos) instanceof MicroblockEntity be) {
			for (MicroPart part : be.parts()) {
				MicroSpec spec = MicroSpec.of(part);
				if (spec != null) {
					popResource(level, pos, Microblocks.stack(spec, 1));
				}
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
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
