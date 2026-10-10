package dev.gmodcraft.weapon;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code gmodcraft:gmod_weapon}: one GMod weapon (SWEP) as a Minecraft item (hybrid mode, D-016).
 * Which one is the {@code gmodcraft:swep} component ({@link SwepData}); without it the stack is
 * inert. Stacks to 1. Using it does nothing on the Minecraft side (GMod fires the weapon, H2), and
 * it never breaks blocks; the Fabric attack/use callbacks in {@link GmodWeapons} refuse the rest.
 */
public final class GmodWeaponItem extends Item {
	public GmodWeaponItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		SwepData d = stack.get(GmodWeapons.SWEP);
		if (d == null) {
			return super.getName(stack);
		}
		return Component.literal(d.printName().isEmpty() ? d.weaponClass() : d.printName());
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		SwepData d = stack.get(GmodWeapons.SWEP);
		if (d == null) {
			tooltip.accept(Component.translatable("item.gmodcraft.gmod_weapon.inert").withStyle(ChatFormatting.GRAY));
			return;
		}
		tooltip.accept(Component.translatable("item.gmodcraft.gmod_weapon.class", d.weaponClass()).withStyle(ChatFormatting.GRAY));
		if (d.clip1() >= 0) {
			tooltip.accept(Component.translatable("item.gmodcraft.gmod_weapon.clip", d.clip1()).withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		return InteractionResult.PASS;
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return false;
	}
}
