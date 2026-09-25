package dev.evvie.waylandcraft.network;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import dev.evvie.waylandcraft.WaylandCraftCommon;

/**
 * Tracks, per player, whether their client registered support for
 * WaylandCraft's networking channel -- checked once during the
 * CONFIGURATION phase (which always finishes before PLAY begins), instead
 * of with a live ServerPlayNetworking.canSend() check during PLAY. A
 * PLAY-phase check races the very first PLAY packets the server sends
 * (like the player's initial inventory sync via container_set_content),
 * since the client's channel-registration handshake and those packets
 * aren't ordered relative to each other. See project memory:
 * container-set-content-decode-crash.
 */
public class WaylandCraftPresence {

	private static final Set<UUID> PRESENT = ConcurrentHashMap.newKeySet();

	public static void register() {
		PayloadTypeRegistry.clientboundConfiguration().register(ClientboundHelloPayload.TYPE, ClientboundHelloPayload.CODEC);

		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			boolean canSend = ServerConfigurationNetworking.canSend(handler, ClientboundHelloPayload.TYPE);
			WaylandCraftCommon.LOGGER.info("[WaylandCraftPresence] CONFIGURE for {}: canSend(hello)={}", handler.getOwner().name(), canSend);
			if(canSend) {
				PRESENT.add(handler.getOwner().id());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PRESENT.remove(handler.getPlayer().getUUID());
		});
	}

	public static boolean has(UUID playerId) {
		boolean present = PRESENT.contains(playerId);
		WaylandCraftCommon.LOGGER.info("[WaylandCraftPresence] has({})={}", playerId, present);
		return present;
	}

}
