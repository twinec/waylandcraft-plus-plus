package dev.evvie.waylandcraft.render;

import java.util.Optional;

import org.joml.Vector4fc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public class WindowTranslucencyHotfix {
	
	private static final RenderPipeline TRANSLUCENCY_HOTFIX_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/translucency_hotfix"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "core/singlecolor"))
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALPHA))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withShaderDefine("RED", 1.0f)
			.withShaderDefine("GREEN", 1.0f)
			.withShaderDefine("BLUE", 1.0f)
			.withShaderDefine("ALPHA", 1.0f)
			.build()
	);
	
	public static void render() {
		if(Minecraft.getInstance().level == null) return;
		
		Optional<Vector4fc> clearColor = Optional.empty();
		try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "translucency_hotfix", Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(), clearColor)) {
			pass.setPipeline(TRANSLUCENCY_HOTFIX_PIPELINE);
			pass.draw(3, 1, 0, 0);
		}
	}
	
}
