package dev.gmodcraft.mixin;

import dev.gmodcraft.world.BlockDeltas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change on the server (Level.setBlock ends in updatePOIOnBlockStateChange, whatever
 * the update flags) marks its section for the server link's block ring (BlockDeltas).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockChangeMixin {
	@Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
	private void gmodcraft$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		BlockDeltas.blockChanged((ServerLevel) (Object) this, pos);
	}
}
