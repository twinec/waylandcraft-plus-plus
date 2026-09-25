package dev.evvie.waylandcraft.item;

import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * A window item's {player, handle} pair, stored on the wire inside the
 * item's ordinary vanilla CUSTOM_DATA component rather than a custom
 * DataComponentType. A custom DataComponentType's network id is assigned
 * by registration order at registry-freeze time, which isn't guaranteed
 * to match between a client (with many more mods, e.g. rendering/QoL
 * mods that also register components) and a bare Fabric server running
 * only WaylandCraft -- causing "No value with id N" decode failures for
 * *any* item once the ordinals disagree. CUSTOM_DATA is a fixed vanilla
 * registry entry present at the same id everywhere, so it can't drift.
 * This also happens to be the exact format the companion Paper plugin
 * (paper-plugin/) already writes via Bukkit's PersistentDataContainer,
 * since Paper can't register a custom DataComponentType at all -- so
 * Fabric and Paper servers now produce identical wire data.
 */
public record WindowHandle(UUID player, long handle) {

	private static final String VALUES_COMPOUND = "PublicBukkitValues";
	private static final String PLAYER_KEY = "waylandcraft:player";
	private static final String HANDLE_KEY = "waylandcraft:handle";

	public static WindowHandle forPlayer(Player player, long handle) {
		return new WindowHandle(getPlayerUUID(player), handle);
	}

	public static UUID getPlayerUUID(Player player) {
		return player.getGameProfile().id();
	}

	public boolean matchesPlayer(Player player) {
		return getPlayerUUID(player).equals(this.player());
	}

	public void writeTo(ItemStack stack) {
		CustomData existing = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		CompoundTag root = existing.copyTag();
		CompoundTag values = root.getCompoundOrEmpty(VALUES_COMPOUND);
		values.putString(PLAYER_KEY, player.toString());
		values.putLong(HANDLE_KEY, handle);
		root.put(VALUES_COMPOUND, values);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	/** Strips this handle data from an item, e.g. before showing it to a
	 * client that doesn't understand it. */
	public static void strip(ItemStack stack) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if(custom == null) return;

		CompoundTag root = custom.copyTag();
		if(!root.contains(VALUES_COMPOUND)) return;

		root.remove(VALUES_COMPOUND);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	@Nullable
	public static WindowHandle from(ItemStack item) {
		CustomData custom = item.get(DataComponents.CUSTOM_DATA);
		if(custom == null) return null;

		CompoundTag root = custom.copyTag();
		if(!root.contains(VALUES_COMPOUND)) return null;

		CompoundTag values = root.getCompoundOrEmpty(VALUES_COMPOUND);
		String playerStr = values.getStringOr(PLAYER_KEY, "");
		if(playerStr.isEmpty()) return null;

		long handle = values.getLongOr(HANDLE_KEY, Long.MIN_VALUE);
		if(handle == Long.MIN_VALUE) return null;

		try {
			return new WindowHandle(UUID.fromString(playerStr), handle);
		} catch(IllegalArgumentException e) {
			return null;
		}
	}

}
