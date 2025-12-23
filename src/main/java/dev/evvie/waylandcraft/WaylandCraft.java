package dev.evvie.waylandcraft;

import java.awt.Color;
import java.util.ArrayList;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.evvie.waylandcraft.Window.WindowHitResult;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ModInitializer, ClientModInitializer {
	public static final String MOD_ID = "waylandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String KEYBIND_CATEGORY = "key.categories." + MOD_ID;
	
	public static WaylandCraft instance;
	
	public WaylandCraftBridge bridge = null;
	public ArrayList<Window> windows = new ArrayList<Window>();
	public WindowHitResult hitResult = null;
	public boolean keyboardCaptured = false;
	
	public KeyMapping keyOpenScreen;
	
	@Override
	public void onInitialize() {
	}
	
	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing WaylandCraft");
		
		instance = this;
		
		keyOpenScreen = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.windowManager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEYBIND_CATEGORY));
		
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if(bridge == null) {
				bridge = WaylandCraftBridge.start();
				String socket = bridge.getSocket();
				Minecraft.getInstance().gui.getChat().addMessage(Component.literal("Server started on " + socket));
			}
			bridge.update();
			
			for(WLCToplevel toplevel : bridge.getToplevels()) {
				if(!windows.stream().anyMatch((w) -> w.backing == toplevel)) {
					windows.add(new Window(toplevel));
				}
			}
			for(WLCPopup popup : bridge.getPopups()) {
				if(!windows.stream().anyMatch((w) -> w.backing == popup)) {
					windows.add(new Window(popup));
				}
			}
			windows.removeIf((w) -> !w.isAlive());
			
			for(WLCPopup popup : bridge.getPopups()) {
				anchorToParent(popup);
			}
			
			RenderSystem.enableDepthTest();
			windows.forEach((w) -> w.render(context));
			
			sendMotionEvents();
		});
		
		ClientTickEvents.END_CLIENT_TICK.register((mc) -> {
			if(keyOpenScreen.consumeClick()) {
				mc.setScreen(new WindowManagerScreen(WaylandCraft.instance));
			}
		});
		
		HudRenderCallback.EVENT.register((context, delta) -> {
			if(Minecraft.getInstance().options.hideGui) return;
			
			if(WaylandCraft.instance.keyboardCaptured) {
				String text = "KEYBOARD CAPTURED [PRESS F7]";
				Font font = Minecraft.getInstance().font;
				context.drawString(Minecraft.getInstance().font, text, context.guiWidth() - font.width(text) - 10, 10, Color.red.getRGB(), false);
			}
		});
		
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			RenderUtils.registerShaders(context);
		});
	}
	
	/* Handle mouse button input
	 * Returns true when the mouse button action has been consumed
	 */
	public boolean onButtonPress(long windowHandle, int button, int action, int modifiers) {
		if(hitResult == null) return false;
		
		Window window = hitResult.target;
		if(!window.isAlive()) {
			hitResult = null;
			return false;
		}
		
		KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), false);
		
		// Check if on the backside of the window
		if(hitResult.dist < 0) return true;
		
		// 0x110 is linux BTN_LEFT, see linux/input-event-codes.h
		bridge.sendButton(0x110 + button, action);
		
		return true;
	}
	
	/* Handle mouse scroll input
	 * Returns true when the mouse scroll action has been consumed
	 */
	public boolean onScroll(long windowHandle, double scrollX, double scrollY) {
		if(hitResult == null) return false;
		
		Window window = hitResult.target;
		if(!window.isAlive()) {
			hitResult = null;
			return false;
		}
		
		// Check if on the backside of the window
		if(hitResult.dist < 0) return true;
		
		// Multiplication by -10 is the inverse transformation from what GLFW does on wayland
		bridge.sendScroll(0, -scrollY * 10);
		bridge.sendScroll(1, -scrollX * 10);
		
		return true;
	}
	
	/* Handle keyboard input
	 * Returns true when the key press action has been consumed
	 */
	public boolean onKeyPress(long windowHandle, int key, int scancode, int action, int modifiers) {
		if(key == GLFW.GLFW_KEY_F7) {
			handleCaptureKey(action);
			return true;
		}
		
		if(!keyboardCaptured) return false;
		
		/* This code just completely naively assumes that the scancode received by GLFW
		 * is also the correct matching Wayland scancode for the default XKBConfig.
		 * For X11 and Wayland hosts, this is a huge hack but should mostly work for now
		 */
		if(action == GLFW.GLFW_PRESS) {
			LOGGER.info("PRESSED KEY: " + scancode);
			bridge.pressKey(scancode);
		}
		else if(action == GLFW.GLFW_RELEASE) {
			LOGGER.info("RELEASED KEY: " + scancode);
			bridge.releaseKey(scancode);
		}
		
		return true;
	}
	
	private void handleCaptureKey(int action) {
		if(action != GLFW.GLFW_PRESS) {
			return;
		}
		
		if(hitResult == null || hitResult.dist < 0) {
			bridge.focusSurface(null);
			keyboardCaptured = false;
			return;
		}
		
		WLCSurface surface = hitResult.target.backing.getSurfaceTree();
		if(!surface.isAlive()) return;
		
		bridge.focusSurface(surface);
		keyboardCaptured = !keyboardCaptured;
	}
	
	private void anchorToParent(WLCPopup popup) {
		Window window = windows.stream().filter((w) -> w.backing == popup).findAny().orElse(null);
		Window parent = windows.stream().filter((w) -> w.backing == popup.getParent()).findAny().orElse(null);
		
		if(window == null || parent == null) return;
		
		// If the parent is also a popup, first make it anchor itself
		if(parent.backing instanceof WLCPopup) {
			anchorToParent((WLCPopup) parent.backing);
		}
		
		window.rotate(parent.normal(), parent.down());
		window.moveOrigin(parent.localToWorld(popup.offsetX, popup.offsetY, 0.05));
	}
	
	private boolean inScreen = false;
	
	private void sendMotionEvents() {
		boolean inScreenNow = Minecraft.getInstance().screen != null;
		
		// Send pointer moved outside window when a screen is opened
		if(inScreenNow && !inScreen) bridge.sendMotionOutside();
		inScreen = inScreenNow;
		
		// Don't send hitResult-based pointer updates when inside a screen
		if(inScreen) return;
		
		if(hitResult != null) {
			Vec3 coords = hitResult.surfaceLocal;
			Window w = hitResult.target;
			
			if(!w.isAlive()) {
				hitResult = null;
				bridge.sendMotionOutside();
				return;
			}
			
			if(hitResult.dist < 0) {
				bridge.sendMotionOutside();
				return;
			}
			
			for(WLCSurface surface = w.backing.getSurfaceTreeLast(); surface != null; surface = surface.getPrevChild()) {
				Vec3 rel = coords.subtract(surface.xSubpos, surface.ySubpos, 0);
				
				int width = surface.width();
				int height = surface.height();
				
				if(rel.x < 0 || rel.y < 0 || rel.x > width || rel.y > height) {
					continue;
				}
				
				if(bridge.inputRegionContains(surface, rel.x, rel.y)) {
					bridge.sendMotion(surface, rel.x, rel.y);
					return;
				}
			}
		}
		
		bridge.sendMotionOutside();
	}
}