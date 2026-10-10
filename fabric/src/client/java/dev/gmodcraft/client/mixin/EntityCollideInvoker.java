package dev.gmodcraft.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Entity.collide for CarryClient: the platform's motion collides like the player's own move. */
@Mixin(Entity.class)
public interface EntityCollideInvoker {
	@Invoker("collide")
	Vec3 gmodcraft$collide(Vec3 movement);
}
