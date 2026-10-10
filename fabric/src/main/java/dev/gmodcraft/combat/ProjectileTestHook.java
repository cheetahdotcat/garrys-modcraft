package dev.gmodcraft.combat;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.GLink;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Test only (tools/test_mc.sh --server sets GMODCRAFT_TEST_PROJECTILES=1; off otherwise): for each
 * host actor named {@code pj:<kind>} (arrow, snowball, trident, egg), shoots one projectile of that
 * kind once, from 1 block east and 1 block above the actor's feet, flying east (+x), so the
 * harness can put a GMod entity's collision in its way and check kEvProjectileHit. Forces the
 * chunks it needs to load (a dedicated server without players ticks nothing else). Logs
 * "GmodCraft test: shot ...".
 */
public final class ProjectileTestHook {
	public static final boolean ENABLED = "1".equals(System.getenv("GMODCRAFT_TEST_PROJECTILES"));
	private static final String PREFIX = "pj:";
	/** Blocks per tick. */
	private static final double SPEED = 2.0;

	private static final Map<Integer, Boolean> SHOT = new HashMap<>();

	private ProjectileTestHook() {
	}

	static void tick(ServerLevel level, Collection<GLink.Actor> actors) {
		for (GLink.Actor a : actors) {
			if (!a.name().startsWith(PREFIX) || Boolean.TRUE.equals(SHOT.get(a.entId()))) {
				continue;
			}
			double x = a.x() + 1.0, y = a.y() + 1.0, z = a.z();
			BlockPos from = BlockPos.containing(x, y, z);
			if (!SHOT.containsKey(a.entId())) {
				SHOT.put(a.entId(), false);
				for (int dx = 0; dx <= 1; dx++) {
					level.setChunkForced(SectionPos.blockToSectionCoord(from.getX()) + dx, SectionPos.blockToSectionCoord(from.getZ()), true);
				}
			}
			if (!level.isPositionEntityTicking(from) || !level.isPositionEntityTicking(from.east(8))) {
				continue;
			}
			String kind = a.name().substring(PREFIX.length());
			Projectile p = switch (kind) {
				case "arrow" -> new Arrow(level, x, y, z, new ItemStack(Items.ARROW), null);
				case "trident" -> new ThrownTrident(level, x, y, z, new ItemStack(Items.TRIDENT));
				case "snowball" -> new Snowball(level, x, y, z, new ItemStack(Items.SNOWBALL));
				case "egg" -> new ThrownEgg(level, x, y, z, new ItemStack(Items.EGG));
				default -> null;
			};
			SHOT.put(a.entId(), true);
			if (p == null) {
				GmodCraft.LOG.warn("GmodCraft test: unknown projectile kind '{}'", kind);
				continue;
			}
			if (p instanceof AbstractArrow arrow) {
				arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
			}
			p.setDeltaMovement(SPEED, 0.0, 0.0);
			boolean added = level.addFreshEntity(p);
			GmodCraft.LOG.info("GmodCraft test: shot {} for ent {} from {} {} {} east at {} blocks/tick (added {})", kind, a.entId(), x, y, z, SPEED, added);
		}
	}
}
