package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SurfaceBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** M1: animals spawn on the map's grass (ANIMALS_SPAWNABLE_ON reads the ground's material). */
@Mixin(Animal.class)
public abstract class AnimalSpawnRulesMixin {
	@WrapOperation(
		method = "checkAnimalSpawnRules",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private static BlockState gmodcraft$mapGroundForAnimals(LevelAccessor level, BlockPos pos, Operation<BlockState> original) {
		return SurfaceBlocks.groundState(level, pos, original.call(level, pos));
	}
}
