package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.InputConstants;

import dev.evvie.waylandcraft.WaylandCraft;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	
	@Inject(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 0), cancellable = true)
	public void onPressInGame(long windowHandle, int action, KeyEvent event, CallbackInfo info) {
		int scancode = WaylandCraft.correctScancode(event.key());

		if(Minecraft.getInstance().level == null) return;
		if(Minecraft.getInstance().gui.screen() != null) return;
		
		if(WaylandCraft.instance.onKeyPress(windowHandle, event.key(), scancode, action, event.modifiers())) info.cancel();
	}
	
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = false)
	public void onPressGlobal(long windowHandle, int action, KeyEvent event, CallbackInfo info) {
		if(WaylandCraft.instance.bridge == null) return;
		
		int scancode = WaylandCraft.correctScancode(event.key());
		if(action != InputConstants.PRESS && action != InputConstants.RELEASE) return;

		WaylandCraft.instance.bridge.internalKeyUpdate(scancode, action == InputConstants.PRESS);
	}
	
}
