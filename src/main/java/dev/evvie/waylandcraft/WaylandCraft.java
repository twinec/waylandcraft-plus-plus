package dev.evvie.waylandcraft;

import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ModInitializer, ClientModInitializer {
	public static final String MOD_ID = "waylandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	private WaylandCraftBridge bridge = null;
	
	@Override
	public void onInitialize() {
	}
	
	private static final float PIXEL_SCALE = 1.0f / 500;
	
	private Vec2 getSurfaceDimensions(WLCSurface surface) {
		BufferTexture buf = surface.getBuffer();
		if(buf == null) return new Vec2(0, 0);
		float width = buf.width;
		float height = buf.height;
		return new Vec2(width * PIXEL_SCALE, height * PIXEL_SCALE);
	}
	
	private void renderToplevelAt(WorldRenderContext ctx, WLCToplevel toplevel, Vec3 pos) {
		int depth = 0;
		GL33.glEnable(GL33.GL_POLYGON_OFFSET_FILL);
		GL33.glDisable(GL33.GL_CULL_FACE);
		for(WLCSurface surface = toplevel.getSurfaceTree(); surface != null; surface = surface.getNextChild()) {
			Vec3 rpos = pos.add(surface.xSubpos * PIXEL_SCALE, -surface.ySubpos * PIXEL_SCALE, 0);
			GL33.glPolygonOffset(-depth, 0);
			renderSurfaceAt(ctx, surface, rpos);
			depth++;
		}
		GL33.glDisable(GL33.GL_POLYGON_OFFSET_FILL);
		GL33.glEnable(GL33.GL_CULL_FACE);
	}
	
	private void renderSurfaceAt(WorldRenderContext ctx, WLCSurface surface, Vec3 pos) {
		BufferTexture buf = surface.getBuffer();
		if(buf == null) return;
		
		Vec2 size = getSurfaceDimensions(surface);
		RenderUtils.drawTexturedQuad(ctx.camera(), buf.getId(),
				pos, pos.add(0, -size.y, 0), pos.add(size.x, -size.y, 0), pos.add(size.x, 0, 0),
				new Vec2(0, 0), new Vec2(0, 1), new Vec2(1, 1), new Vec2(1, 0));
	}
	
	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing WaylandCraft");
		
		WorldRenderEvents.END.register(context -> {
			if(bridge == null) {
				bridge = WaylandCraftBridge.start();
				String socket = bridge.getSocket();
				Minecraft.getInstance().gui.getChat().addMessage(Component.literal("Server started on " + socket));
			}
			bridge.update();
			
			RenderSystem.enableDepthTest();
			Vec3 pos = new Vec3(-250, 65, -500);
			WLCToplevel[] toplevels = bridge.getToplevels();
			for(WLCToplevel toplevel : toplevels) {
				Vec2 size = getSurfaceDimensions(toplevel.getSurfaceTree());
				renderToplevelAt(context, toplevel, pos.add(0, size.y, 0));
				pos = pos.add(size.x, 0, 0);
			}
		});
	}
}