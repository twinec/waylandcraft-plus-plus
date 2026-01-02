package dev.evvie.waylandcraft.gui;

import java.awt.Color;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public abstract class SelectorWidget<T> extends AbstractWidget {
	
	private SelectorButton<T>[] buttons;
	private int count = 0;
	
	// Currently selected element, should always be either null or an element assigned to a button
	private T selected = null;
	
	@SuppressWarnings("unchecked")
	public SelectorWidget(int x, int y, int buttonWidth, int buttonHeight, int maxCount) {
		super(x, y, buttonWidth * maxCount, buttonHeight, Component.empty());
		
		if(maxCount < 1) throw new IllegalArgumentException("SelectorWidget maxCount < 1");
		
		buttons = new SelectorButton[maxCount];
		for(int i = 0; i < buttons.length; i++) {
			buttons[i] = new SelectorButton<T>(this, x + buttonWidth * i, y, buttonWidth, buttonHeight, i);
		}
	}
	
	public void setEntries(T[] entries) {
		count = Math.min(entries.length, buttons.length);
		
		for(int i = 0; i < count; i++) {
			buttons[i].element = entries[i];
		}
		
		if(entries.length == 0) {
			buttons[0].element = null;
			count = 1;
		}
		
		selectionCheck();
	}
	
	public abstract Component titleForElement(T element);
	public abstract boolean elementDimColor(T element);
	
	public T selection() {
		return selected;
	}
	
	// Maintains selected element property
	private void selectionCheck() {
		if(!Stream.of(buttons).anyMatch((b) -> b.element == selected)) {
			selected = null;
		}
	}
	
	public void select(T element) {
		this.selected = element;
		selectionCheck();
	}
	
	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		for(int i = 0; i < buttons.length; i++) {
			SelectorButton<T> b = buttons[i];
			
			b.selected = b.element == selected;
			b.visible = i < count;
			
			if(b.element != null) {
				b.setMessage(titleForElement(b.element));
				b.dimColor = elementDimColor(b.element);
			}
			else {
				b.setMessage(Component.empty());
				b.dimColor = false;
			}
			
			b.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}
	
	@Override
	public boolean mouseClicked(double x, double y, int mouseButton) {
		if(!(this.active && this.visible)) return false;
		
		for(SelectorButton<T> b : buttons) {
			if(b.mouseClicked(x, y, mouseButton)) return true;
		}
		
		return false;
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	}
	
	private static class SelectorButton<T> extends Button {
		
		public T element = null;
		public boolean selected = false;
		public boolean dimColor = false;
		
		@SuppressWarnings("unchecked")
		public SelectorButton(SelectorWidget<T> widget, int x, int y, int width, int height, int idx) {
			super(x, y, width, height, Component.empty(), (b) -> {widget.select(((SelectorButton<T>) b).element);}, (c) -> c.get());
		}
		
		private static final WidgetSprites SPRITES = new WidgetSprites(
				new ResourceLocation("widget/button"),
				new ResourceLocation("widget/button_disabled"),
				new ResourceLocation("widget/button_highlighted")
		);
		
		@Override
		protected void renderWidget(GuiGraphics context, int i, int j, float f) {
			int x = getX();
			int y = getY();
			int width = getWidth();
			int height = getHeight();
			
			Color color = dimColor ? Color.lightGray : Color.white;
			Font font = Minecraft.getInstance().font;
			
			context.blitSprite(SPRITES.get(active, selected), x, y, width, height);
			context.enableScissor(x + 2, y, x + width - 2, y + height);
			context.drawString(font, getMessage(), x + 2, y + height / 2 - font.lineHeight / 2, color.getRGB());
			context.disableScissor();
		}
		
	}
	
}
