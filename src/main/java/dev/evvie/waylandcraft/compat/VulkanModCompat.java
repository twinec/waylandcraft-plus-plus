package dev.evvie.waylandcraft.compat;

import net.fabricmc.loader.api.FabricLoader;

public class VulkanModCompat {

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("vulkanmod");
	}

}
