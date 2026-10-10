package dev.gmodcraft.mixin;

import dev.gmodcraft.combat.HeldMcEntities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** T2 (v29): no movement of its own (walking, swimming, gravity, friction) while GMod holds it: GMod places it. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHeldTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$heldNoTravel(Vec3 input, CallbackInfo ci) {
		if (HeldMcEntities.pinned((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
