package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.renderpearl.backend.opengl.FrameBufferCache;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public interface IGlDeviceMixin {

	@Invoker("frameBufferCache")
	FrameBufferCache invokeFrameBufferCache();

}
