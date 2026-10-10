package dev.gmodcraft.combat;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.mixin.AbstractArrowAccessor;
import dev.gmodcraft.world.SkyClip;
import dev.gmodcraft.world.SkyTri;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.phys.Vec3;

/**
 * v15: a Minecraft projectile that hits a GMod entity's collision (host triangles flagged
 * kTriDynamic: props, doors, breakables, glass) is reported to the host as kEvProjectileHit, which
 * damages and pushes that entity in GMod. Hits on the host's static world and on Minecraft blocks
 * are not reported. Players and NPCs are hit through their stand-ins (kEvHitActor), never here.
 */
public final class ProjectileHits {
	/** Vanilla's trident damage (ThrownTrident.onHitEntity), before enchantments. */
	private static final float TRIDENT_DAMAGE = 8.0F;
	/** Vanilla's small fireball damage (SmallFireball.onHitEntity). */
	private static final float SMALL_FIREBALL_DAMAGE = 5.0F;

	private ProjectileHits() {
	}

	/** True if {@code hit} landed on a GMod entity's collision. */
	public static boolean onHostEntity(SkyClip.SkyrimHitResult hit) {
		SkyTri tri = hit.hit.tri();
		return tri != null && tri.dynamic;
	}

	/** ProjectileMixin: a projectile hit host geometry. Server side only; pushes the event if it counts. */
	public static void report(Projectile projectile, SkyClip.SkyrimHitResult hit) {
		if (projectile.level().isClientSide() || !onHostEntity(hit) || !ServerHost.linked()) {
			return;
		}
		int kind = kindOf(projectile);
		if (kind == 0) {
			return;
		}
		Vec3 v = projectile.getDeltaMovement();
		double speed = v.length();
		float yaw = (float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (Mth.atan2(v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG);
		float damage = damageOf(projectile, kind, speed);
		long shooter = projectile.getOwner() instanceof ServerPlayer player ? ServerHost.steamIdOf(player) : 0L;
		Vec3 at = hit.getLocation();
		// v28 (F1): a burning projectile (flame arrow, fire charge) sets what it hit on fire
		boolean onFire = dev.gmodcraft.world.FireCrossover.enabled(projectile.level()) && (kind == Proto.PROJ_SMALL_FIREBALL || projectile.isOnFire());
		ServerLink.INSTANCE.pushEvent(Proto.EV_PROJECTILE_HIT, 0, shooter, (float) at.x, (float) at.y, (float) at.z, yaw, Float.floatToRawIntBits(pitch),
			kind | (onFire ? Proto.PROJ_ON_FIRE : 0),
			Float.floatToRawIntBits(damage), Float.floatToRawIntBits((float) speed));
		if (ProjectileTestHook.ENABLED) {
			GmodCraft.LOG.info("GmodCraft test: projectile kind {} hit a GMod entity at {} {} {} (damage {}, speed {})", kind, at.x, at.y, at.z, damage, speed);
		}
	}

	/** ProjectileKind for the projectile, or 0 if it isn't reported (explosive ones: kEvExplosion). */
	public static int kindOf(Projectile p) {
		if (p instanceof ThrownTrident) {
			return Proto.PROJ_TRIDENT;
		}
		if (p instanceof SpectralArrow) {
			return Proto.PROJ_SPECTRAL_ARROW;
		}
		if (p instanceof Arrow arrow) {
			return arrow.getColor() > 0 ? Proto.PROJ_TIPPED_ARROW : Proto.PROJ_ARROW;
		}
		if (p instanceof AbstractArrow) {
			return Proto.PROJ_ARROW;
		}
		if (p instanceof Snowball) {
			return Proto.PROJ_SNOWBALL;
		}
		if (p instanceof ThrownEgg) {
			return Proto.PROJ_EGG;
		}
		if (p instanceof ThrowableItemProjectile) {
			return Proto.PROJ_THROWN;
		}
		if (p instanceof SmallFireball) {
			return Proto.PROJ_SMALL_FIREBALL;
		}
		return 0;
	}

	/**
	 * Minecraft damage as vanilla would deal it to a creature, without enchantments (they need a
	 * target): arrows ceil(speed x base damage) plus the random crit bonus, tridents 8, small
	 * fireballs 5, thrown items 0 (the host gives those a token amount).
	 */
	static float damageOf(Projectile p, int kind, double speed) {
		return switch (kind) {
			case Proto.PROJ_TRIDENT -> TRIDENT_DAMAGE;
			case Proto.PROJ_SMALL_FIREBALL -> SMALL_FIREBALL_DAMAGE;
			case Proto.PROJ_ARROW, Proto.PROJ_SPECTRAL_ARROW, Proto.PROJ_TIPPED_ARROW -> {
				AbstractArrow arrow = (AbstractArrow) p;
				double base = ((AbstractArrowAccessor) arrow).gmodcraft$getBaseDamage();
				int d = Mth.ceil(Mth.clamp(speed * base, 0.0, 2.147483647E9));
				if (arrow.isCritArrow()) {
					d = (int) Math.min((long) d + p.getRandom().nextInt(d / 2 + 2), Integer.MAX_VALUE);
				}
				yield d;
			}
			default -> 0.0F;
		};
	}
}
