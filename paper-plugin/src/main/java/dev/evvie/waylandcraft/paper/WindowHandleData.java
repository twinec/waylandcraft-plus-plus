package dev.evvie.waylandcraft.paper;

import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * The {player, handle} pair identifying a toplevel window, mirroring
 * dev.evvie.waylandcraft.item.WindowHandle on the Fabric side.
 *
 * Stored via Bukkit's PersistentDataContainer, which Paper persists
 * inside the item's real vanilla minecraft:custom_data component
 * (nested under a "PublicBukkitValues" compound) -- so the Fabric
 * client's fallback parser (WindowHandle#fromCustomData) can read it
 * back out via DataComponents.CUSTOM_DATA without this plugin needing
 * any custom registry entry, which Paper can't provide anyway.
 */
public record WindowHandleData(UUID player, long handle) {

	public static final NamespacedKey PLAYER_KEY = new NamespacedKey("waylandcraft", "player");
	public static final NamespacedKey HANDLE_KEY = new NamespacedKey("waylandcraft", "handle");

	public void writeTo(ItemMeta meta) {
		meta.getPersistentDataContainer().set(PLAYER_KEY, PersistentDataType.STRING, player.toString());
		meta.getPersistentDataContainer().set(HANDLE_KEY, PersistentDataType.LONG, handle);
	}

	@Nullable
	public static WindowHandleData readFrom(ItemMeta meta) {
		var pdc = meta.getPersistentDataContainer();
		String playerStr = pdc.get(PLAYER_KEY, PersistentDataType.STRING);
		Long handle = pdc.get(HANDLE_KEY, PersistentDataType.LONG);
		if(playerStr == null || handle == null) return null;

		try {
			return new WindowHandleData(UUID.fromString(playerStr), handle);
		} catch(IllegalArgumentException e) {
			return null;
		}
	}

	public static WindowHandleData forPlayer(Player player, long handle) {
		return new WindowHandleData(player.getUniqueId(), handle);
	}

	public boolean matchesPlayer(Player player) {
		return player.getUniqueId().equals(this.player);
	}

}
