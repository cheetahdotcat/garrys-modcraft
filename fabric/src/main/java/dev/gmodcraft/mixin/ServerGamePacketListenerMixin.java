package dev.gmodcraft.mixin;

import dev.gmodcraft.ServerHost;
import dev.gmodcraft.world.SkyCollision;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft resends the blocks under a player whose client says "on ground" while the server's
 * last move of them hit no floor ("Player X standing on air - force-sending blocks below", every
 * 10 s). With a GMod host the floor is usually the host's geometry: the client stands on its exact
 * triangles, the server only replays the client's moves against 1/8-block voxels and (moving by
 * dy = 0 doesn't update the flag) seldom records a floor hit. Resending air fixes nothing there,
 * so it's skipped while the host's ground may be what the player stands on.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$hostGroundIsNotAir(CallbackInfo ci) {
		if (ServerHost.linked() && SkyCollision.of(this.player.level()).mayStandOnHost(this.player.getX(), this.player.getY(), this.player.getZ())) {
			ci.cancel();
		}
	}
}
