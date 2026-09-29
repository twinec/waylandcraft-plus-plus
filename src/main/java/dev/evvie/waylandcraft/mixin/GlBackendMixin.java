package dev.evvie.waylandcraft.mixin;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.system.Platform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.opengl.GlBackend;

import dev.evvie.waylandcraft.gpu.EglAvailability;

@Mixin(GlBackend.class)
public class GlBackendMixin {

	@Inject(method = "setWindowHints", at = @At("TAIL"))
	public void changeContextApi(CallbackInfo info) {
		if(Platform.get() != Platform.LINUX) return;
		if(!EglAvailability.probe()) return;
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");
	}

}
