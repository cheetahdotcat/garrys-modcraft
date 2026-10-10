package dev.gmodcraft.mixin;

import dev.gmodcraft.GmodCraftConfig;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v34 rule mobCapPercent: the natural spawning caps (NaturalSpawner reads each category's
 * getMaxInstancesPerChunk) scaled by the rule; 100 = vanilla, 0 = no natural spawns.
 */
@Mixin(MobCategory.class)
public abstract class MobCategoryCapMixin {
	@Inject(method = "getMaxInstancesPerChunk", at = @At("RETURN"), cancellable = true)
	private void gmodcraft$scaleCap(CallbackInfoReturnable<Integer> cir) {
		int percent = GmodCraftConfig.rules().mobCapPercent();
		if (percent != 100) {
			cir.setReturnValue(scaled(cir.getReturnValueI(), percent));
		}
	}

	private static int scaled(int vanilla, int percent) {
		return (int) Math.min(Integer.MAX_VALUE, ((long) vanilla * percent + 50) / 100);
	}
}
