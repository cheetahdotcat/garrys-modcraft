package dev.gmodcraft.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v35 (S1): the creative inventory tab wraps the player's slots, and the wrapper's own index is the
 * menu index (36-44 for the hotbar), not the inventory slot: McScreen::hoveredSlot unwraps it.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface CreativeSlotWrapperAccessor {
	@Accessor("target")
	Slot gmodcraft$target();
}
