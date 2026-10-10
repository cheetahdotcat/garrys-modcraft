package dev.gmodcraft.client.mixin;

import dev.gmodcraft.client.WeaponIconRender;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** H3: GMod weapon icons and the held weapon's ammo in Minecraft's GUI (hotbar, inventory, containers). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiWeaponIconMixin {
	@Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
		at = @At("HEAD"), cancellable = true)
	private void gmodcraft$weaponIcon(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		if (WeaponIconRender.drawIcon((GuiGraphicsExtractor) (Object) this, stack, x, y)) {
			ci.cancel();
		}
	}

	@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
	private void gmodcraft$weaponAmmo(Font font, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
		WeaponIconRender.drawAmmo((GuiGraphicsExtractor) (Object) this, font, stack, x, y);
	}
}
