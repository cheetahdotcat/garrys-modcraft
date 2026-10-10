package dev.gmodcraft.mixin;

import dev.gmodcraft.world.SurfaceBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * M1: a hoe or a plant used on the map's ground converts the clicked surface cell into the real
 * block of its material first (SurfaceBlocks.convertForUse); vanilla then tills it or plants on it.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@ModifyVariable(method = "useItemOn", at = @At("HEAD"), argsOnly = true)
	private BlockHitResult gmodcraft$mapSurfaceToBlock(BlockHitResult hit, ServerPlayer player, Level level, ItemStack stack, InteractionHand hand) {
		return level instanceof ServerLevel server ? SurfaceBlocks.convertForUse(player, server, stack, hit) : hit;
	}
}
