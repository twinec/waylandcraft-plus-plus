package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;

@Mixin(FrontendGpuDevice.class)
public interface IGpuDeviceMixin {

	@Accessor("backend")
	GpuDeviceBackend getBackend();

}
