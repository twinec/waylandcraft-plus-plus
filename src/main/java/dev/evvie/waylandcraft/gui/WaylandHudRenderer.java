package dev.evvie.waylandcraft.gui;

import java.awt.Color;

import org.lwjgl.opengl.GL33;

import dev.evvie.waylandcraft.RenderUtils;
import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraft.KeyboardCaptureMode;
import dev.evvie.waylandcraft.WindowFramebuffer;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

public class WaylandHudRenderer {
	
	private WaylandCraft wlc;
	
	public WaylandHudRenderer(WaylandCraft wlc) {
		this.wlc = wlc;
	}
	
	public void render(GuiGraphics context, float delta) {
		if(Minecraft.getInstance().options.hideGui) return;
		
		Font font = Minecraft.getInstance().font;
		int yoff = 30;
		int ystep = font.lineHeight + 2;
		
		if(WaylandCraft.instance.keyboardCaptureMode == KeyboardCaptureMode.CAPTURE) {
			String text = "KEYBOARD CAPTURED [PRESS ESCAPE]";
			context.drawString(font, text, context.guiWidth() - font.width(text) - 10, yoff, Color.red.getRGB(), true);
			yoff += ystep;
		}
		else if(WaylandCraft.instance.keyboardCaptureMode == KeyboardCaptureMode.HARD_CAPTURE) {
			String text = "KEYBOARD CAPTURED [PRESS SUPER+ESCAPE]";
			context.drawString(font, text, context.guiWidth() - font.width(text) - 10, yoff, Color.red.getRGB(), true);
			yoff += ystep;
		}
		
		for(WLCToplevel toplevel : WaylandCraft.instance.bridge.getToplevels()) {
			String appID = toplevel.appID;
			
			String name = "<unknown app>";
			if(appID != null) {
				name = appID;
				
				String xdgName = wlc.xdgManager.getName(appID);
				if(xdgName != null) name = xdgName;
			}
			
			Style style = Style.EMPTY;
			Color color = Color.white;
			
			if(!wlc.hasDisplayFor(toplevel)) {
				color = Color.lightGray;
			}
			if(toplevel == wlc.bridge.getMostRecentFocus()) {
				style = style.applyFormat(ChatFormatting.UNDERLINE);
			}
			
			int x = context.guiWidth() - font.width(name) - 10;
			context.drawString(font, Component.literal(name).withStyle(style), x, yoff, color.getRGB(), true);
			
			if(appID != null) {
				ResourceLocation icon = wlc.xdgManager.getIcon(appID);
				int iconX = x - font.lineHeight - 2;
				int iconY = yoff;
				int iconW = font.lineHeight;
				int iconH = font.lineHeight;
				GL33.glEnable(GL33.GL_BLEND);
				if(icon != null) RenderUtils.blitGUI(context, icon, iconX, iconY, iconX + iconW, iconY + iconH);
				GL33.glDisable(GL33.GL_BLEND);
			}
			
			yoff += ystep;
		}
		
		if(wlc.pinnedToplevel != null && !wlc.pinnedToplevel.isAlive()) wlc.pinnedToplevel = null;
		if(wlc.pinnedToplevel != null) {
			WindowFramebuffer buf = wlc.pinnedToplevel.framebuffer;
			SurfaceGeometry geometry = wlc.pinnedToplevel.geometry;
			
			int guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
			float x = -buf.getXOff() - geometry.x();
			float y = -buf.getYOff() - geometry.y();
			float w = buf.getWidth();
			float h = buf.getHeight();
			
			x /= guiScale * 2;
			y /= guiScale * 2;
			w /= guiScale * 2;
			h /= guiScale * 2;
			
			GL33.glEnable(GL33.GL_BLEND);
			RenderUtils.blitGUI(context, buf.getTexture(), x, y, w, h);
			GL33.glDisable(GL33.GL_BLEND);
		}
	}
	
}
