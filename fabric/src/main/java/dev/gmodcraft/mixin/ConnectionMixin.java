package dev.gmodcraft.mixin;

import dev.gmodcraft.GmodCraft;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server-list ping (status) connection that fails on read (the peer resets it, garbage bytes)
 * makes vanilla's exceptionCaught send minecraft:disconnect, which the status protocol hasn't got:
 * "Error sending packet clientbound/minecraft:disconnect ... EncoderException: Sending unknown
 * packet" at ERROR with a stack trace. Handshake connections have no clientbound disconnect either.
 * Those are just closed. Logged at most every 10 minutes (with the peer, to find who pings).
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
	private static final long LOG_EVERY_MS = 10 * 60 * 1000;
	private static volatile long gmodcraft$lastLog;

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$noDisconnectPacketBeforeLogin(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		Connection self = (Connection) (Object) this;
		if (cause instanceof SkipPacketException || self.getSending() != PacketFlow.CLIENTBOUND) {
			return;
		}
		PacketListener listener = self.getPacketListener();
		if (listener == null) {
			return;
		}
		ConnectionProtocol protocol = listener.protocol();
		if (protocol != ConnectionProtocol.STATUS && protocol != ConnectionProtocol.HANDSHAKING) {
			return;
		}
		ci.cancel();
		long now = System.currentTimeMillis();
		if (now - gmodcraft$lastLog >= LOG_EVERY_MS) {
			gmodcraft$lastLog = now;
			GmodCraft.LOG.info("GmodCraft: a {} connection from {} failed ({}); closed without a disconnect packet (it has none)", protocol.id(),
				self.getLoggableAddress(true), cause.toString());
		}
		self.disconnect(Component.translatable("disconnect.genericReason", "Internal Exception: " + cause));
	}
}
