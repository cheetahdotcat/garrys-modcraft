package dev.gmodcraft.mixin;

import dev.gmodcraft.ServerHost;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The dedicated server's half of {@link MinecraftServerFlightMixin}: DedicatedServer overrides
 * allowFlight() (server.properties allow-flight), so the MinecraftServer injection never runs there.
 * In the void mirror world a player held in place until GMod's collision arrives, or standing on the
 * host's ground (no Minecraft blocks under them), would otherwise be kicked for "floating too long"
 * after 4 s. Only while a GMod server is linked, like single-player.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerFlightMixin {
	@Inject(method = "allowFlight", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$playersStandOnHostGround(CallbackInfoReturnable<Boolean> cir) {
		if (ServerHost.linked()) {
			cir.setReturnValue(true);
		}
	}
}
