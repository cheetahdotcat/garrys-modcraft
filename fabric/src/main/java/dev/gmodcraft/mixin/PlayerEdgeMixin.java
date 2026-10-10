package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyTri;
import dev.gmodcraft.world.TriCollider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Crouching stops at edges of the host's ground too. Minecraft's edge stop asks whether the space
 * under the player (a step ahead) is free of block collision. A linked player doesn't collide with
 * the host's voxels (it collides with the exact triangles, TriCollider), so that query never sees the
 * host's ground: every direction looks like a drop. Here the same triangles movement uses count as
 * ground; Minecraft blocks and the walls of dug holes still come from the original query.
 *
 * <p>Both sides run this (client movement and the server's re-check of it), each with its own store.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
	@WrapOperation(
		method = "canFallAtLeast",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z")
	)
	private boolean gmodcraft$hostGroundStopsTheFall(Level level, Entity entity, AABB below, Operation<Boolean> original) {
		if (!original.call(level, entity, below)) {
			return false; // a block (or a dug hole's wall) is there
		}
		if (!SkyCollision.usesSmoothCollider(entity)) {
			return true; // the query above already included the host's voxels
		}
		// The query box is the player's footprint (shifted a step ahead) from its feet down to the fall depth.
		double x = (below.minX + below.maxX) * 0.5, z = (below.minZ + below.maxZ) * 0.5, feet = below.maxY;
		double step = ((Player) (Object) this).maxUpStep();
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.of(level).trianglesNear(new AABB(x - 1, below.minY - 1, z - 1, x + 1, feet + step + 1, z + 1), tris);
		return tris.isEmpty() || !TriCollider.hasGround(tris, x, feet, z, feet - below.minY, step);
	}
}
