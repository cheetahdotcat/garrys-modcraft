package dev.gmodcraft.mixin;

import dev.gmodcraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sand and gravel rest on Skyrim ground instead of falling forever through it; mirror blocks never fall. */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$restOnSkyrim(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		// a hull world's mirror block stays where the map has it (GMod's geometry doesn't fall either)
		if (SkyCollision.SERVER.supportsFromBelow(pos) || dev.gmodcraft.world.HullWorld.isMirror(level, pos)) {
			ci.cancel();
			return;
		}
		// v44: nor does one placed by GMod's physics a moment ago (no churn, whatever the prop layer says), nor one a
		// GMod prop holds up. Vanilla never reschedules this tick (only onPlace / a neighbour change do), so both
		// schedule the next check themselves: when the hold ends, and every PROP_RECHECK_TICKS while a prop holds it
		// (a prop going away sends Minecraft no block update).
		long held = dev.gmodcraft.world.PhysicsBlocks.justPlacedRemaining(level, pos);
		if (held > 0) {
			level.scheduleTick(pos, state.getBlock(), (int) Math.min(Integer.MAX_VALUE - 1, held) + 1);
			ci.cancel();
			return;
		}
		if (dev.gmodcraft.world.PhysicsBlocks.propUnder(level, pos)) {
			level.scheduleTick(pos, state.getBlock(), dev.gmodcraft.world.PhysicsBlockRules.PROP_RECHECK_TICKS);
			ci.cancel();
			return;
		}
		dev.gmodcraft.world.PhysicsBlocks.placedFalls(level, pos);  // diagnostics: one of ours falls after all
	}
}
