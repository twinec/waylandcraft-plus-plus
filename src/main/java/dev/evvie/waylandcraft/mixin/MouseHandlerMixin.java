package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraft.PointerCaptureOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	
	@Inject(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V"), cancellable = true)
	public void onButton(long windowHandle, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
		if(WaylandCraft.instance.onButtonPress(windowHandle, buttonInfo.button(), action, buttonInfo.modifiers())) info.cancel();
	}
	
	// Target updated for the Minecraft.getOverlay() -> Minecraft.gui.overlay()
	// move (see WaylandCraft.java's same-shaped fix in 6a8262c): vanilla's
	// onButton/onScroll can no longer call a method that doesn't exist on
	// Minecraft, so the actual INVOKE site must now live on Gui. Best-effort
	// guess at the owner class/descriptor -- not verified against a real
	// decompile or an in-game launch, since neither is possible in this
	// sandbox. If mixin application fails at launch, this is the first
	// place to check.
	@Inject(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0))
	public void onButtonMaybeOverlay(long windowHandle, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
		if(Minecraft.getInstance().gui.overlay() instanceof PointerCaptureOverlay && WaylandCraft.instance.pointerCapture != null) {
			WaylandCraft.instance.onButtonPress(windowHandle, buttonInfo.button(), action, buttonInfo.modifiers());
		}
	}
	
	@Inject(method = "onScroll", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;", ordinal = 1), cancellable = true)
	public void onScroll(long windowHandle, double scrollX, double scrollY, CallbackInfo info) {
		if(WaylandCraft.instance.onScroll(windowHandle, scrollX, scrollY)) info.cancel();
	}
	
	// Same unverified best-effort target update as onButtonMaybeOverlay above.
	@Inject(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0))
	public void onScrollMaybeOverlay(long windowHandle, double scrollX, double scrollY, CallbackInfo info) {
		if(Minecraft.getInstance().gui.overlay() instanceof PointerCaptureOverlay && WaylandCraft.instance.pointerCapture != null) {
			WaylandCraft.instance.onScroll(windowHandle, scrollX, scrollY);
		}
	}
	
	@WrapOperation(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setAll()V"))
	private void stopSetAllKeys(Operation<Void> original) {
		if(!WaylandCraft.PointerCaptureOverlay.STOP_KEYMAPPING_SET_ALL.isBound()) original.call();
	}
	
	@Shadow public double accumulatedDX;
	@Shadow public double accumulatedDY;
	
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	public void onTurnPlayer(double timeDelta, CallbackInfo info) {
		if(WaylandCraft.instance.onMouseTurn(accumulatedDX, accumulatedDY)) info.cancel();
	}
	
}
