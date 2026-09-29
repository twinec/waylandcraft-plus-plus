package dev.evvie.waylandcraft.gpu;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;

import dev.evvie.waylandcraft.WaylandCraftCommon;

/**
 * Probes, once, whether GLFW can actually create an OpenGL context via EGL
 * on this system. Some NVIDIA PRIME/Optimus offload configurations fail
 * EGL context creation entirely (GLFW_ERROR 0x10008), which would otherwise
 * take down the whole OpenGL backend if we unconditionally forced EGL for
 * DMA-BUF/EGLImage compositor integration.
 */
public final class EglAvailability {

	private static Boolean available = null;

	private EglAvailability() {}

	public static boolean probe() {
		if(available == null) {
			available = runProbe();
			if(!available) {
				WaylandCraftCommon.LOGGER.warn("[waylandcraft/gpu] EGL context creation is unavailable on this system (NVIDIA PRIME/Optimus offload is a common cause) — falling back to native GL context creation. DMA-BUF/EGLImage compositor integration will be disabled.");
			}
		}
		return available;
	}

	private static boolean runProbe() {
		if(!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) return false;

		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");

		long handle = SDLVideo.SDL_CreateWindow("waylandcraft-egl-probe", 1, 1, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "0");

		if(handle == 0L) return false;

		SDLVideo.SDL_DestroyWindow(handle);
		return true;
	}

}
