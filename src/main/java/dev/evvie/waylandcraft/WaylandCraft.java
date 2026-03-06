package dev.evvie.waylandcraft;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.evvie.waylandcraft.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import dev.evvie.waylandcraft.item.WindowItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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
	
	public WLCToplevel pinnedToplevel = null;
	
	public List<WLCToplevel> newToplevels = new ArrayList<WLCToplevel>();
	
	public XDGDesktopManager xdgManager = new XDGDesktopManager();
	
	public KeyMapping keyOpenScreen;
	
	public WindowInHandRenderer windowInHandRenderer = new WindowInHandRenderer();
	
	@Override
	public void onInitialize() {
		WindowItem.register();
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
					getOrCreateDisplay(popup);
				}
				else if(!toplevelHasWindow && popupHasWindow) {
					displays.removeIf((w) -> w.window == popup);
				}
			}
			
			displays.removeIf((d) -> !d.isValid());
			displays.forEach((d) -> d.updateGeometry());
			
			for(WLCPopup popup : bridge.getPopups()) {
				anchorToParent(popup);
			}
			
			// Hide all windows that were minimized and unset minimize requested state
			displays.removeIf((w) -> w.window instanceof WLCToplevel && ((WLCToplevel) w.window).requests.minimize);
			Stream.of(bridge.getToplevels()).forEach((t) -> t.requests.minimize = false);
			
			// Handle any maximize or unmaximize requests
			for(WLCToplevel toplevel : bridge.getToplevels()) {
				if(toplevel.requests.maximize && toplevel.requests.unmaximize) {
					// Both requests shouldn't happen at the same time
					toplevel.restoreGeometry = null;
				}
				else if(toplevel.requests.maximize) {
					// Maximize toplevel and store its old geometry
					toplevel.restoreGeometry = toplevel.geometry;
					bridge.maximizeToplevel(toplevel);
				}
				else if(toplevel.requests.unmaximize) {
					// Unmaximize toplevel and attempt to restore old geometry
					SurfaceGeometry newGeometry = toplevel.restoreGeometry;
					if(newGeometry == null) newGeometry = toplevel.geometry;
					
					// resizeToplevel also unsets the maximize flag
					bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
					toplevel.restoreGeometry = null;
				}
				
				toplevel.requests.maximize = toplevel.requests.unmaximize = false;
			}
			
			// Handle any fullscreen or unfullscreen requests
			for(WLCToplevel toplevel : bridge.getToplevels()) {
				if(toplevel.requests.fullscreen && toplevel.requests.unfullscreen) {
					// Both requests shouldn't happen at the same time
					toplevel.restoreGeometry = null;
				}
				else if(toplevel.requests.fullscreen) {
					// Fullscreen toplevel and store its old geometry
					toplevel.restoreGeometry = toplevel.geometry;
					bridge.fullscreenToplevel(toplevel);
				}
				else if(toplevel.requests.unfullscreen) {
					// Unfullscreen toplevel and attempt to restore old geometry
					SurfaceGeometry newGeometry = toplevel.restoreGeometry;
					if(newGeometry == null) newGeometry = toplevel.geometry;
					
					// resizeToplevel also unsets the fullscreen flag
					bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
					toplevel.restoreGeometry = null;
				}
				
				toplevel.requests.fullscreen = toplevel.requests.unfullscreen = false;
			}
			
			newToplevels.addAll(Arrays.asList(bridge.getNewToplevels()));
			
			if(grabbedDisplay != null && !grabbedDisplay.isValid()) grabbedDisplay = null;
			if(grabbedDisplay != null) anchorToCamera(grabbedDisplay, context.camera());
			
			boolean inWMScreen = Minecraft.getInstance().screen instanceof WindowManagerScreen;
			
			// Make sure the toplevels are focused in their respective order and being refocused when a toplevel disappears
			if(!inWMScreen) {
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
			updateOutputSize(inWMScreen);
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
					ResourceLocation icon = xdgManager.getIcon(appID);
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
			
			if(pinnedToplevel != null && !pinnedToplevel.isAlive()) pinnedToplevel = null;
			if(pinnedToplevel != null) {
				WindowFramebuffer buf = pinnedToplevel.framebuffer;
				SurfaceGeometry geometry = pinnedToplevel.geometry;
				
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
		});
		
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			RenderUtils.registerShaders(context);
		});
		
		ServerTickEvents.START_WORLD_TICK.register(level -> {
			if(bridge == null) return;
			
			level.players().forEach(player -> {
				newToplevels.forEach(toplevel -> {
					ItemStack item = WindowItem.createItem(toplevel);
					player.addItem(item);
				});
				
				Inventory inv = player.getInventory();
				for(int i = 0; i < inv.getContainerSize(); i++) {
					ItemStack item = inv.getItem(i);
					
					if(!item.is(WindowItem.WINDOW)) continue;
					if(WindowItem.getToplevel(item) != null) continue;
					
					inv.setItem(i, ItemStack.EMPTY);
				}
			});
			newToplevels.clear();
			
			StreamSupport.stream(level.getAllEntities().spliterator(), false)
					.filter((e) -> e instanceof ItemEntity)
					.map((e) -> (ItemEntity) e)
					.filter((e) -> e.getItem().is(WindowItem.WINDOW))
					.filter((e) -> WindowItem.getToplevel(e.getItem()) == null)
					.filter((e) -> e.getAge() > 10)
					.forEach((e) -> {
						for(int i = 0; i < 10; i++) {
							double dx = ((level.random.nextDouble() * 2) - 1) * 0.15;
							double dy = level.random.nextDouble() * 0.2;
							double dz = ((level.random.nextDouble() * 2) - 1) * 0.15;
							Minecraft.getInstance().level.addParticle(ParticleTypes.FLAME, e.getX(), e.getY(), e.getZ(), dx, dy, dz);
						}
						e.discard();
					});
		});
	}
	
	private void updateOutputSize(boolean inWMScreen) {
		int outputWidth = Minecraft.getInstance().getWindow().getWidth();
		int outputHeight = Minecraft.getInstance().getWindow().getHeight();
		
		Size size = bridge.getOutputSize();
		if(size.width() != outputWidth || size.height() != outputHeight) {
			bridge.resizeOutput(outputWidth, outputHeight);
			if(!inWMScreen) bridge.setOutputBounds(outputWidth, outputHeight);
		}
	}
	
	public WindowDisplay getOrCreateDisplay(WLCAbstractWindow window) {
		WindowDisplay display = displays.stream().filter((w) -> w.window == window).findAny().orElse(null);
		if(display != null) return display;
		
		display = new WindowDisplay(window);
		displays.add(display);
		
		return display;
	}
	
	public boolean hasDisplayFor(WLCAbstractWindow window) {
		return displays.stream().anyMatch((w) -> w.window == window);
	}
	
	private ArrayList<Integer> pressedButtons = new ArrayList<Integer>();
	
	public void releaseHeldButtons() {
		for(int button : pressedButtons) {
			bridge.sendButton(0x110 + button, 0);
		}
		pressedButtons.clear();
	}
	
	public void pressButton(int button) {
		if(pressedButtons.contains(button)) return;
		
		// 0x110 is linux BTN_LEFT, see linux/input-event-codes.h
		bridge.sendButton(0x110 + button, 1);
		pressedButtons.add(button);
	}
	
	public void releaseButton(int button) {
		if(!pressedButtons.contains(button)) return;
		
		// 0x110 is linux BTN_LEFT, see linux/input-event-codes.h
		bridge.sendButton(0x110 + button, 0);
		pressedButtons.removeIf((b) -> b == button);
	}
	
	/* Handle mouse button input
	 * Returns true when the mouse button action has been consumed
	 */
	public boolean onButtonPress(long windowHandle, int button, int action, int modifiers) {
		if(grabbedDisplay != null) {
			grabbedDisplay = null;
			return true;
		}
		
		if(action == GLFW.GLFW_RELEASE && pressedButtons.contains(button)) {
			bridge.sendButton(0x110 + button, 0);
			pressedButtons.removeIf((b) -> b == button);
			return true;
		}
		
		if(hitResult == null) return false;
		
		WindowDisplay display = hitResult.target;
		if(!display.isValid()) {
			hitResult = null;
			return false;
		}
		
		KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), false);
		
		// Check if on the backside of the window
		if(hitResult.dist < 0) return true;
		
		if(action == GLFW.GLFW_PRESS) {
			if(display.window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) display.window);
			
			pressButton(button);
			return true;
		}
		else {
			releaseButton(button);
			return true;
		}
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
		if(!window.isValid()) {
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
		if(inScreenNow && !inScreen) {
			releaseHeldButtons();
			bridge.sendMotionOutside();
		}
		inScreen = inScreenNow;
		
		// Don't send hitResult-based pointer updates when inside a screen
		if(inScreen) {
			return;
		}
		
		if(grabbedDisplay != null) {
			releaseHeldButtons();
			bridge.sendMotionOutside();
			return;
		}
		
		if(hitResult != null) {
			Vec3 coords = hitResult.surfaceLocal;
			WindowDisplay w = hitResult.target;
			
			if(!w.isValid()) {
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