package dev.evvie.waylandcraft.network;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Tells a real WaylandCraft client the server's authoritative raw item id
 * for WindowItem.WINDOW, sent during CONFIGURATION (see
 * WaylandCraftRegistrySync) to clients detected via WaylandCraftPresence.
 *
 * Needed because WINDOW is registered with Polymer as a "server entry" (see
 * PolymerCompat), which fabric-registry-sync-v0's own sync packet always
 * omits -- so client and server each independently compute WINDOW's
 * tail-of-registry raw id at mod init via Polymer's own
 * registry-sync-manipulator mixin, and those two computations only agree
 * when both sides have registered the exact same total set of items
 * beforehand, which doesn't hold for a client with any extra
 * item-registering mods the server doesn't have.
 */
public record ClientboundWindowRawIdPayload(int rawId) implements CustomPacketPayload {

	public static final Identifier WINDOW_RAW_ID_PAYLOAD_ID = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window_raw_id");

	public static final CustomPacketPayload.Type<ClientboundWindowRawIdPayload> TYPE = new CustomPacketPayload.Type<ClientboundWindowRawIdPayload>(WINDOW_RAW_ID_PAYLOAD_ID);

	public static final StreamCodec<FriendlyByteBuf, ClientboundWindowRawIdPayload> CODEC = ByteBufCodecs.VAR_INT.map(ClientboundWindowRawIdPayload::new, ClientboundWindowRawIdPayload::rawId);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

}
