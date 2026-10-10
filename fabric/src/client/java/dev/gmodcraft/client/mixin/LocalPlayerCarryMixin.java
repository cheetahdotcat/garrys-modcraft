package dev.gmodcraft.client.mixin;

import dev.gmodcraft.client.CarryClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P6i: the moving GMod platform carries the player after its own movement and before the move is
 * sent, so the server gets the carried position (and "on ground" while it stands on the platform).
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerCarryMixin {
	// 26.3: Minecraft.tick calls sendChanges() after ClientLevel.tickEntities(); it sends the move.
	@Inject(method = "sendChanges", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;sendPosition()V"))
	private void gmodcraft$carry(CallbackInfo ci) {
		CarryClient.beforeSend((LocalPlayer) (Object) this);
	}
}
