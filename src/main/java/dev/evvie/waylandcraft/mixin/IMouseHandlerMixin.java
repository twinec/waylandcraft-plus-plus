package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.MouseHandler;

@Mixin(MouseHandler.class)
public interface IMouseHandlerMixin {
	
	@Invoker
	public void invokeOnMove(long l, double d, double e, double relX, double relY);
	
}
