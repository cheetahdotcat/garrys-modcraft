package dev.gmodcraft.wire;

import dev.gmodcraft.ServerHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * {@code gmodcraft:redstone_bridge} (P7, D-013): one block, six faces, paired with a Wiremod entity
 * on the GMod server. A face whose wire input is linked is driven: it gives weak redstone power of
 * the wire's level (0-15). Every other face is an input: the redstone level next to it goes to the
 * wire output of the same name. Weak power only, and not a conductor, so a driven face never reads
 * itself back. Without Wiremod on the GMod server the block is inert.
 */
public final class RedstoneBridgeBlock extends Block implements EntityBlock {
	public RedstoneBridgeBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new RedstoneBridgeBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		// Server side only: the first tick registers the bridge (Bridges), on the server thread.
		if (level.isClientSide() || type != Bridges.BLOCK_ENTITY) {
			return null;
		}
		return (BlockEntityTicker<T>) (BlockEntityTicker<RedstoneBridgeBlockEntity>) (l, p, s, be) -> be.serverTick((ServerLevel) l);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	/** {@code dir} points from the block reading the signal toward us: our face is its opposite. */
	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
		return level.getBlockEntity(pos) instanceof RedstoneBridgeBlockEntity be ? be.output(dir.getOpposite()) : 0;
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean moved) {
		if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof RedstoneBridgeBlockEntity be) {
			be.readInputs();
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
		if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof RedstoneBridgeBlockEntity be) {
			be.readInputs();
		}
	}

	/** Our power goes away with the block: tell the neighbours (as a lever does), and GMod. Not called on a chunk unload. */
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
		level.updateNeighborsAt(pos, this, null);
		Bridges.broken(level, pos);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide() || !(placer instanceof Player player)) {
			return;
		}
		if (!ServerHost.wiremod() && !ServerHost.mapIo()) {
			player.sendSystemMessage(Component.translatable(ServerHost.linked() ? "block.gmodcraft.redstone_bridge.no_wiremod"
				: "block.gmodcraft.redstone_bridge.no_link"));
		} else if (!Bridges.inSlot(level, pos)) {
			player.sendSystemMessage(Component.translatable("block.gmodcraft.redstone_bridge.outside_map"));
		}
	}
}
