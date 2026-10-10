package dev.gmodcraft.client.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why a connection ended (the reason a failed or lost join reports to the host, JoinStatus). */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
	@Accessor("details")
	DisconnectionDetails gmodcraft$details();
}
