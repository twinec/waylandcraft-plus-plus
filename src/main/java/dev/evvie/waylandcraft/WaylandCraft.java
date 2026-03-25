package dev.evvie.waylandcraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import dev.evvie.waylandcraft.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.grabs.MoveGrab;
import dev.evvie.waylandcraft.grabs.PointerGrabMap;
import dev.evvie.waylandcraft.grabs.PointerGrabMap.ReleasedImplicitGrab;
import dev.evvie.waylandcraft.gui.WaylandHudRenderer;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.item.WindowItemManager;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.render.WindowInHandRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ModInitializer, ClientModInitializer {
	public static final String MOD_ID = "waylandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String KEYBIND_CATEGORY = "key.categories." + MOD_ID;
	
	public static WaylandCraft instance;
	
	public WaylandCraftBridge bridge = null;
	public String waylandSocket = "";
	
	public ArrayList<WindowDisplay> displays = new ArrayList<WindowDisplay>();
	
	public boolean overridePickBlock = false;
	public HitResult trueGameHitResult = null;
	
	public WLCToplevel pinnedToplevel = null;
	
	public WindowItemManager itemManager = new WindowItemManager(this);
	public XDGDesktopManager xdgManager = new XDGDesktopManager();
	
	public KeyMapping keyOpenScreen;
	public KeyMapping keyCaptureKeyboard;
	
	public WindowInHandRenderer windowInHandRenderer = new WindowInHandRenderer();
	public WaylandHudRenderer hudRenderer = new WaylandHudRenderer(this);
	
	public PointerGrabMap pointerGrabs = new PointerGrabMap(this);
	
	// HitResult of currently hovered WindowDisplay
	// Only non-null, when no exclusive pointer grabs are currently active
	public DisplayHitResult hoveredDisplay = null;
	
	public KeyboardCaptureMode keyboardCaptureMode = KeyboardCaptureMode.NONE;
	
	public PointerCapture pointerCapture = null;
	
	@Override
	public void onInitialize() {
		WindowItem.register();
	}
	
	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing WaylandCraft");
		
		instance = this;
		
		keyOpenScreen = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.windowManager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEYBIND_CATEGORY));
		keyCaptureKeyboard = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.captureKeyboard", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KEYBIND_CATEGORY));
		
		WorldRenderEvents.END.register((ctx) -> this.renderWorld(ctx.camera()));
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		HudRenderCallback.EVENT.register(hudRenderer::render);
		CoreShaderRegistrationCallback.EVENT.register(RenderUtils::registerShaders);
		ServerTickEvents.START_WORLD_TICK.register(itemManager::onServerTick);
		ClientPlayConnectionEvents.JOIN.register(this::onClientJoin);
		
	}
	
	/* Update bridge and clients. May be called at any state of the game, even outside of a level
	 * Called before game render in Minecraft::runTick
	 */
	public void update() {
		if(bridge == null) {
			bridge = WaylandCraftBridge.start();
			waylandSocket = bridge.getSocket();
			
			LOGGER.info("Server started on " + waylandSocket);
		}
		bridge.update();
	}
	
	/* Called during level render. Used for everything relevant in-game. */
	public void renderWorld(Camera camera) {
		if(bridge == null) return;
		
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
		
		updateDisplayRequests();
		
		itemManager.giveItemsIfMissing(bridge.getNewToplevels());
		
		boolean inWMScreen = Minecraft.getInstance().screen instanceof WindowManagerScreen;
		
		// Make sure the toplevels are focused in their respective order and being refocused when a toplevel disappears
		if(!inWMScreen) {
			WLCToplevel focus = bridge.getMostToLeastRecentFocus()
					.filter((t) -> hasDisplayFor(t))
					.findFirst()
					.orElse(null);
			
			bridge.focusSurface(focus);
		}
		
		processPointerMotion(camera);
		
		displays.sort(((display1, display2) -> (int) Math.signum(display2.pivot.distanceToSqr(camera.getPosition()) - display1.pivot.distanceToSqr(camera.getPosition()))));
		displays.forEach((w) -> w.render(camera));
		
		updateOutputSize(inWMScreen);
	}
	
	public void enableKeyboardCapture(boolean hardCapture) {
		if(keyboardCaptureMode != KeyboardCaptureMode.NONE) return;
		
		keyboardCaptureMode = hardCapture ? KeyboardCaptureMode.HARD_CAPTURE : KeyboardCaptureMode.CAPTURE;
		bridge.activateKeyboard();
	}
	
	public void disableKeyboardCapture() {
		if(keyboardCaptureMode == KeyboardCaptureMode.NONE) return;
		
		keyboardCaptureMode = KeyboardCaptureMode.NONE;
		bridge.deactivateKeyboard();
		disablePointerCapture();
	}
	
	public void onClientTick(Minecraft minecraft) {
		if(keyOpenScreen.consumeClick()) {
			keyboardCaptureMode = KeyboardCaptureMode.NONE;
			pointerGrabs.releaseAll();
			minecraft.setScreen(new WindowManagerScreen(WaylandCraft.instance));
			return;
		}
		
		if(keyCaptureKeyboard.consumeClick()) {
			enableKeyboardCapture(false);
			return;
		}
	}
	
	private void onClientJoin(ClientPacketListener listener, PacketSender sender, Minecraft minecraft) {
		minecraft.player.sendSystemMessage(Component.literal("Wayland compositor running on " + waylandSocket));
		itemManager.giveItemsIfMissing(bridge.getToplevels());
	}
	
	private void updateDisplayRequests() {
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
		
		Integer moveRequest = bridge.checkMoveRequest();
		if(moveRequest != null) {
			ReleasedImplicitGrab implicit = pointerGrabs.releaseImplicitMatching(moveRequest.intValue());
			if(implicit != null) {
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new MoveGrab(implicit));
			}
		}
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
	
	public @Nullable WindowDisplay getDisplay(WLCAbstractWindow window) {
		return displays.stream().filter((w) -> w.window == window).findAny().orElse(null);
	}
	
	public WindowDisplay getOrCreateDisplay(WLCAbstractWindow window) {
		WindowDisplay display = getDisplay(window);
		if(display != null) return display;
		
		display = new WindowDisplay(window);
		displays.add(display);
		
		return display;
	}
	
	public boolean hasDisplayFor(WLCAbstractWindow window) {
		return getDisplay(window) != null;
	}
	
	public void disablePointerCapture() {
		if(pointerCapture == null) return;
		bridge.unlockPointer();
		pointerCapture = null;
	}
	
	private void processPointerMotion(Camera camera) {
		if(pointerCapture != null) {
			if(!pointerCapture.surface.isAlive()) {
				pointerCapture = null;
				return;
			}
			
			if(!bridge.maybeLockPointer(pointerCapture.surface)) {
				disablePointerCapture();
			}
			
			return;
		}
		
		// Reset hovered display and pick block override
		this.hoveredDisplay = null;
		this.overridePickBlock = false;
		
		if(Minecraft.getInstance().screen instanceof WindowManagerScreen) {
			return;
		}
		else if(Minecraft.getInstance().screen != null) {
			pointerGrabs.releaseAll();
			bridge.sendMotionOutside();
			return;
		}
		
		Vec3 pos = camera.getPosition();
		Vec3 look = new Vec3(camera.getLookVector());
		Vec3 up = new Vec3(camera.getUpVector());
		
		DisplayHitResult finalHitResult = null;
		double finalDistance = Double.POSITIVE_INFINITY;
		for(WindowDisplay display : displays) {
			DisplayHitResult hit = display.intersect(pos, look);
			if(hit == null || hit.isMiss()) continue;
			
			double dist = hit.position.distanceToSqr(pos);
			if(finalHitResult == null || dist < finalDistance) {
				finalHitResult = hit;
				finalDistance = dist;
			}
		}
		
		// Check if game hit result closer
		// Must use trueGameHitResult because the game hit result is overridden by overridePickBlock
		HitResult gameHitResult = trueGameHitResult;
		double gameHitDistance = (gameHitResult == null || gameHitResult.getType() == HitResult.Type.MISS) ? Double.POSITIVE_INFINITY : gameHitResult.getLocation().distanceToSqr(pos);
		if(gameHitDistance < finalDistance) finalHitResult = null;
		
		// Check for player reach
		if(finalHitResult != null && !finalHitResult.position.closerThan(pos, Minecraft.getInstance().player.blockInteractionRange())) finalHitResult = null;
		
		if(!pointerGrabs.isExclusiveGrabActive()) hoveredDisplay = finalHitResult;
		
		// Check for pointer grab and short-circuit if any
		if(pointerGrabs.isGrabActive()) {
			this.overridePickBlock = true;
			
			pointerGrabs.moveWorld(pos, look, up);
			if(finalHitResult != null) {
				pointerGrabs.hover(finalHitResult.target.window, finalHitResult.surface, finalHitResult.surfaceLocalOrigin.x, finalHitResult.surfaceLocalOrigin.y);
			}
			
			return;
		}
		
		/* All of the following code will only be executed when there aren't any active pointer grabs */
		
		if(hoveredDisplay != null) {
			this.overridePickBlock = true;
		}
		
		if(hoveredDisplay != null && hoveredDisplay.dist >= 0) {
			WLCSurface surface = hoveredDisplay.surface;
			Vec3 rel = hoveredDisplay.surfaceLocalRelative;
			
			bridge.sendMotionRefocus(surface, rel.x, rel.y);
			
			if(keyboardCaptureMode != KeyboardCaptureMode.NONE && bridge.maybeLockPointer(surface)) {
				pointerCapture = new PointerCapture(surface, rel.x, rel.y);
			}
		}
		else {
			bridge.sendMotionOutside();
		}
	}
	
	/* Handle mouse button input
	 * Returns true when the mouse button action has been consumed
	 */
	public boolean onButtonPress(long windowHandle, int button, int action, int modifiers) {
		if(pointerCapture != null) {
			if(action == 1 && !pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(0x110 + button, 1);
				pointerCapture.pressedButtons.add(button);
			}
			else if(action == 0 && pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(0x110 + button, 0);
				pointerCapture.pressedButtons.remove(button);
			}
			else if(action == 0) {
				// Forward release to minecraft if it wasn't part of this pointer capture
				return false;
			}
			return true;
		}
		
		if(action == 0 && pointerGrabs.isGrabActive(button)) {
			pointerGrabs.release(button);
			return true;
		}
		
		if(pointerGrabs.isExclusiveGrabActive()) return true;
		
		if(action == 1 && hoveredDisplay != null && !pointerGrabs.isGrabActive(button)) {
			if(hoveredDisplay.dist >= 0) {
				WLCAbstractWindow window = hoveredDisplay.target.window;
				pointerGrabs.startImplicit(hoveredDisplay.target, hoveredDisplay.surface, button);
				
				if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			}
			return true;
		}
		
		return false;
	}
	
	/* Handle mouse being turned in game
	 * Returns true when the mouse move has been consumed
	 */
	public boolean onMouseTurn(double dx, double dy) {
		if(pointerCapture == null) return false;
		
		bridge.sendRelativeMotion(dx, dy);
		
		// Workaround for xwayland-satellite issues, usually shouldn't be done
		// as it is technically against protocol and so might cause issues but
		// otherwise relative motion seems to not work.
//		bridge.sendMotion(pointerCapture.x += dx, pointerCapture.y += dy);
		
		return true;
	}
	
	/* Handle mouse scroll input
	 * Returns true when the mouse scroll action has been consumed
	 */
	public boolean onScroll(long windowHandle, double scrollX, double scrollY) {
		if(pointerGrabs.isExclusiveGrabActive()) return true;
		
		if(hoveredDisplay != null) {
			if(hoveredDisplay.dist < 0) return true;
			
			// Multiplication by -10 is the inverse transformation from what GLFW does on wayland
			bridge.sendScroll(0, -scrollY * 10);
			bridge.sendScroll(1, -scrollX * 10);
			
			WLCAbstractWindow window = hoveredDisplay.target.window;
			if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			
			return true;
		}
		
		return false;
	}
	
	/* Handle keyboard input
	 * Returns true when the key press action has been consumed
	 * This code just completely naively assumes that the scancode received by GLFW
	 * is also the correct matching Wayland scancode for the default XKBConfig.
	 * For X11 and Wayland hosts, this is a huge hack but should mostly work for now
	 */
	public boolean onKeyPress(long windowHandle, int key, int scancode, int action, int modifiers) {
		if(key == GLFW.GLFW_KEY_ESCAPE && modifiers == GLFW.GLFW_MOD_SUPER) {
			if(action == 0) return true;
			
			if(keyboardCaptureMode != KeyboardCaptureMode.HARD_CAPTURE) {
				enableKeyboardCapture(true);
			}
			else {
				disableKeyboardCapture();
			}
			return true;
		}
		
		if(keyboardCaptureMode == KeyboardCaptureMode.NONE) return false;
		
		if(keyboardCaptureMode == KeyboardCaptureMode.CAPTURE && key == GLFW.GLFW_KEY_ESCAPE) {
			disableKeyboardCapture();
			return true;
		}
		
		if(action == GLFW.GLFW_PRESS) {
			bridge.pressKey(scancode);
		}
		else if(action == GLFW.GLFW_RELEASE) {
			bridge.releaseKey(scancode);
		}
		
		return true;
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
	
	public static enum KeyboardCaptureMode {
		
		NONE, CAPTURE, HARD_CAPTURE;
		
	}
	
	public static class PointerCapture {
		
		public final WLCSurface surface;
		
		// Pointer capture entry surface-local coordinates
		public double x;
		public double y;
		
		public HashSet<Integer> pressedButtons = new HashSet<Integer>();
		
		public PointerCapture(WLCSurface surface, double x, double y) {
			this.surface = surface;
			this.x = x;
			this.y = y;
		}
		
	}
	
}