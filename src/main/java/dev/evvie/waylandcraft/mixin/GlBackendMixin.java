package dev.evvie.waylandcraft.mixin;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.system.Platform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.renderpearl.backend.opengl.GlBackend;

import dev.evvie.waylandcraft.gpu.EglAvailability;

@Mixin(GlBackend.class)
public class GlBackendMixin {

	@Inject(method = "createWindow", at = @At("HEAD"))
	public void changeContextApi(String title, int width, int height, long monitor, CallbackInfoReturnable<Long> info) {
		if(Platform.get() != Platform.LINUX) return;
		if(!EglAvailability.probe()) return;
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");
	}

}
