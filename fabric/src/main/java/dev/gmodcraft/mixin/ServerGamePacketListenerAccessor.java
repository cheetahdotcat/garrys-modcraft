package dev.gmodcraft.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft's teleport handshake: non-null while the server waits for the client to accept a
 * teleport. kEvTeleportAck goes out once it is null again (the client is at the new place).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
	@Accessor("awaitingPositionFromClient")
	Vec3 gmodcraft$awaitingPositionFromClient();
}
