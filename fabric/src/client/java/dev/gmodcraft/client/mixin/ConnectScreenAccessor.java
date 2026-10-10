package dev.gmodcraft.client.mixin;

import io.netty.channel.ChannelFuture;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Aborting a connect that's still in flight (ConnectScreen has no public cancel; its Cancel button,
 * under the screen's lock, sets aborted, cancels the channel future and disconnects). Used when the
 * host redirects or cancels a join mid-connect.
 */
@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
	@Accessor("aborted")
	void gmodcraft$setAborted(boolean aborted);

	@Accessor("connection")
	Connection gmodcraft$connection();

	@Accessor("channelFuture")
	ChannelFuture gmodcraft$channelFuture();

	@Accessor("channelFuture")
	void gmodcraft$setChannelFuture(ChannelFuture future);
}
