package dev.evvie.waylandcraft.compat;

import dev.evvie.waylandcraft.item.WindowItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;

public class PolymerCompat {

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("polymer");
	}

	/**
	 * Returns the appropriate WindowItem instance.
	 * When Polymer is present a PolymerWindowItem is returned so that vanilla
	 * clients connected to a Polymer server see a recognisable placeholder item
	 * instead of an unknown custom item.
	 *
	 * The PolymerWindowItem class is only classloaded when Polymer is on the
	 * classpath, keeping the dependency optional at runtime.
	 */
	public static Item createWindowItem() {
		if (isLoaded()) {
			return new PolymerWindowItem();
		}
		return new WindowItem();
	}

}
