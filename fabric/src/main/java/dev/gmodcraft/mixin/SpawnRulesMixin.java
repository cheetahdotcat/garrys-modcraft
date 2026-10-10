package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SurfaceBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** M1: mob spawn rules read the map ground's material under the spawn spot (isValidSpawn). */
@Mixin(Mob.class)
public abstract class SpawnRulesMixin {
	@WrapOperation(
		method = "checkMobSpawnRules",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private static BlockState gmodcraft$mapGroundForMobs(LevelAccessor level, BlockPos pos, Operation<BlockState> original) {
		return SurfaceBlocks.groundState(level, pos, original.call(level, pos));
	}
}
