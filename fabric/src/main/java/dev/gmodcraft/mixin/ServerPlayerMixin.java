package dev.gmodcraft.mixin;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.combat.SkyCombat;
import dev.gmodcraft.combat.HostActorEntity;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.ServerLink;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	/** Critical hits on a GMod NPC are flagged so the host can play them up. */
	@Inject(method = "crit", at = @At("HEAD"))
	private void gmodcraft$critHostActor(Entity entity, CallbackInfo ci) {
		if (entity instanceof HostActorEntity proxy) {
			proxy.markCritical();
		}
	}

	/**
	 * Carried in a GMod seat (ServerHost.seatedByHost): GMod drives and moves this player along by
	 * teleport, falling in between. Those falls never hurt. Nor do falls in GMod's noclip, or out of
	 * it until the player first lands (Noclip, v22).
	 */
	@Inject(method = "checkFallDamage", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$noFallWhileSeated(double dy, boolean onGround, net.minecraft.world.level.block.state.BlockState state,
		net.minecraft.core.BlockPos pos, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (ServerHost.seatedByHost(self) || dev.gmodcraft.Noclip.noFall(self)) {
			self.resetFallDistance();
			ci.cancel();
		}
	}

	/** Dying in Minecraft is dying in GMod: the GMod server kills the GMod player behind this one. */
	@Inject(method = "die", at = @At("HEAD"))
	private void gmodcraft$diesInGmod(DamageSource source, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (!ServerHost.linked()) {
			return;
		}
		long steamId = ServerHost.steamIdOf(self);
		ServerLink.INSTANCE.pushEvent(Proto.EV_PLAYER_DIED, SkyCombat.attackerEntId(source), steamId, 0, 0, 0, 0, 0);
		GmodCraft.LOG.info("GmodCraft: {} died ({}); telling GMod (SteamID {})", self.getPlainTextName(), source.getMsgId(), Long.toUnsignedString(steamId));
	}
}
