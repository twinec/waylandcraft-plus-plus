package dev.evvie.waylandcraft;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.function.Supplier;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class RenderUtils {
	
	private static ShaderInstance CUTOUT_NO_COLOR;
	
	protected static void registerShaders(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
		context.register(new ResourceLocation(WaylandCraft.MOD_ID, "cutout_no_color"), DefaultVertexFormat.POSITION_TEX, shader -> {
			CUTOUT_NO_COLOR = shader;
		});
	}
	
	public static ShaderInstance getCutoutNoColor() {
		return CUTOUT_NO_COLOR;
	}
	
	public static void blitGUIUnscaled(GuiGraphics graphics, int tex, float x1, float y1, float x2, float y2) {
		float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
		x1 /= guiScale;
		y1 /= guiScale;
		x2 /= guiScale;
		y2 /= guiScale;
		
		blitGUI(graphics, tex, x1, y1, x2, y2, 0, 0, 1, 1);
	}
	
	public static void blitGUI(GuiGraphics graphics, int tex, float x1, float y1, float x2, float y2) {
		blitGUI(graphics, tex, x1, y1, x2, y2, 0, 0, 1, 1);
	}
	
	public static void blitGUI(GuiGraphics graphics, int tex, float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2) {
		RenderSystem.setShaderTexture(0, tex);
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		Matrix4f matrix4f = graphics.pose().last().pose();
		BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
		bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bufferBuilder.vertex(matrix4f, x1, y1, 0).uv(u1, v1).endVertex();
		bufferBuilder.vertex(matrix4f, x1, y2, 0).uv(u1, v2).endVertex();
		bufferBuilder.vertex(matrix4f, x2, y2, 0).uv(u2, v2).endVertex();
		bufferBuilder.vertex(matrix4f, x2, y1, 0).uv(u2, v1).endVertex();
		BufferUploader.drawWithShader(bufferBuilder.end());
	}
	
	public static void blitGUI(GuiGraphics graphics, ResourceLocation tex, float x1, float y1, float x2, float y2) {
		blitGUI(graphics, tex, x1, y1, x2, y2, 0, 0, 1, 1);
	}
	
	public static void blitGUI(GuiGraphics graphics, ResourceLocation tex, float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2) {
		RenderSystem.setShaderTexture(0, tex);
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		Matrix4f matrix4f = graphics.pose().last().pose();
		BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
		bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bufferBuilder.vertex(matrix4f, x1, y1, 0).uv(u1, v1).endVertex();
		bufferBuilder.vertex(matrix4f, x1, y2, 0).uv(u1, v2).endVertex();
		bufferBuilder.vertex(matrix4f, x2, y2, 0).uv(u2, v2).endVertex();
		bufferBuilder.vertex(matrix4f, x2, y1, 0).uv(u2, v1).endVertex();
		BufferUploader.drawWithShader(bufferBuilder.end());
	}
	
	public static Matrix4f cameraTransform(Camera camera) {
		PoseStack matrixStack = new PoseStack();
//		matrixStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
//		matrixStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
		matrixStack.translate(-camera.getPosition().x, -camera.getPosition().y, -camera.getPosition().z);
		
		return matrixStack.last().pose();
	}
	
	public static void drawQuad(Camera camera, OptionalInt texture, Supplier<ShaderInstance> shader, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4, Vec3 color, float alpha) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.getBuilder();
		Matrix4f positionMatrix = cameraTransform(camera);

		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
		buffer.vertex(positionMatrix, (float) p1.x, (float) p1.y, (float) p1.z).color((float) color.x, (float) color.y, (float) color.z, alpha).uv(uv1.x, uv1.y).endVertex();
		buffer.vertex(positionMatrix, (float) p2.x, (float) p2.y, (float) p2.z).color((float) color.x, (float) color.y, (float) color.z, alpha).uv(uv2.x, uv2.y).endVertex();
		buffer.vertex(positionMatrix, (float) p3.x, (float) p3.y, (float) p3.z).color((float) color.x, (float) color.y, (float) color.z, alpha).uv(uv3.x, uv3.y).endVertex();
		buffer.vertex(positionMatrix, (float) p4.x, (float) p4.y, (float) p4.z).color((float) color.x, (float) color.y, (float) color.z, alpha).uv(uv4.x, uv4.y).endVertex();

		RenderSystem.setShader(shader);
		if(texture.isPresent()) {
			RenderSystem.setShaderTexture(0, texture.getAsInt());
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		tesselator.end();
	}
	
	public static void drawQuadPosTex(Camera camera, OptionalInt texture, Supplier<ShaderInstance> shader, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.getBuilder();
		Matrix4f positionMatrix = cameraTransform(camera);
		
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		buffer.vertex(positionMatrix, (float) p1.x, (float) p1.y, (float) p1.z).uv(uv1.x, uv1.y).endVertex();
		buffer.vertex(positionMatrix, (float) p2.x, (float) p2.y, (float) p2.z).uv(uv2.x, uv2.y).endVertex();
		buffer.vertex(positionMatrix, (float) p3.x, (float) p3.y, (float) p3.z).uv(uv3.x, uv3.y).endVertex();
		buffer.vertex(positionMatrix, (float) p4.x, (float) p4.y, (float) p4.z).uv(uv4.x, uv4.y).endVertex();
		
		RenderSystem.setShader(shader);
		if(texture.isPresent()) {
			RenderSystem.setShaderTexture(0, texture.getAsInt());
		}
		tesselator.end();
	}
	
	public static void drawQuad(Camera camera, ResourceLocation res, Supplier<ShaderInstance> shader, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4, Vec3 color, float alpha) {
		TextureManager textureManager = Minecraft.getInstance().getTextureManager();
		AbstractTexture tex = textureManager.getTexture(res);
		drawQuad(camera, OptionalInt.of(tex.getId()), shader, p1, p2, p3, p4, uv1, uv2, uv3, uv4, color, alpha);
	}
	
	public static void drawTexturedQuad(Camera camera, ResourceLocation res, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4) {
		drawQuad(camera, res, GameRenderer::getPositionColorTexShader, p1, p2, p3, p4, uv1, uv2, uv3, uv4, new Vec3(1.0, 1.0, 1.0), 1.0f);
	}
	
	public static void drawTexturedQuad(Camera camera, int tex, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4) {
		drawQuad(camera, OptionalInt.of(tex), GameRenderer::getPositionColorTexShader, p1, p2, p3, p4, uv1, uv2, uv3, uv4, new Vec3(1.0, 1.0, 1.0), 1.0f);
	}
	
	public static void drawCutoutColorlessQuad(Camera camera, int tex, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec2 uv1, Vec2 uv2, Vec2 uv3, Vec2 uv4) {
		drawQuadPosTex(camera, OptionalInt.of(tex), RenderUtils::getCutoutNoColor, p1, p2, p3, p4, uv1, uv2, uv3, uv4);
	}
	
	public static void drawSolidQuad(Camera camera, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, float r, float g, float b) {
		drawQuad(camera, OptionalInt.empty(), GameRenderer::getPositionColorShader, p1, p2, p3, p4, Vec2.ZERO, Vec2.ZERO, Vec2.ZERO, Vec2.ZERO, new Vec3(r, g, b), 1.0f);
	}
	
	public static void drawLine(Camera camera, double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b) {
		drawLine(camera, new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), r, g, b);
	}
	
	public static void drawLine(Camera camera, Vec3 p1, Vec3 p2, float r, float g, float b) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.getBuilder();
		Matrix4f positionMatrix = cameraTransform(camera);

		buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
		buffer.vertex(positionMatrix, (float) p1.x, (float) p1.y, (float) p1.z).color(r, g, b, 1f).endVertex();
		buffer.vertex(positionMatrix, (float) p2.x, (float) p2.y, (float) p2.z).color(r, g, b, 1f).endVertex();

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		tesselator.end();
	}
	
	public static void drawTracer(Camera camera, Vec3 p, float r, float g, float b) {
		Vec3 t = camera.getPosition();
		Vec3 l = new Vec3(camera.getLookVector()).scale(0.1);
		t = t.add(l);
		
		RenderSystem.depthFunc(GL11.GL_ALWAYS);
		drawLine(camera, p, t, r, g, b);
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
	}
	
	public static void drawBlockOutline(Camera camera, BlockPos pos, double d, float r, float g, float b) {
		Vec3 p = new Vec3(pos.getX(), pos.getY(), pos.getZ());
		drawWireCube(camera, p, p.add(1, 1, 1), d, r, g, b);
	}
	
	public static void drawBlockOutline(Camera camera, BlockPos pos, float r, float g, float b) {
		drawBlockOutline(camera, pos, 0.02, r, g, b);
	}
	
	public static void drawMarker(Camera camera, Vec3 pos, double size, float r, float g, float b) {
		drawWireCube(camera, pos, pos, size, r, g, b);
	}
	
	public static void drawWireCube(Camera camera, Vec3 v1, Vec3 v2, double d, float r, float g, float b) {
		Vec3 v1d = v1.subtract(d, d, d);
		Vec3 v2d = v2.add(d, d, d);
		
		// bottom lines
		drawLine(camera, v1d.x, v1d.y, v1d.z, v2d.x, v1d.y, v1d.z, r, g, b);
		drawLine(camera, v1d.x, v1d.y, v1d.z, v1d.x, v1d.y, v2d.z, r, g, b);
		drawLine(camera, v1d.x, v1d.y, v2d.z, v2d.x, v1d.y, v2d.z, r, g, b);
		drawLine(camera, v2d.x, v1d.y, v1d.z, v2d.x, v1d.y, v2d.z, r, g, b);
		
		// top lines
		drawLine(camera, v1d.x, v2d.y, v1d.z, v2d.x, v2d.y, v1d.z, r, g, b);
		drawLine(camera, v1d.x, v2d.y, v1d.z, v1d.x, v2d.y, v2d.z, r, g, b);
		drawLine(camera, v1d.x, v2d.y, v2d.z, v2d.x, v2d.y, v2d.z, r, g, b);
		drawLine(camera, v2d.x, v2d.y, v1d.z, v2d.x, v2d.y, v2d.z, r, g, b);
		
		// connecting lines
		drawLine(camera, v1d.x, v1d.y, v1d.z, v1d.x, v2d.y, v1d.z, r, g, b);
		drawLine(camera, v2d.x, v1d.y, v1d.z, v2d.x, v2d.y, v1d.z, r, g, b);
		drawLine(camera, v1d.x, v1d.y, v2d.z, v1d.x, v2d.y, v2d.z, r, g, b);
		drawLine(camera, v2d.x, v1d.y, v2d.z, v2d.x, v2d.y, v2d.z, r, g, b);
	}
	
	public static void drawBlockOverlay(Camera camera, BlockPos pos, float r, float g, float b) {
		Vec3 p = new Vec3(pos.getX(), pos.getY(), pos.getZ());
		
		final double d = 0.02;
		final double l = 0.0f - d;
		final double h = 1.0f + d;
		
		drawSolidQuad(camera, p.add(l, l, l), p.add(l, h, l), p.add(h, h, l), p.add(h, l, l), r, g, b); // back (ZN)
		drawSolidQuad(camera, p.add(l, l, h), p.add(h, l, h), p.add(h, h, h), p.add(l, h, h), r, g, b); // front (ZP)
		drawSolidQuad(camera, p.add(l, l, l), p.add(l, l, h), p.add(l, h, h), p.add(l, h, l), r, g, b); // left (XN)
		drawSolidQuad(camera, p.add(h, l, l), p.add(h, h, l), p.add(h, h, h), p.add(h, l, h), r, g, b); // right (XP)
		drawSolidQuad(camera, p.add(l, h, l), p.add(l, h, h), p.add(h, h, h), p.add(h, h, l), r, g, b); // top (YP)
		drawSolidQuad(camera, p.add(l, l, l), p.add(h, l, l), p.add(h, l, h), p.add(l, l, h), r, g, b); // bottom (YN)
	}
	
	public static void drawBlockTexOverlay(Camera camera, ResourceLocation res, BlockPos pos) {
		Vec3 p = new Vec3(pos.getX(), pos.getY(), pos.getZ());
		
		final double d = 0.02;
		final double l = 0.0f - d;
		final double h = 1.0f + d;
		
		drawTexturedQuad(camera, res, p.add(l, l, l), p.add(l, h, l), p.add(h, h, l), p.add(h, l, l),
				new Vec2(1, 1), new Vec2(1, 0), new Vec2(0, 0), new Vec2(0, 1)); // back (ZN)
		drawTexturedQuad(camera, res, p.add(l, l, h), p.add(h, l, h), p.add(h, h, h), p.add(l, h, h),
				new Vec2(0, 1), new Vec2(1, 1), new Vec2(1, 0), new Vec2(0, 0)); // front (ZP)
		drawTexturedQuad(camera, res, p.add(l, l, l), p.add(l, l, h), p.add(l, h, h), p.add(l, h, l),
				new Vec2(0, 1), new Vec2(1, 1), new Vec2(1, 0), new Vec2(0, 0)); // left (XN)
		drawTexturedQuad(camera, res, p.add(h, l, l), p.add(h, h, l), p.add(h, h, h), p.add(h, l, h),
				new Vec2(1, 1), new Vec2(1, 0), new Vec2(0, 0), new Vec2(0, 1)); // right (XP)
		drawTexturedQuad(camera, res, p.add(l, h, l), p.add(l, h, h), p.add(h, h, h), p.add(h, h, l),
				new Vec2(0, 0), new Vec2(0, 1), new Vec2(1, 1), new Vec2(1, 0)); // top (YP)
		drawTexturedQuad(camera, res, p.add(l, l, l), p.add(h, l, l), p.add(h, l, h), p.add(l, l, h),
				new Vec2(0, 1), new Vec2(1, 1), new Vec2(1, 0), new Vec2(0, 0)); // bottom (YN)
	}
}
