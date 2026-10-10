package dev.gmodcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** M1: would a plant take root on this ground (checked before a map surface cell is converted)? */
@Mixin(VegetationBlock.class)
public interface VegetationBlockInvoker {
	@Invoker("mayPlaceOn")
	boolean gmodcraft$mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos);
}
