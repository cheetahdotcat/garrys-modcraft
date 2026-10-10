package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.PistonPush;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * v42: a piston about to move blocks tells GMod which cells move where (kEvPistonMove, see
 * {@link PistonPush}). Hooked at moveBlocks' own structure resolve, so the cells are exactly the
 * ones Minecraft moves, before any of them changed.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseMixin {
	@WrapOperation(
		method = "moveBlocks",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z")
	)
	private boolean gmodcraft$pistonMove(PistonStructureResolver resolver, Operation<Boolean> original, Level level, BlockPos pos, Direction facing,
		boolean extending) {
		boolean ok = original.call(resolver);
		if (ok && !level.isClientSide()) {
			PistonPush.moved(level, pos, facing, extending, resolver.getPushDirection(), resolver.getToPush());
		}
		return ok;
	}
}
