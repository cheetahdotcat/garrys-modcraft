package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.combat.HostActorEntity;
import dev.gmodcraft.combat.ProjectileHits;
import dev.gmodcraft.combat.ProjectileTestHook;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyRay;
import org.spongepowered.asm.mixin.Shadow;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.world.SkyClip;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Arrows and tridents hit Skyrim's exact surfaces. They then stick where they hit: the block state
 * there is air, the same as what they recorded on impact, so vanilla never makes them fall out.
 * In a GMod entity (a prop, door, glass) they fall once the entity has moved away.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	/** Ticks between checks that the GMod entity an arrow is stuck in is still there. */
	@Unique
	private static final int GMODCRAFT$STUCK_CHECK_TICKS = 4;
	/** How far (blocks) before and behind the hit point, along the flight, the entity may still be. */
	@Unique
	private static final double GMODCRAFT$STUCK_REACH = 0.3;

	@Shadow
	protected abstract boolean isInGround();

	@Shadow
	private void startFalling() {
	}

	/** Set while stuck in a GMod entity's collision (kTriDynamic): where, and the flight direction. */
	@Unique
	private Vec3 gmodcraft$stuckAt;
	@Unique
	private Vec3 gmodcraft$stuckDir;

	@Inject(method = "onHitBlock", at = @At("HEAD"))
	private void gmodcraft$rememberHostEntityHit(BlockHitResult hitResult, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		this.gmodcraft$stuckAt = null;
		this.gmodcraft$stuckDir = null;
		if (self.level().isClientSide() || !(hitResult instanceof SkyClip.SkyrimHitResult sky) || !ProjectileHits.onHostEntity(sky)) {
			return;
		}
		Vec3 v = self.getDeltaMovement();
		if (v.lengthSqr() < 1e-8) {
			return;
		}
		this.gmodcraft$stuckAt = sky.getLocation();
		this.gmodcraft$stuckDir = v.normalize();
	}

	/**
	 * Vanilla only lets a stuck arrow fall when the block it is in changes, and a GMod entity's
	 * collision is no block: when the entity has moved away (or was taken out of Minecraft's copy
	 * while it moves fast), the arrow falls instead of floating where it was.
	 */
	@Inject(method = "tick", at = @At("HEAD")) // HEAD: tick returns early while the arrow is stuck
	private void gmodcraft$fallOutOfMovedEntity(CallbackInfo ci) {
		if (this.gmodcraft$stuckAt == null) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!this.isInGround() || self.isRemoved()) {
			this.gmodcraft$stuckAt = null;
			this.gmodcraft$stuckDir = null;
			return;
		}
		if (self.tickCount % GMODCRAFT$STUCK_CHECK_TICKS != 0) {
			return;
		}
		Vec3 reach = this.gmodcraft$stuckDir.scale(GMODCRAFT$STUCK_REACH);
		SkyRay.Hit hit = SkyClip.cast(SkyCollision.of(self.level()), this.gmodcraft$stuckAt.subtract(reach), this.gmodcraft$stuckAt.add(reach));
		if (hit != null && hit.tri() != null && hit.tri().dynamic) {
			return;
		}
		if (ProjectileTestHook.ENABLED) {
			dev.gmodcraft.GmodCraft.LOG.info("GmodCraft test: arrow stuck at {} fell: the GMod entity moved away", this.gmodcraft$stuckAt);
		}
		this.gmodcraft$stuckAt = null;
		this.gmodcraft$stuckDir = null;
		this.startFalling();
	}
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;")
	)
	private BlockHitResult gmodcraft$hitSkyrim(Level level, ClipContext context, Operation<BlockHitResult> original) {
		return SkyClip.refine(dev.gmodcraft.world.SkyCollision.of(level), context.getFrom(), context.getTo(), original.call(level, context), SkyClip.Use.PROJECTILE);
	}

	@Unique
	private Vec3 gmodcraft$hitAt;

	@Inject(method = "onHitEntity", at = @At("HEAD"))
	private void gmodcraft$rememberHit(EntityHitResult hitResult, CallbackInfo ci) {
		this.gmodcraft$hitAt = hitResult.getLocation();
	}

	/**
	 * Where Minecraft counts an arrow as stuck in a creature (it hurt it and didn't pierce): if that
	 * creature is a Skyrim NPC's stand-in, Skyrim pins the arrow to the NPC's skeleton.
	 */
	@WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setArrowCount(I)V"))
	private void gmodcraft$stickInSkyrimActor(LivingEntity mob, int count, Operation<Void> original) {
		original.call(mob, count);
		if (!(mob instanceof HostActorEntity actor) || this.gmodcraft$hitAt == null || mob.level().isClientSide() || !ServerHost.linked()) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		Vec3 v = self.getDeltaMovement();
		float yaw = (float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (Mth.atan2(v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG);
		int texture = self instanceof SpectralArrow ? 2 : self instanceof Arrow tippable && tippable.getColor() > 0 ? 1 : 0;
		Vec3 at = this.gmodcraft$hitAt;
		long shooter = self.getOwner() instanceof net.minecraft.server.level.ServerPlayer player ? ServerHost.steamIdOf(player) : 0L;
		ServerLink.INSTANCE.pushEvent(Proto.EV_ARROW_STUCK, actor.entId(), shooter, (float) at.x, (float) at.y, (float) at.z, yaw, Float.floatToRawIntBits(pitch), texture);
	}
}
