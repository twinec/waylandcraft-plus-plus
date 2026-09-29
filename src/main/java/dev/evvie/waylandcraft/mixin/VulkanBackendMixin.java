package dev.evvie.waylandcraft.mixin;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.vulkan.VulkanHelper;

@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {

	@ModifyVariable(
			method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At("HEAD"),
			argsOnly = true
	)
	private static FeatureSet checkMoreVulkanExtensions(FeatureSet featureSet, VulkanPhysicalDevice physicalDevice) {
		boolean hasNecessaryExtensions = true;
		for(String extension : VulkanHelper.NECESSARY_VULKAN_EXTENSIONS) {
			if(!physicalDevice.hasDeviceExtension(extension)) {
				WaylandCraftCommon.LOGGER.error("Vulkan physical device is missing necessary extension " + extension + "!");
				hasNecessaryExtensions = false;
				break;
			}
		}

		if(!hasNecessaryExtensions) return featureSet;

		Set<String> extraExtensions = new HashSet<>();
		for(String extension : VulkanHelper.NECESSARY_VULKAN_EXTENSIONS) {
			extraExtensions.add(extension);
			System.out.println("ADDED EXTENSION " + extension);
		}

		FeatureSet dmabufFeatures = new FeatureSet("waylandcraft_dmabuf", extraExtensions, Set.of());
		return featureSet.composite(dmabufFeatures);
	}

}
