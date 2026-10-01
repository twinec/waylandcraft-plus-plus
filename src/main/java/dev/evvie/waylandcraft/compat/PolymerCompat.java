package dev.evvie.waylandcraft.compat;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerClientDecoded;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.item.WindowHandle;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.network.WaylandCraftPresence;

/**
 * Registers WaylandCraft's items as Polymer overlays, using
 * {@link PolymerItem#registerOverlay} rather than implementing PolymerItem
 * directly on the item classes -- this keeps WindowItem itself free of any
 * compile-time dependency on Polymer, even though polymer-core is a hard
 * dependency of the mod as a whole (see fabric.mod.json).
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
		// Covers a genuinely-vanilla client (no Fabric API at all): that
		// case is never reached by registerOverlay's own exemption below
		// (fabric-registry-sync-v0 only consults RegistryAttribute.OPTIONAL
		// when the client can't speak its payload at all), so it still
		// needs to be marked separately.
		RegistryAttributeHolder.get(BuiltInRegistries.ITEM).addAttribute(RegistryAttribute.OPTIONAL);

		// Makes our own textures/models (including FALLBACK_MODEL above)
		// available in Polymer's generated resource pack, so non-modded
		// clients actually have something to render for the fallback model
		// rather than a missing-texture placeholder.
		if(FabricLoader.getInstance().isModLoaded("polymer-resource-pack")) {
			ResourcePackHook.addAssets();
		}

		// PolymerItem.registerOverlay(...), not PolymerSyncedObject.setPlainSyncedObject(...)
		// -- registerOverlay also calls RegistrySyncUtils.setServerEntry(),
		// which (via Polymer's own registry-sync-manipulator mixin) drops
		// WINDOW from fabric-registry-sync-v0's required-entries list
		// unconditionally, for every connecting client -- this is what
		// actually stops a vanilla/non-WaylandCraft client from being kicked
		// as "missing" the mod; RegistryAttribute.OPTIONAL above never
		// applied to this case at all.
		//
		// The same mixin also reorders WINDOW to the tail of the item
		// registry's raw ids at freeze time, on both sides -- which is only
		// safe because polymer-core is now a hard dependency (see
		// fabric.mod.json and WaylandCraftCommon#onInitialize): a real
		// WaylandCraft client runs the same mixin on its own registry, so
		// it independently reorders WINDOW to the same raw id the server
		// does, with no networking involved. Without Polymer guaranteed
		// client-side, that reorder is invisible to a real client and
		// decoding throws "No value with id N" -- see project memory:
		// container-set-content-decode-crash.
		PolymerItem.registerOverlay(WindowItem.WINDOW, new WindowItemPolymerOverlay());
	}

	// Isolated in its own class so merely loading PolymerCompat doesn't
	// force-load PolymerResourcePackUtils (from the separate, still-optional
	// polymer-resource-pack module) -- unlike polymer-core, nothing requires
	// that submodule to be present.
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

		// Not modifyBasePolymerItemStack -- Polymer's own createItemStack()
		// unconditionally overwrites CUSTOM_DATA *after* modifyBasePolymerItemStack
		// runs (it stuffs its own "$polymer:stack" wrapper in there for every
		// client, virtualized or not), so anything WindowHandle-related set
		// during modifyBasePolymerItemStack gets clobbered before the packet
		// is even built. Overriding getPolymerItemStack instead lets us
		// re-stamp the handle onto the final stack *after* Polymer is done
		// with it. WindowHandle.writeTo() merges into CUSTOM_DATA under its
		// own nested compound key rather than replacing the component, so it
		// coexists with Polymer's own "$polymer:stack" data there. Real
		// clients need this to identify which toplevel the item refers to;
		// other clients don't know what it means and don't need it either.
		// See project memory: container-set-content-decode-crash.
		@Override
		public ItemStack getPolymerItemStack(ItemStack itemStack, TooltipFlag tooltipType, PacketContext context, HolderLookup.Provider lookup) {
			ItemStack out = PolymerItem.super.getPolymerItemStack(itemStack, tooltipType, context, lookup);
			if(hasWaylandCraft(context)) {
				WindowHandle handle = WindowHandle.from(itemStack);
				if(handle != null) handle.writeTo(out);
			}
			return out;
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
