package dev.gmodcraft.mixin;

import dev.gmodcraft.ServerHost;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Players stand on the host's ground (its collision, streamed per player area), which this server
 * may not know everywhere a client does; to it they'd seem to hover and be kicked for flying. Their
 * own clients keep them on the ground.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerFlightMixin {
	@Inject(method = "allowFlight", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$playersStandOnHostGround(CallbackInfoReturnable<Boolean> cir) {
		if (ServerHost.linked()) {
			cir.setReturnValue(true);
		}
	}
}
