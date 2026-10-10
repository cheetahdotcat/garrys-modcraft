package dev.gmodcraft.mixin;

import dev.gmodcraft.Noclip;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GMod's noclip (Noclip, v22): Player.tick sets noPhysics = isSpectator() first thing; a player in
 * noclip gets noPhysics (and flying) right after, on both sides, so it moves through blocks and the
 * host's geometry like a spectator while staying in survival.
 */
@Mixin(Player.class)
public abstract class PlayerNoclipMixin {
	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
		opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 0))
	private void gmodcraft$gmodNoclip(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Noclip.active(self)) {
			self.noPhysics = true;
			self.getAbilities().mayfly = true;
			self.getAbilities().flying = true;
			self.setOnGround(false);
		}
	}
}
