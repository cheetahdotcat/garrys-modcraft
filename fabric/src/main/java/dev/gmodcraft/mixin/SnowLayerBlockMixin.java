package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SurfaceBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** M1: snow layers rest on the map's ground (its material answers for the AIR below). No block writes. */
@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerBlockMixin {
	@WrapOperation(
		method = "canSurvive",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private BlockState gmodcraft$mapGroundBelow(LevelReader level, BlockPos pos, Operation<BlockState> original) {
		return SurfaceBlocks.groundState(level, pos, original.call(level, pos));
	}
}
