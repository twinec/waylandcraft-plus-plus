package dev.evvie.waylandcraft.mixin;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

@Mixin(VulkanCommandEncoder.class)
public interface IVulkanCommandEncoderMixin {

	@Invoker("commandBuffer")
	VkCommandBuffer invokeCommandBuffer();

	@Invoker("memoryBarrier")
	void invokeMemoryBarrier(MemoryStack stack);

}
