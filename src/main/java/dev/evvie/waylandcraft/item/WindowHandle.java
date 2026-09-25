package dev.evvie.waylandcraft.item;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

public record WindowHandle(UUID player, long handle) {

	public static final Codec<WindowHandle> CODEC = RecordCodecBuilder.create(builder -> {
		return builder.group(
				UUIDUtil.CODEC.fieldOf("player").forGetter(WindowHandle::player),
				Codec.LONG.fieldOf("handle").forGetter(WindowHandle::handle)
		).apply(builder, WindowHandle::new);
	});

	// Namespace/keys the companion Paper plugin (paper-plugin/) writes
	// into, matching WindowHandleData on that side.
	private static final String PAPER_VALUES_COMPOUND = "PublicBukkitValues";
	private static final String PAPER_PLAYER_KEY = "waylandcraft:player";
	private static final String PAPER_HANDLE_KEY = "waylandcraft:handle";

	public static WindowHandle forPlayer(Player player, long handle) {
		return new WindowHandle(getPlayerUUID(player), handle);
	}

	public static UUID getPlayerUUID(Player player) {
		return player.getGameProfile().id();
	}

	public boolean matchesPlayer(Player player) {
		return getPlayerUUID(player).equals(this.player());
	}

	/**
	 * Fallback for window items given by the companion Paper plugin
	 * instead of a real Fabric server -- Paper can't register a genuine
	 * WINDOW_HANDLE DataComponentType (see WaylandCraft++'s README/docs
	 * for why), so it instead writes {player, handle} via Bukkit's
	 * PersistentDataContainer, which Paper stores inside the item's real
	 * vanilla CUSTOM_DATA component under a "PublicBukkitValues"
	 * compound. Not verified against a live Paper server capture --
	 * check this first if fallback parsing silently fails.
	 */
	@Nullable
	public static WindowHandle fromCustomData(ItemStack item) {
		CustomData custom = item.get(DataComponents.CUSTOM_DATA);
		if(custom == null) return null;

		CompoundTag root = custom.copyTag();
		if(!root.contains(PAPER_VALUES_COMPOUND)) return null;

		CompoundTag values = root.getCompoundOrEmpty(PAPER_VALUES_COMPOUND);
		String playerStr = values.getStringOr(PAPER_PLAYER_KEY, "");
		if(playerStr.isEmpty()) return null;

		long handle = values.getLongOr(PAPER_HANDLE_KEY, Long.MIN_VALUE);
		if(handle == Long.MIN_VALUE) return null;

		try {
			return new WindowHandle(UUID.fromString(playerStr), handle);
		} catch(IllegalArgumentException e) {
			return null;
		}
	}

}
