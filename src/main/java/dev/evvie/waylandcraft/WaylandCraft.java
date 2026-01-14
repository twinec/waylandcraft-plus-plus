package dev.evvie.waylandcraft;

import java.awt.Color;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.evvie.waylandcraft.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.XDGDesktopManager.IconData;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ModInitializer, ClientModInitializer {
	public static final String MOD_ID = "waylandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String KEYBIND_CATEGORY = "key.categories." + MOD_ID;
	
	public static WaylandCraft instance;
	
	public WaylandCraftBridge bridge = null;
	public ArrayList<WindowDisplay> displays = new ArrayList<WindowDisplay>();
	public DisplayHitResult hitResult = null;
	
	public WLCToplevel keyboardCapture = null;
	public WLCSurface pointerGrab = null;
	
	public WindowDisplay grabbedDisplay = null;
	
	public WLCToplevel stickyToplevel = null;
	public float stickyToplevelScale = 0.0f;
	
	public XDGDesktopManager xdgManager = new XDGDesktopManager();
	
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
			
			for(WLCPopup popup : bridge.getPopups()) {
				WLCAbstractWindow root = popup;
				while((root = ((WLCPopup) root).getParent()) instanceof WLCPopup);
				
				WLCToplevel toplevel = (WLCToplevel) root;
				boolean toplevelHasWindow = hasDisplayFor(toplevel);
				boolean popupHasWindow = hasDisplayFor(popup);
				if(toplevelHasWindow && !popupHasWindow) {
					displays.add(new WindowDisplay(popup));
				}
				else if(!toplevelHasWindow && popupHasWindow) {
					displays.removeIf((w) -> w.window == popup);
				}
			}
			
			for(WLCPopup popup : bridge.getPopups()) {
				anchorToParent(popup);
			}
			
			displays.removeIf((w) -> !w.isAlive());
			
			// Hide all windows that were minimized and unset minimize requested state
			displays.removeIf((w) -> w.window instanceof WLCToplevel && ((WLCToplevel) w.window).minimizeRequest);
			Stream.of(bridge.getToplevels()).forEach((t) -> t.minimizeRequest = false);
			
			// Handle any maximize or unmaximize requests
			for(WLCToplevel toplevel : bridge.getToplevels()) {
				if(toplevel.maximizeRequest && toplevel.unmaximizeRequest) {
					// Both requests shouldn't happen at the same time
					toplevel.restoreGeometry = null;
				}
				else if(toplevel.maximizeRequest) {
					// Maximize toplevel and store its old geometry
					toplevel.restoreGeometry = toplevel.geometry;
					bridge.maximizeToplevel(toplevel);
				}
				else if(toplevel.unmaximizeRequest) {
					// Unmaximize toplevel and attempt to restore old geometry
					SurfaceGeometry newGeometry = toplevel.restoreGeometry;
					if(newGeometry == null) newGeometry = toplevel.geometry;
					
					// resizeToplevel also unsets the maximize flag
					bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
					toplevel.restoreGeometry = null;
				}
				
				toplevel.maximizeRequest = toplevel.unmaximizeRequest = false;
			}
			
			// Handle any fullscreen or unfullscreen requests
			for(WLCToplevel toplevel : bridge.getToplevels()) {
				if(toplevel.fullscreenRequest && toplevel.unfullscreenRequest) {
					// Both requests shouldn't happen at the same time
					toplevel.restoreGeometry = null;
				}
				else if(toplevel.fullscreenRequest) {
					// Fullscreen toplevel and store its old geometry
					toplevel.restoreGeometry = toplevel.geometry;
					bridge.fullscreenToplevel(toplevel);
				}
				else if(toplevel.unfullscreenRequest) {
					// Unfullscreen toplevel and attempt to restore old geometry
					SurfaceGeometry newGeometry = toplevel.restoreGeometry;
					if(newGeometry == null) newGeometry = toplevel.geometry;
					
					// resizeToplevel also unsets the fullscreen flag
					bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
					toplevel.restoreGeometry = null;
				}
				
				toplevel.fullscreenRequest = toplevel.unfullscreenRequest = false;
			}
			
			if(grabbedDisplay != null && !grabbedDisplay.isAlive()) grabbedDisplay = null;
			if(grabbedDisplay != null) anchorToCamera(grabbedDisplay, context.camera());
			
			// Make sure the toplevels are focused in their respective order and being refocused when a toplevel disappears
			if(!(Minecraft.getInstance().screen instanceof WindowManagerScreen)) {
				WLCToplevel focus = bridge.getMostToLeastRecentFocus()
						.filter((t) -> hasDisplayFor(t))
						.findFirst()
						.orElse(null);
				
				bridge.focusSurface(focus);
				
				if(keyboardCapture != focus) {
					keyboardCapture = null;
				}
			}
			
			RenderSystem.enableDepthTest();
			displays.forEach((w) -> w.render(context));
			
			sendMotionEvents();
		});
		
		ClientTickEvents.END_CLIENT_TICK.register((mc) -> {
			if(keyOpenScreen.consumeClick()) {
				mc.setScreen(new WindowManagerScreen(WaylandCraft.instance));
			}
		});
		
		HudRenderCallback.EVENT.register((context, delta) -> {
			if(Minecraft.getInstance().options.hideGui) return;
			
			Font font = Minecraft.getInstance().font;
			int yoff = 30;
			int ystep = font.lineHeight + 2;
			
			if(WaylandCraft.instance.keyboardCapture != null) {
				String text = "KEYBOARD CAPTURED [PRESS F7]";
				context.drawString(font, text, context.guiWidth() - font.width(text) - 10, yoff, Color.red.getRGB(), true);
				yoff += ystep;
			}
			
			for(WLCToplevel toplevel : WaylandCraft.instance.bridge.getToplevels()) {
				String appID = toplevel.appID;
				
				String name = "<unknown app>";
				if(appID != null) {
					name = appID;
					
					String xdgName = xdgManager.getName(appID);
					if(xdgName != null) name = xdgName;
				}
				
				Style style = Style.EMPTY;
				Color color = Color.white;
				
				if(!hasDisplayFor(toplevel)) {
					color = Color.lightGray;
				}
				if(toplevel == bridge.getMostRecentFocus()) {
					style = style.applyFormat(ChatFormatting.UNDERLINE);
				}
				
				int x = context.guiWidth() - font.width(name) - 10;
				context.drawString(font, Component.literal(name).withStyle(style), x, yoff, color.getRGB(), true);
				
				if(appID != null) {
					IconData icon = xdgManager.getIcon(appID);
					int iconX = x - font.lineHeight - 2;
					int iconY = yoff;
					int iconW = font.lineHeight;
					int iconH = font.lineHeight;
					if(icon != null) RenderUtils.blitGUI(context, icon.texture.id, iconX, iconY, iconX + iconW, iconY + iconH);
				}
				
				yoff += ystep;
			}
			
			if(stickyToplevel != null && !stickyToplevel.isAlive()) stickyToplevel = null;
			if(stickyToplevel != null) {
				WindowFramebuffer buf = stickyToplevel.framebuffer;
				SurfaceGeometry geometry = stickyToplevel.geometry;
				
				int size = 200;
				stickyToplevelScale = size / (float) Math.max(geometry.width(), geometry.height());
				float x = 0 + (-buf.getXOff() - geometry.x()) * stickyToplevelScale;
				float y = 0 + (-buf.getYOff() - geometry.y()) * stickyToplevelScale;
				
				RenderUtils.blitGUI(context, buf.getTexture(), x, y, buf.getWidth() * stickyToplevelScale, buf.getHeight() * stickyToplevelScale);
			}
		});
		
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			RenderUtils.registerShaders(context);
		});
	}
	
	public WindowDisplay getOrCreateDisplay(WLCToplevel toplevel) {
		WindowDisplay window = displays.stream().filter((w) -> w.window == toplevel).findAny().orElse(null);
		if(window != null) return window;
		
		window = new WindowDisplay(toplevel);
		displays.add(window);
		
		return window;
	}
	
	public boolean hasDisplayFor(WLCAbstractWindow window) {
		return displays.stream().anyMatch((w) -> w.window == window);
	}
	
	/* Handle mouse button input
	 * Returns true when the mouse button action has been consumed
	 */
	public boolean onButtonPress(long windowHandle, int button, int action, int modifiers) {
		if(grabbedDisplay != null) {
			grabbedDisplay = null;
			return true;
		}
		if(hitResult == null) return false;
		
		WindowDisplay display = hitResult.target;
		if(!display.isAlive()) {
			hitResult = null;
			return false;
		}
		
		KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), false);
		
		// Check if on the backside of the window
		if(hitResult.dist < 0) return true;
		
		// 0x110 is linux BTN_LEFT, see linux/input-event-codes.h
		bridge.sendButton(0x110 + button, action);
		
		if(action == GLFW.GLFW_PRESS && display.window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) display.window);
		
		return true;
	}
	
	/* Handle mouse being turned in game
	 * Returns true when the mouse move has been consumed
	 */
	public boolean onMouseTurn(double dx, double dy) {
		if(pointerGrab == null) return false;
		
		bridge.sendRelativeMotion(dx, dy);
		LocalPlayer player = Minecraft.getInstance().player;
		
		WindowDisplay display = displays.stream().filter((w) -> w.window == keyboardCapture).findAny().orElse(null);
		if(display != null) {
			Vec3 eye = player.getEyePosition(Minecraft.getInstance().getFrameTime());
			Vec3 diff = display.pivot.subtract(eye);
			
			float yaw = Mth.wrapDegrees((float)(Mth.atan2(diff.z, diff.x) * (double)(180F / (float)Math.PI)) - 90.0F);
			float pitch = Mth.wrapDegrees((float)(-(Mth.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (double)(180F / (float)Math.PI))));
			
			player.setYRot(yaw);
			player.setXRot(pitch);
		}
		
		return true;
	}
	
	/* Handle mouse scroll input
	 * Returns true when the mouse scroll action has been consumed
	 */
	public boolean onScroll(long windowHandle, double scrollX, double scrollY) {
		if(grabbedDisplay != null) return true;
		if(hitResult == null) return false;
		
		WindowDisplay window = hitResult.target;
		if(!window.isAlive()) {
			hitResult = null;
			return false;
		}
		
		// Check if on the backside of the window
		if(hitResult.dist < 0) return true;
		
		// Multiplication by -10 is the inverse transformation from what GLFW does on wayland
		bridge.sendScroll(0, -scrollY * 10);
		bridge.sendScroll(1, -scrollX * 10);
		
		if(window.window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window.window);
		
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
		
		if(keyboardCapture == null) return false;
		
		/* This code just completely naively assumes that the scancode received by GLFW
		 * is also the correct matching Wayland scancode for the default XKBConfig.
		 * For X11 and Wayland hosts, this is a huge hack but should mostly work for now
		 */
		if(action == GLFW.GLFW_PRESS) {
//			LOGGER.info("PRESSED KEY: " + scancode);
			bridge.pressKey(scancode);
		}
		else if(action == GLFW.GLFW_RELEASE) {
//			LOGGER.info("RELEASED KEY: " + scancode);
			bridge.releaseKey(scancode);
		}
		
		return true;
	}
	
	private void handleCaptureKey(int action) {
		if(action != GLFW.GLFW_PRESS) {
			return;
		}
		
		WLCToplevel focused = bridge.getMostRecentFocus();
		if(focused == null || !hasDisplayFor(focused)) {
			keyboardCapture = null;
			return;
		}
		
		if(keyboardCapture == null) keyboardCapture = focused;
		else keyboardCapture = null;
	}
	
	private void anchorToParent(WLCPopup popup) {
		WindowDisplay window = displays.stream().filter((w) -> w.window == popup).findAny().orElse(null);
		WindowDisplay parent = displays.stream().filter((w) -> w.window == popup.getParent()).findAny().orElse(null);
		
		if(window == null || parent == null) return;
		
		// If the parent is also a popup, first make it anchor itself
		if(parent.window instanceof WLCPopup) {
			anchorToParent((WLCPopup) parent.window);
		}
		
		window.rotate(parent.normal(), parent.down());
		
		int x = popup.offsetX - popup.geometry.x() + parent.window.geometry.x();
		int y = popup.offsetY - popup.geometry.y() + parent.window.geometry.y();
		
		window.moveOrigin(parent.localToWorld(x, y, 0.01));
	}
	
	private void anchorToCamera(WindowDisplay display, Camera camera) {
		Vec3 look = new Vec3(camera.getLookVector());
		Vec3 up = new Vec3(camera.getUpVector());
		display.pivot = camera.getPosition().add(look.scale(2));
		display.rotate(look.reverse(), up.reverse());
	}
	
	private void checkPointerGrab(WLCSurface surface) {
		if(grabbedDisplay != null || keyboardCapture == null || !inSurfaceTreeOf(keyboardCapture, surface) || Minecraft.getInstance().screen != null) {
			pointerGrab = null;
			bridge.unlockPointer();
			return;
		}
		
		// Try to (re-)lock surface
		if(bridge.maybeLockPointer(surface)) {
			pointerGrab = surface;
		}
		else {
			pointerGrab = null;
		}
	}
	
	private boolean inSurfaceTreeOf(WLCAbstractWindow window, WLCSurface surface) {
		for(WLCSurface s = window.getSurfaceTree(); s != null; s = s.getNextChild()) {
			if(s == surface) return true;
		}
		return false;
	}
	
	private boolean inScreen = false;
	
	private void sendMotionEvents() {
		if(pointerGrab != null) checkPointerGrab(pointerGrab);
		if(pointerGrab != null) return;
		
		boolean inScreenNow = Minecraft.getInstance().screen != null;
		
		// Send pointer moved outside window when a screen is opened
		if(inScreenNow && !inScreen) bridge.sendMotionOutside();
		inScreen = inScreenNow;
		
		// Don't send hitResult-based pointer updates when inside a screen
		if(inScreen) {
			return;
		}
		
		if(grabbedDisplay != null) {
			bridge.sendMotionOutside();
			return;
		}
		
		if(hitResult != null) {
			Vec3 coords = hitResult.surfaceLocal;
			WindowDisplay w = hitResult.target;
			
			if(!w.isAlive()) {
				hitResult = null;
				bridge.sendMotionOutside();
				return;
			}
			
			if(hitResult.dist < 0) {
				bridge.sendMotionOutside();
				return;
			}
			
			for(WLCSurface surface = w.window.getSurfaceTreeLast(); surface != null; surface = surface.getPrevChild()) {
				Vec3 rel = coords.subtract(surface.xSubpos, surface.ySubpos, 0);
				
				int width = surface.width();
				int height = surface.height();
				
				if(rel.x < 0 || rel.y < 0 || rel.x > width || rel.y > height) {
					continue;
				}
				
				if(bridge.inputRegionContains(surface, rel.x, rel.y)) {
					bridge.sendMotion(surface, rel.x, rel.y);
					checkPointerGrab(surface);
					return;
				}
			}
		}
		
		bridge.sendMotionOutside();
	}
}