package dev.evvie.waylandcraft.compat;

import net.fabricmc.loader.api.FabricLoader;

public class BerylCompat {

	/**
	 * Returns true when Beryl is loaded and its rendering effects are active.
	 * When active, WaylandCraft falls back to entity-based rendering so that
	 * Beryl's pipeline does not corrupt the window framebuffers.
	 */
	public static boolean isShaderActive() {
		if (!FabricLoader.getInstance().isModLoaded("beryl")) return false;
		return BerylApiHolder.isActive();
	}

	/**
	 * Separate class so the Beryl API is only classloaded when Beryl is present.
	 */
	private static class BerylApiHolder {
		static boolean isActive() {
			return gg.galaxygaming.beryl.api.BerylApi.getInstance().isActive();
		}
	}

}
