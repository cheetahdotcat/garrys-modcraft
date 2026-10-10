package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.combat.HeldMcEntities;
import dev.gmodcraft.world.PhysicsBlocks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v44 physics blocks: a falling block GMod simulates (HeldMcEntities) doesn't tick in Minecraft at all
 * (no fall, no landing, no despawn clock) until GMod gives it back; and one landing on the host's
 * ground or a GMod prop becomes a block there instead of an item (vanilla sees only air below).
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$heldByGmod(CallbackInfo ci) {
		FallingBlockEntity self = (FallingBlockEntity) (Object) this;
		// waitForGmod first: it also ends (and logs) a pulled block's wait once GMod has pinned it
		if (!self.level().isClientSide() && (PhysicsBlocks.waitForGmod(self) || HeldMcEntities.pinned(self))) {
			ci.cancel();
		}
	}

	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z")
	)
	private boolean gmodcraft$hostHoldsIt(BlockState below, Operation<Boolean> original) {
		boolean free = original.call(below);
		FallingBlockEntity self = (FallingBlockEntity) (Object) this;
		if (free && !self.level().isClientSide() && PhysicsBlocks.hostHolds(self.level(), self.blockPosition(), self.position())) {
			PhysicsBlocks.landedOnHost(self);
			return false; // it rests on GMod's map or a prop: it lands here
		}
		return free;
	}

	/** v44: a falling block GMod once simulated never breaks into an item: it goes to the nearest free cell. */
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/entity/item/ItemEntity;")
	)
	private net.minecraft.world.entity.item.ItemEntity gmodcraft$noItemForGmodBlocks(FallingBlockEntity self, net.minecraft.server.level.ServerLevel level,
		net.minecraft.world.level.ItemLike item, Operation<net.minecraft.world.entity.item.ItemEntity> original) {
		if (HeldMcEntities.oncePinned(self) && PhysicsBlocks.rescueItem(self)) {
			return null;
		}
		return original.call(self, level, item);
	}
}
