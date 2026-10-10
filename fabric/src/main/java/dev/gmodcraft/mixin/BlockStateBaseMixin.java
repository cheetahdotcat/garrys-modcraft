package dev.gmodcraft.mixin;

import dev.gmodcraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skyrim ground and walls hold things up: torches, lanterns, rails, carpets and the like can be
 * placed on terrain and against walls, and stay there. A hull world's mirror blocks have no collision,
 * don't suffocate and don't block the view (see below).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	// A hull world's mirror block has no Minecraft collision (the host's geometry collides there), doesn't
	// suffocate and doesn't blot the view: an eye is often inside one at a wall (cells are mirror blocks
	// when half full). Its outline (picking, mining) and its sturdy faces (torches) stay.
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void gmodcraft$mirrorNoCollision(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		if (dev.gmodcraft.world.MirrorColumn.seen && dev.gmodcraft.world.HullWorld.isMirror(level, pos)) {
			cir.setReturnValue(Shapes.empty());
		}
	}

	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void gmodcraft$mirrorNoCollisionCtx(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (dev.gmodcraft.world.MirrorColumn.seen && dev.gmodcraft.world.HullWorld.isMirror(level, pos)) {
			cir.setReturnValue(Shapes.empty());
		}
	}

	@Inject(method = "isSuffocating(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$mirrorNoSuffocation(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (dev.gmodcraft.world.MirrorColumn.seen && dev.gmodcraft.world.HullWorld.isMirror(level, pos)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isViewBlocking", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$mirrorNoViewBlock(BlockGetter level, BlockPos pos, AABB box, CallbackInfoReturnable<Boolean> cir) {
		if (dev.gmodcraft.world.MirrorColumn.seen && dev.gmodcraft.world.HullWorld.isMirror(level, pos)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(
		method = "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void gmodcraft$skyrimIsSturdy(BlockGetter level, BlockPos pos, Direction direction, SupportType type, CallbackInfoReturnable<Boolean> cir) {
		if (!((BlockBehaviour.BlockStateBase) (Object) this).isAir()) {
			return;
		}
		boolean sturdy = direction == Direction.UP ? SkyCollision.of(level).supportsFromBelow(pos.above()) : SkyCollision.of(level).solidFraction(pos) >= 0.4F;
		if (sturdy) {
			cir.setReturnValue(true);
		}
	}
}
