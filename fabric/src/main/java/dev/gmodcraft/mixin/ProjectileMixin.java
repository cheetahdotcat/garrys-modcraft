package dev.gmodcraft.mixin;

import dev.gmodcraft.world.SkyClip;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft ignores projectile hits on air, and to Minecraft a Skyrim wall is air. Treat a hit on
 * Skyrim geometry as a real hit: arrows stick in it, snowballs and eggs break on it. A hit on a
 * GMod entity's collision is also reported to GMod (ProjectileHits).
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	@Shadow
	protected abstract void onHit(HitResult hitResult);

	@Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$hitSkyrim(HitResult hitResult, CallbackInfoReturnable<ProjectileDeflection> cir) {
		if (hitResult instanceof SkyClip.SkyrimHitResult sky) {
			// v15: on a GMod entity's collision, GMod hears about it (kEvProjectileHit) first, while
			// the projectile still has its flight velocity.
			dev.gmodcraft.combat.ProjectileHits.report((net.minecraft.world.entity.projectile.Projectile) (Object) this, sky);
			this.onHit(hitResult);
			cir.setReturnValue(ProjectileDeflection.NONE);
		}
	}
}
