package dev.evvie.waylandcraft.mixin.polymer;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.network.ClientboundHelloPayload;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * WINDOW is registered as an ordinary synced+modded item (see PolymerCompat,
 * PolymerSyncedObject.setPlainSyncedObject) so that real WaylandCraft
 * clients get it reconciled by fabric-registry-sync-v0's normal, safe raw-id
 * remap -- the same well-tested path every other mod's items go through.
 * That's what makes it required, though: a client with Fabric API but
 * without WaylandCraft would normally get kicked on join as "missing" the
 * mod (see commit a65492f).
 *
 * Rather than exempting WINDOW from the registry-sync handshake for
 * everyone (which is what broke real clients' raw id -- see project memory:
 * polymer-registry-sync-optional-fix), this drops WINDOW from the id map
 * only for the one connection that doesn't need it: one that hasn't
 * declared support for WaylandCraft's own networking channel. A real client
 * never hits this branch, so it still gets WINDOW's id reconciled normally.
 */
@Pseudo
@Mixin(value = RegistrySyncManager.class, remap = false)
public class RegistrySyncManagerMixin {

	@WrapOperation(method = "configureClient", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/registry/sync/RegistrySyncManager;createAndPopulateRegistryMap()Ljava/util/Map;"), require = 0)
	private static Map<Identifier, Object2IntMap<Identifier>> waylandcraft$dropWindowForOtherClients(Operation<Map<Identifier, Object2IntMap<Identifier>>> original, @Local ServerConfigurationPacketListenerImpl handler) {
		Map<Identifier, Object2IntMap<Identifier>> map = original.call();

		if (map != null && !ServerConfigurationNetworking.canSend(handler, ClientboundHelloPayload.TYPE)) {
			Object2IntMap<Identifier> itemMap = map.get(BuiltInRegistries.ITEM.key().identifier());

			if (itemMap != null) {
				itemMap.removeInt(BuiltInRegistries.ITEM.getKey(WindowItem.WINDOW));
			}
		}

		return map;
	}

}
