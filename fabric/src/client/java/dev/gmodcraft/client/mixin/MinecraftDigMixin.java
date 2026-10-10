package dev.gmodcraft.client.mixin;

import dev.gmodcraft.client.SkyDigClient;
import dev.gmodcraft.weapon.GmodWeapons;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attacking Skyrim's geometry digs into it (SkyDigClient). H2: nothing at all while a gmod_weapon is held. */
@Mixin(Minecraft.class)
public abstract class MinecraftDigMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$digStart(CallbackInfoReturnable<Boolean> cir) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (minecraft.player != null && GmodWeapons.holdsWeapon(minecraft.player)) {
			// H2 hybrid mode: a held GMod weapon fires in GMod; Minecraft neither digs nor swings.
			cir.setReturnValue(false);
			return;
		}
		if (SkyDigClient.attack(minecraft)) {
			// A swing, not a miss: no miss cooldown before mining the block that appears.
			var held = minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
			minecraft.player.swing(InteractionHand.MAIN_HAND, held.getAttackAnimation(), false);
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$digHold(boolean down, CallbackInfo ci) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (minecraft.player != null && GmodWeapons.holdsWeapon(minecraft.player)) {
			if (minecraft.gameMode != null) {
				minecraft.gameMode.stopDestroyBlock(); // a dig started with another item ends here
			}
			ci.cancel();
			return;
		}
		if (down) {
			SkyDigClient.attack((Minecraft) (Object) this);
		}
	}
}
