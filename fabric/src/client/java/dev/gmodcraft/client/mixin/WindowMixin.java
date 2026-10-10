package dev.gmodcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.gmodcraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The MC window is hidden while linked; Skyrim has the real focus, so pretend we do too. */
@Mixin(Window.class)
public abstract class WindowMixin {
	/**
	 * I1b: right where the SDL window is made (Window.createWindow returns its handle). Not the
	 * constructor's TAIL: of Window's two constructors Minecraft calls the 8-argument one directly, and
	 * Mixin's "<init>" TAIL injection landed only in the 7-argument one that delegates to it (seen in
	 * the -Dmixin.debug.export dump), so the early hide never ran.
	 */
	@Inject(method = "createWindow", at = @At("RETURN"))
	private void gmodcraft$hideAtOnce(com.mojang.renderpearl.api.device.GpuBackend backend, int width, int height, String title,
		CallbackInfoReturnable<Long> cir) {
		SkyClient.windowCreated(cir.getReturnValueJ());
	}

	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$focused(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.tookOver()) {
			// Focused while Skyrim is connected; if Skyrim goes away, act unfocused so MC
			// never tries to grab the (hidden) mouse.
			cir.setReturnValue(SkyClient.linked());
		}
	}

	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void gmodcraft$notIconified(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(false);
		}
	}
}
