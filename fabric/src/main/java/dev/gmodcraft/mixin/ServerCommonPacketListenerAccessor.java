package dev.gmodcraft.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A player's network connection (protected in Minecraft). ServerHost's owner check needs
 * {@link Connection#isMemoryConnection()}: only the integrated server's own client is connected in
 * memory; everyone else comes in over the network.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {
	@Accessor("connection")
	Connection gmodcraft$connection();
}
