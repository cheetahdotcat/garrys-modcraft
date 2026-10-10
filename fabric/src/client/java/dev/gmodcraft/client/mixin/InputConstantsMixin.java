package dev.gmodcraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.gmodcraft.client.InputBridge;
import dev.gmodcraft.client.SkyClient;
import dev.gmodcraft.link.MouseOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keyboard state and mouse capture come from Skyrim while linked, not from SDL. The system mouse
 * (InputConstants.grabMouse / releaseMouse: a warp to the window centre and SDL relative mouse mode)
 * is never touched by a Minecraft started for GMod (startHidden) or once GMod has connected (I1,
 * MouseOwner). MouseHandler keeps its own mouseGrabbed flag either way, so screens and clicks work.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void gmodcraft$isKeyDown(int key, CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.tookOver()) {
			cir.setReturnValue(InputBridge.isKeyDown(key));
		}
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void gmodcraft$grabMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (!MouseOwner.mayTouchSystemMouse(SkyClient.startHidden(), SkyClient.tookOver())) {
			SkyClient.mouseBlocked("grab");
			ci.cancel();
		} else {
			SkyClient.mouse().systemGrab(true);
		}
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void gmodcraft$releaseMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (!MouseOwner.mayTouchSystemMouse(SkyClient.startHidden(), SkyClient.tookOver())) {
			SkyClient.mouseBlocked("release");
			ci.cancel();
		} else {
			SkyClient.mouse().systemGrab(false);
		}
	}
}
