package dev.gmodcraft.mixin;

import dev.gmodcraft.combat.HeldMcEntities;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** T2 (v29): no AI (goals, navigation, look, jump) while GMod holds the mob (transient, never NoAI). */
@Mixin(Mob.class)
public abstract class MobHeldAiMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$heldNoAi(CallbackInfo ci) {
		if (HeldMcEntities.pinned((Mob) (Object) this)) {
			ci.cancel();
		}
	}
}
