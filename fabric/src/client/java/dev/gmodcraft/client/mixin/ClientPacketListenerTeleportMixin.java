package dev.gmodcraft.client.mixin;

import dev.gmodcraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The MC server moved the local player (join, respawn, or a teleport the GMod server asked for on
 * the server link). TAIL is only reached on the render thread: the first, network-thread call
 * re-schedules itself and bails out before it.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTeleportMixin {
	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void gmodcraft$serverMovedPlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		SkyClient.onServerTeleport(Minecraft.getInstance());
	}
}
