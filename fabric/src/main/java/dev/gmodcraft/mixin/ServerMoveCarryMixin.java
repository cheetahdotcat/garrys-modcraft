package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P6i (v30): a player carried by a moving GMod entity (an elevator, a train, a pushed prop) is
 * where its client puts it, on the platform as the GMod client sees it. This server replays the
 * move against its own copy of the host's geometry: the server link's, whose entities are placed at
 * most every 250 ms (and fast props not at all), so the platform there lags or leads the client by a
 * fraction of a block. Vanilla then sends the player back: "moved wrongly" (the replay stopped at the
 * stale platform) or a "new" collision (the client's position overlaps the stale platform's voxels;
 * a platform going down does this on every packet).
 * <p>Both checks are relaxed only for a player the GMod server marks carried (kHostPlayerCarried,
 * from its validated McState.carryEnt) and only near a GMod entity's geometry (dynamic host triangles
 * around the player): the replay mismatch counts like vanilla's post-impulse grace (knockback), and a new
 * collision there doesn't send the player back. Minecraft blocks elsewhere still count. The host
 * geometry is the GMod server's to judge (it checks every MC player's position itself).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveCarryMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private static long gmodcraft$relaxLoggedAt;
	@Unique
	private static int gmodcraft$relaxed;

	@WrapOperation(method = "handlePlayerPositionChange",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isInPostImpulseGraceTime()Z"))
	private boolean gmodcraft$hostEntityGrace(ServerPlayer self, Operation<Boolean> original) {
		if (original.call(self)) {
			return true;
		}
		return gmodcraft$nearHostEntity("moved wrongly");
	}

	@WrapOperation(method = "handlePlayerPositionChange",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"))
	private boolean gmodcraft$hostEntityNotNew(ServerGamePacketListenerImpl self, LevelReader level, Entity entity, AABB box, double x, double y, double z,
		Operation<Boolean> original) {
		if (!original.call(self, level, entity, box, x, y, z)) {
			return false;
		}
		return !gmodcraft$nearHostEntity("collides with something new");
	}

	/** Host-entity triangles (the dynamic layer) within 2 blocks of the player? Counts what it relaxed. */
	@Unique
	private boolean gmodcraft$nearHostEntity(String check) {
		// Only for a player GMod has as carried (kHostPlayerCarried): anyone else next to a prop keeps
		// vanilla's checks, Minecraft blocks included.
		if (!ServerHost.linked() || !ServerHost.carriedByHost(this.player)) {
			return false;
		}
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.of(this.player.level()).trianglesNear(this.player.getBoundingBox().inflate(2.0), tris);
		for (SkyTri t : tris) {
			if (t.dynamic) {
				gmodcraft$relaxed++;
				long now = System.currentTimeMillis();
				if (now - gmodcraft$relaxLoggedAt >= 10_000) {
					GmodCraft.LOG.info("GmodCraft: {} {} next to a GMod entity: kept ({} such moves kept since the last line)", this.player.getPlainTextName(),
						check, gmodcraft$relaxed);
					gmodcraft$relaxLoggedAt = now;
					gmodcraft$relaxed = 0;
				}
				return true;
			}
		}
		return false;
	}
}
