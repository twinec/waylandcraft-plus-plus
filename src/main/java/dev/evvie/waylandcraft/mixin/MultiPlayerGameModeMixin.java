package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.item.WindowItem;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Drives WindowItem's use()-equivalent behaviour (starting the
 * "hold to view" interaction) for window items given by the companion
 * Paper plugin (paper-plugin/) instead of a real Fabric server. Those
 * items are a plain vanilla base item carrying fallback NBT (see
 * WindowHandle#from) rather than a real registered WindowItem,
 * so WindowItem's own Item#use()/#onUseTick() overrides never fire for
 * them at all -- there's no real WindowItem class involved. This mixin
 * covers that gap by hooking client-side item-use dispatch directly,
 * independent of which Item class the held stack actually is.
 *
 * Method name/signature not verified against this exact MC version's
 * compiled MultiPlayerGameMode -- if this fails to compile, check the
 * actual useItem() signature first (same bytecode-archaeology approach
 * used for KeyboardHandlerMixin's injection point earlier this project).
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack item = player.getItemInHand(hand);

		// Real WindowItem already handles itself via Item#use/#onUseTick.
		if(item.is(WindowItem.WINDOW)) return;

		if(WaylandCraft.getToplevel(item) == null) return;

		player.startUsingItem(hand);
		cir.setReturnValue(InteractionResult.CONSUME);
	}

}
