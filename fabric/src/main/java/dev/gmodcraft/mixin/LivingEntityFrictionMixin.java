package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SurfaceBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * M1: walking on the map's ground has its material's friction (ice slides). The one getBlockState in
 * travelInAir is the block under the feet, read only for Block.getFriction. Both sides: the local
 * player moves on the client.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFrictionMixin {
	@WrapOperation(
		method = "travelInAir",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private BlockState gmodcraft$mapGroundFriction(Level level, BlockPos pos, Operation<BlockState> original) {
		return SurfaceBlocks.groundState(level, pos, original.call(level, pos));
	}
}
