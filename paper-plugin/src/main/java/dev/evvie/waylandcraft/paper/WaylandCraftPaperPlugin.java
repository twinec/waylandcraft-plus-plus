package dev.evvie.waylandcraft.paper;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.Nullable;

/**
 * Companion Paper plugin for WaylandCraft++, giving/validating window
 * items on servers that can't load the Fabric mod at all. See
 * ../../../../../docs (or the repo README) for how this fits together
 * with the Fabric client's NBT fallback path (WindowHandle#fromCustomData).
 *
 * Mirrors dev.evvie.waylandcraft.item.ServerItemManager's give/validate
 * logic, but using Bukkit APIs (PersistentDataContainer instead of a
 * real DataComponentType, BukkitScheduler instead of ServerTickEvents,
 * plain plugin-messaging channels instead of Fabric's PayloadTypeRegistry).
 */
public class WaylandCraftPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {

	public static final String NAMESPACE = "waylandcraft";
	public static final String GIVE_ITEMS_CHANNEL = NAMESPACE + ":give_items";
	public static final String ALIVE_WINDOWS_CHANNEL = NAMESPACE + ":alive_windows";
	public static final String HELLO_CHANNEL = NAMESPACE + ":hello";

	private static final int GIVE_COOLDOWN_TICKS = 10;
	private static final int ITEM_ENTITY_GRACE_TICKS = 10;

	private final Map<UUID, Set<Long>> aliveWindows = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> giveCooldown = new ConcurrentHashMap<>();

	private Material windowMaterial = Material.PAPER;

	@Override
	public void onEnable() {
		saveDefaultConfig();

		Material configured = Material.matchMaterial(getConfig().getString("item", "PAPER"));
		windowMaterial = configured != null ? configured : Material.PAPER;

		var messenger = getServer().getMessenger();
		messenger.registerIncomingPluginChannel(this, GIVE_ITEMS_CHANNEL, this);
		messenger.registerIncomingPluginChannel(this, ALIVE_WINDOWS_CHANNEL, this);
		messenger.registerOutgoingPluginChannel(this, HELLO_CHANNEL);

		getServer().getPluginManager().registerEvents(this, this);

		getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		// Diagnostic-only marker so we can tell which connected players
		// are running the WaylandCraft++ client -- not required for the
		// item system itself to work. See WireFormat's doc comment about
		// Fabric's client sometimes discarding unrecognized S2C channels.
		event.getPlayer().sendPluginMessage(this, HELLO_CHANNEL, new byte[0]);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		try {
			if(channel.equals(GIVE_ITEMS_CHANNEL)) handleGiveItems(player, message);
			else if(channel.equals(ALIVE_WINDOWS_CHANNEL)) handleAliveWindows(player, message);
		} catch(Exception e) {
			getLogger().warning("Failed to handle " + channel + " from " + player.getName() + ": " + e);
		}
	}

	private void handleAliveWindows(Player player, byte[] message) {
		WireFormat.Reader reader = new WireFormat.Reader(message);
		long[] handles = reader.readLongArray();

		Set<Long> set = new HashSet<>();
		for(long h : handles) set.add(h);
		aliveWindows.put(player.getUniqueId(), set);
	}

	private void handleGiveItems(Player player, byte[] message) {
		if(giveCooldown.getOrDefault(player.getUniqueId(), 0) > 0) return;
		giveCooldown.put(player.getUniqueId(), GIVE_COOLDOWN_TICKS);

		WireFormat.Reader reader = new WireFormat.Reader(message);
		long[] handles = reader.readLongArray();
		boolean missingOnly = reader.readBool();

		Set<Long> deduped = new LinkedHashSet<>();
		for(long h : handles) deduped.add(h);

		for(long handle : deduped) {
			if(missingOnly && hasItemFor(player, handle)) continue;
			giveItem(player, handle);
		}
	}

	private boolean hasItemFor(Player player, long handle) {
		for(ItemStack stack : player.getInventory().getContents()) {
			WindowHandleData data = readHandle(stack);
			if(data != null && data.handle() == handle && data.matchesPlayer(player)) return true;
		}
		return false;
	}

	private void giveItem(Player player, long handle) {
		ItemStack stack = new ItemStack(windowMaterial, 1);
		ItemMeta meta = stack.getItemMeta();
		WindowHandleData.forPlayer(player, handle).writeTo(meta);
		stack.setItemMeta(meta);
		player.getInventory().addItem(stack);
	}

	@Nullable
	private WindowHandleData readHandle(@Nullable ItemStack stack) {
		if(stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) return null;
		return WindowHandleData.readFrom(stack.getItemMeta());
	}

	// Tick-equivalent of ServerItemManager's ServerTickEvents.StartLevelTick
	// loop: decays give-cooldowns, discards inventory/dropped items whose
	// handle isn't in the owning player's last-reported alive set.
	private void tick() {
		for(Player player : getServer().getOnlinePlayers()) {
			giveCooldown.computeIfPresent(player.getUniqueId(), (k, v) -> Math.max(0, v - 1));
			sweepInventory(player);
		}

		for(World world : getServer().getWorlds()) {
			sweepDroppedItems(world);
		}
	}

	private void sweepInventory(Player player) {
		Set<Long> alive = aliveWindows.getOrDefault(player.getUniqueId(), Set.of());
		PlayerInventory inv = player.getInventory();
		for(int i = 0; i < inv.getSize(); i++) {
			WindowHandleData data = readHandle(inv.getItem(i));
			if(data == null) continue;
			if(!data.matchesPlayer(player) || !alive.contains(data.handle())) {
				inv.setItem(i, null);
			}
		}
	}

	private void sweepDroppedItems(World world) {
		for(Entity entity : world.getEntities()) {
			if(!(entity instanceof Item itemEntity)) continue;

			WindowHandleData data = readHandle(itemEntity.getItemStack());
			if(data == null) continue;

			Player owner = getServer().getPlayer(data.player());
			boolean valid = owner != null && aliveWindows.getOrDefault(owner.getUniqueId(), Set.of()).contains(data.handle());
			if(valid) continue;
			if(itemEntity.getTicksLived() <= ITEM_ENTITY_GRACE_TICKS) continue;

			itemEntity.getWorld().spawnParticle(Particle.FLAME, itemEntity.getLocation(), 10, 0.15, 0.2, 0.15, 0.1);
			itemEntity.remove();
		}
	}

}
