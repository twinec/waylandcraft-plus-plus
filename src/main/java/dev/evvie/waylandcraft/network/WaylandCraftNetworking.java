package dev.evvie.waylandcraft.network;

import java.util.ArrayList;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.utils.IMyServerPlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.item.Item;

public class WaylandCraftNetworking {

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(ServerboundGiveItemsPayload.TYPE, ServerboundGiveItemsPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ServerboundAliveWindowsPayload.TYPE, ServerboundAliveWindowsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ClientboundHelloPayload.TYPE, ClientboundHelloPayload.CODEC);
		PayloadTypeRegistry.clientboundConfiguration().register(ClientboundWindowRawIdPayload.TYPE, ClientboundWindowRawIdPayload.CODEC);

		WaylandCraftPresence.register();

		// Tells a real WaylandCraft client the server's authoritative raw id
		// for WINDOW -- read lazily here (at connection time, rather than at
		// mod-init) so it's guaranteed to run after Polymer's own
		// registry-sync-manipulator mixin has reordered the item to its
		// tail-of-registry slot (see ClientboundWindowRawIdPayload for why
		// this is needed at all). Checks canSend() directly instead of going
		// through WaylandCraftPresence's PRESENT set, since that set is only
		// populated by a separate listener on the same CONFIGURE event, and
		// relying on it would depend on event listener registration order.
		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			if(ServerConfigurationNetworking.canSend(handler, ClientboundHelloPayload.TYPE)) {
				ServerConfigurationNetworking.send(handler, new ClientboundWindowRawIdPayload(Item.getId(WindowItem.WINDOW)));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ServerboundAliveWindowsPayload.TYPE, (payload, ctx) -> {
			IMyServerPlayer plr = (IMyServerPlayer) ctx.player();
			ArrayList<Long> handles = plr.getAliveWindows();
			handles.clear();
			
			for(long handle : payload.handles()) {
				handles.add(handle);
			}
		});
		
		ServerPlayNetworking.registerGlobalReceiver(ServerboundGiveItemsPayload.TYPE, WaylandCraftCommon.instance.serverItemManager::handleGiveItemsPayload);
	}
	
}
