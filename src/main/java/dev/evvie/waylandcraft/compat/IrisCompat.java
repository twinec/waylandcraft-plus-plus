package dev.evvie.waylandcraft.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

public class IrisCompat {
	
	public static boolean isShaderActive() {
		if(!FabricLoader.getInstance().isModLoaded("iris")) return false;
		return IrisApi.getInstance().isShaderPackInUse();
	}
	
}
