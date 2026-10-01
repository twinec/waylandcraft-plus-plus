package dev.evvie.waylandcraft.mixin.polymer;

import java.util.ArrayList;
import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.network.WaylandCraftPresence;
import eu.pb4.polymer.core.impl.networking.PolymerServerProtocol;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Polymer's polymer:sync/items broadcast tells every Polymer-aware client
 * about WINDOW (registered as a Polymer "server entry", see PolymerCompat)
 * using the server's own raw item id -- PolymerItemEntry#representation is
 * encoded via vanilla's ItemStack.OPTIONAL_STREAM_CODEC, which identifies the
 * item by raw registry id, not by identifier. That raw id is only guaranteed
 * to match a connecting client's own id for WINDOW when both sides register
 * the exact same set of server-entry items in the exact same relative order
 * at registry freeze (see polymer-reg-sync-manipulator's MappedRegistryMixin)
 * -- never guaranteed for an arbitrary real WaylandCraft client, e.g. one
 * that also has Trinkets installed (its own Polymer compat registers server
 * entries too, shifting the tail ordering). A mismatch makes the client
 * misdecode the embedded ItemStack and throw a DecoderException, killing the
 * connection on join.
 *
 * A real WaylandCraft client already has the actual WINDOW item registered
 * and never needed Polymer's virtualized copy of it to begin with (that's
 * the whole point of PolymerClientDecoded on WindowItemPolymerOverlay) -- so
 * the fix is to just not send it that entry at all, for that connection.
 *
 * See project memory: polymer-registry-sync-optional-fix.
 */
@Mixin(value = PolymerServerProtocol.class, remap = false)
public class PolymerServerProtocolMixin {

	@WrapOperation(method = "sendSyncPackets", at = @At(value = "INVOKE", target = "Leu/pb4/polymer/core/impl/networking/PolymerServerProtocol;getServerSideEntries(Lnet/minecraft/core/IdMap;)Ljava/util/Collection;", ordinal = 0))
	private static Collection<?> waylandcraft$skipWindowForRealClients(IdMap<?> registry, Operation<Collection<?>> original, @Local ServerGamePacketListenerImpl handler) {
		Collection<?> entries = original.call(registry);

		if (registry == BuiltInRegistries.ITEM && WaylandCraftPresence.has(handler.getPlayer().getUUID())) {
			entries = new ArrayList<>(entries);
			entries.remove(WindowItem.WINDOW);
		}

		return entries;
	}

}
