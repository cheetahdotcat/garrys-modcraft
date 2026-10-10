package dev.gmodcraft.prop;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code gmodcraft:gmod_prop}: one GMod prop (P1, protocol v33) as a Minecraft item; which one is the
 * {@code gmodcraft:prop} component ({@link PropData}). Using it on any surface (a Minecraft block or
 * GMod geometry) asks the GMod server to spawn the prop there ({@link GmodProps#place}); the stack
 * shrinks only when GMod says it did.
 */
public final class GmodPropItem extends Item {
	public GmodPropItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		PropData d = stack.get(GmodProps.PROP);
		if (d == null || d.inert()) {
			return super.getName(stack);
		}
		return Component.translatable("item.gmodcraft.gmod_prop.named", d.shortName());
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		PropData d = stack.get(GmodProps.PROP);
		if (d == null || d.inert()) {
			tooltip.accept(Component.translatable("item.gmodcraft.gmod_prop.inert").withStyle(ChatFormatting.GRAY));
			return;
		}
		tooltip.accept(Component.literal(d.model()).withStyle(ChatFormatting.GRAY));
		if (d.skin() != 0 || !d.material().isEmpty()) {
			tooltip.accept(Component.translatable("item.gmodcraft.gmod_prop.look", d.skin(), d.material().isEmpty() ? "-" : d.material())
				.withStyle(ChatFormatting.DARK_GRAY));
		}
		tooltip.accept(Component.translatable("item.gmodcraft.gmod_prop.hint").withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		ItemStack stack = context.getItemInHand();
		PropData d = stack.get(GmodProps.PROP);
		if (d == null || d.inert() || context.getPlayer() == null) {
			return InteractionResult.PASS;
		}
		if (context.getLevel().isClientSide()) {
			return InteractionResult.SUCCESS; // the server asks GMod; the swing shows it was taken
		}
		if (context.getPlayer() instanceof ServerPlayer player) {
			GmodProps.place(player, context.getHand(), d, context.getClickLocation());
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return true;
	}
}
