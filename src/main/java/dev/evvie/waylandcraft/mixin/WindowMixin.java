package dev.evvie.waylandcraft.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.mojang.blaze3d.platform.Window;

@Mixin(Window.class)
public class WindowMixin {
	
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = GLFW.GLFW_NATIVE_CONTEXT_API))
	public int changeContextApi(int originalVal) {
		return GLFW.GLFW_EGL_CONTEXT_API;
	}
	
}
