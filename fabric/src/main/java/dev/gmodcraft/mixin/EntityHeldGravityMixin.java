package dev.gmodcraft.mixin;

import dev.gmodcraft.combat.HeldMcEntities;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** T2 (v29): no gravity while GMod holds the entity (carts, boats, items too); transient, never NoGravity. */
@Mixin(Entity.class)
public abstract class EntityHeldGravityMixin {
	@Inject(method = "applyGravity", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$heldNoGravity(CallbackInfo ci) {
		if (HeldMcEntities.pinned((Entity) (Object) this)) {
			ci.cancel();
		}
	}
}
