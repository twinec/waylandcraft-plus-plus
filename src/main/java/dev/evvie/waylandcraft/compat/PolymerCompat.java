package dev.evvie.waylandcraft.compat;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerClientDecoded;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.item.WindowHandle;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.network.WaylandCraftPresence;

/**
 * Registers WaylandCraft's items as Polymer overlays, using
 * {@link PolymerItem#registerOverlay} rather than implementing PolymerItem
 * directly on the item classes -- this keeps WindowItem free of any
 * compile-time or class-load-time dependency on Polymer, so the mod works
 * fine with Polymer absent.
 *
 * This class itself must never be referenced unless polymer-core is
 * confirmed loaded -- see the call site in WaylandCraftCommon.
 */
public class PolymerCompat {

	// The model shown to non-WaylandCraft clients. DataComponents.ITEM_MODEL
	// resolves through the *item definition* convention (assets/<ns>/items/
	// <path>.json, the same kind of file as items/window.json itself), not
	// directly to a models/ file -- so this points at a small dedicated
	// definition (items/window_icon.json) that in turn references the plain
	// generated model/texture, as opposed to the real item's own definition,
	// which selects between a custom special renderer/broken-window state
	// that only our own client code can evaluate.
	private static final Identifier FALLBACK_MODEL = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window_icon");

	public static void register() {
		// Makes our own textures/models (including FALLBACK_MODEL above)
		// available in Polymer's generated resource pack, so non-modded
		// clients actually have something to render for the fallback model
		// rather than a missing-texture placeholder.
		if(FabricLoader.getInstance().isModLoaded("polymer-resource-pack")) {
			ResourcePackHook.addAssets();
		}

		PolymerItem.registerOverlay(WindowItem.WINDOW, new WindowItemPolymerOverlay());
	}

	// Isolated in its own class so merely loading PolymerCompat doesn't
	// force-load PolymerResourcePackUtils (from the separate
	// polymer-resource-pack module) -- see the isModLoaded guard at the
	// call site in WaylandCraftCommon for why this pattern matters.
	private static class ResourcePackHook {

		private static void addAssets() {
			eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.addModAssets(WaylandCraftCommon.MOD_ID);
		}

	}

	// PolymerClientDecoded tells Polymer this item has a "companion client
	// side mod" that decodes it itself -- without it, Polymer appears to
	// virtualize/re-encode this item's network representation for *every*
	// client, including real WaylandCraft clients that never installed
	// Polymer, which broke item-stack decoding entirely (see project memory:
	// polymer-datacomponent-registration). shouldDecodePolymer() defaults to
	// true, which is what we want since getPolymerItem/modifyBasePolymerItemStack
	// above already return the real server-side item for players who have
	// WaylandCraft installed.
	private static class WindowItemPolymerOverlay implements PolymerItem, PolymerClientDecoded {

		@Override
		public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
			if(hasWaylandCraft(context)) return WindowItem.WINDOW;
			return Items.PAPER;
		}

		@Override
		public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
			if(hasWaylandCraft(context)) return PolymerItem.super.getPolymerItemModel(stack, context, lookup);
			return FALLBACK_MODEL;
		}

		@Override
		public void modifyBasePolymerItemStack(ItemStack out, ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
			// Real WaylandCraft clients need the window handle data to
			// identify which toplevel the item refers to; other clients
			// don't know what it means and don't need it either.
			if(!hasWaylandCraft(context)) WindowHandle.strip(out);
		}

		// Detects whether the connecting player has WaylandCraft installed.
		// Backed by WaylandCraftPresence, which checks channel registration
		// during the CONFIGURATION phase rather than with a live
		// ServerPlayNetworking#canSend() check here -- a PLAY-phase check
		// raced the server's very first PLAY packets (like the initial
		// inventory sync), incorrectly reporting players as not having
		// WaylandCraft installed. See project memory:
		// container-set-content-decode-crash.
		private static boolean hasWaylandCraft(PacketContext context) {
			ServerPlayer player = PolymerCommonUtils.getPlayer(context);
			return player != null && WaylandCraftPresence.has(player.getUUID());
		}

	}

}
