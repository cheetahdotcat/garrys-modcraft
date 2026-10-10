package dev.gmodcraft.client.mixin;

import net.minecraft.world.entity.AbstractInterpolationHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** T2b (HeldSmoothing): an entity's client interpolation length, in ticks. */
@Mixin(AbstractInterpolationHandler.class)
public interface InterpolationStepsAccessor {
	@Accessor("interpolationSteps")
	int gmodcraft$interpolationSteps();

	@Accessor("interpolationSteps")
	void gmodcraft$setInterpolationSteps(int steps);

	@Accessor("entity")
	Entity gmodcraft$entity();
}
