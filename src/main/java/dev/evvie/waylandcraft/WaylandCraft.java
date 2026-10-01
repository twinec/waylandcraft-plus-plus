package dev.evvie.waylandcraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Platform;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.ResizeRequest;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.desktop.XDGDesktopManager;
import dev.evvie.waylandcraft.displays.WindowDisplay;
import dev.evvie.waylandcraft.displays.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.grabs.DNDGrab;
import dev.evvie.waylandcraft.grabs.MoveGrab;
import dev.evvie.waylandcraft.grabs.PointerGrabMap;
import dev.evvie.waylandcraft.grabs.PointerGrabMap.ImplicitGrab;
import dev.evvie.waylandcraft.grabs.ResizeGrab;
import dev.evvie.waylandcraft.gui.AppLauncherScreen;
import dev.evvie.waylandcraft.gui.WaylandHudRenderer;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import dev.evvie.waylandcraft.item.WindowHandle;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.item.WindowItemManager;
import dev.evvie.waylandcraft.render.WindowInHandRenderer;
import dev.evvie.waylandcraft.render.WindowInItemFrameRenderer;
import dev.evvie.waylandcraft.render.model.WindowItemModel;
import dev.evvie.waylandcraft.settings.WaylandCraftSettings;
import dev.evvie.waylandcraft.settings.WaylandCraftSettingsManager;
import dev.evvie.waylandcraft.sharing.SharingNetworking;
import dev.evvie.waylandcraft.sharing.SharingOwner;
import dev.evvie.waylandcraft.sharing.SharingViewer;
import dev.evvie.waylandcraft.utils.CursorShape;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.evvie.waylandcraft.network.ClientboundHelloPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ClientModInitializer {
	
	private static final KeyMapping.Category KEYBIND_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "keys"));
	
	public static WaylandCraft instance;
	public static boolean fallbackMode = false;
	
	public WaylandCraftSettingsManager settingsManager;
	public WaylandCraftSettings settings;
	
	public WaylandCraftBridge bridge = null;
	public String waylandSocket = "";
	public @Nullable String x11Display = null;
	
	public ArrayList<WindowDisplay> displays = new ArrayList<WindowDisplay>();
	
	public boolean overridePickBlock = false;
	public HitResult trueGameHitResult = null;
	
	public WLCToplevel pinnedToplevel = null;
	
	public WindowItemManager itemManager = new WindowItemManager();
	public XDGDesktopManager xdgManager;
	
	public KeyMapping keyOpenScreen;
	public KeyMapping keyOpenAppLauncher;
	public KeyMapping keyCaptureKeyboard;
	public KeyMapping keyToggleSharing;
	
	public SharingOwner sharingOwner = new SharingOwner(this);
	public SharingViewer sharingViewer = new SharingViewer();
	
	public WindowInHandRenderer windowInHandRenderer = new WindowInHandRenderer();
	public WindowInItemFrameRenderer windowInItemFrameRenderer = new WindowInItemFrameRenderer();
	public WaylandHudRenderer hudRenderer = new WaylandHudRenderer(this);
	
	public PointerGrabMap pointerGrabs = new PointerGrabMap(this);
	
	// HitResult of currently hovered WindowDisplay
	// Only non-null, when no exclusive pointer grabs are currently active
	public DisplayHitResult hoveredDisplay = null;
	
	public KeyboardCaptureMode keyboardCaptureMode = KeyboardCaptureMode.NONE;
	
	public PointerCapture pointerCapture = null;
	
	private boolean playerUsingWindowItem = false;
	private boolean playerWasUsingWindowItem = false;
	
	public @Nullable CursorShape cursorShape = null;
	
	@Override
	public void onInitializeClient() {
		WaylandCraftCommon.LOGGER.info("Initializing WaylandCraft");
		
		instance = this;

		// No-op receivers -- their only purpose is making the client announce
		// support for this channel, so the server can detect WaylandCraft's
		// presence (see WaylandCraftPresence/PolymerCompat). Registered for
		// both CONFIGURATION (the one WaylandCraftPresence actually checks,
		// since it's guaranteed to complete before PLAY starts) and PLAY
		// (kept so the channel is still announced there too).
		ClientConfigurationNetworking.registerGlobalReceiver(ClientboundHelloPayload.TYPE, (payload, ctx) -> {});
		ClientPlayNetworking.registerGlobalReceiver(ClientboundHelloPayload.TYPE, (payload, ctx) -> {});

		keyOpenScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.windowManager", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, KEYBIND_CATEGORY));
		keyOpenAppLauncher = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.appLauncher", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, KEYBIND_CATEGORY));
		keyCaptureKeyboard = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.captureKeyboard", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, KEYBIND_CATEGORY));
		keyToggleSharing = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.toggleSharing", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, KEYBIND_CATEGORY));
		
		WindowItemModel.register();
		
		settingsManager = new WaylandCraftSettingsManager(this);
		
		// Watching windows other players share is pure Java, so it works on every platform
		SharingNetworking.registerClient();
		LevelRenderEvents.COLLECT_SUBMITS.register((ctx) -> {
			sharingViewer.presentFrames();
			sharingViewer.renderFloating(ctx.poseStack(), ctx.submitNodeCollector(), ctx.levelState().cameraRenderState.pos);
		});
		ClientTickEvents.END_CLIENT_TICK.register((minecraft) -> sharingViewer.tick());
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> sharingViewer.reset());
		
		if(Platform.get() != Platform.LINUX) {
			WaylandCraftCommon.LOGGER.error("Invalid platform detected! Most mod features will be disabled");
			WaylandCraft.fallbackMode = true;
			return;
		}
		
		LevelRenderEvents.COLLECT_SUBMITS.register(this::renderWorld);
		LevelExtractionEvents.END_EXTRACTION.register(this::updateWorld);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ClientPlayConnectionEvents.JOIN.register(this::onClientJoin);
		ClientPlayConnectionEvents.DISCONNECT.register(this::onClientDisconnect);
		ItemTooltipCallback.EVENT.register(this::addWindowItemTooltip);
		ClientTickEvents.START_CLIENT_TICK.register(itemManager);
		ClientTickEvents.END_CLIENT_TICK.register((minecraft) -> sharingOwner.tick());
		
		WaylandCraftCommon.instance.windowItemInteractionProvider = itemManager;
		
		hudRenderer.register();
	}
	
	/* Update bridge and clients. May be called at any state of the game, even outside of a level
	 * Called after game render in Minecraft::runTick
	 */
	public void update() {
		if(fallbackMode) return;
		
		if(bridge == null) {
			bridge = WaylandCraftBridge.start();
			waylandSocket = bridge.getSocket();
			x11Display = bridge.getX11Display();
			xdgManager = new XDGDesktopManager(this);
			registerSettingsResponders();
			settingsManager.loadKeymap();
			settingsManager.loadEnvOverrides();

			WaylandCraftCommon.LOGGER.info("Wayland server started on " + waylandSocket);
			WaylandCraftCommon.LOGGER.info("Xwayland started on " + x11Display);
		}
		bridge.update();
		sharingOwner.captureFrames();
	}
	
	private void registerSettingsResponders() {
		settingsManager.registerResponder(WaylandCraftSettings.TERMINAL_CHOICE, (value) -> {
			bridge.setPreferredTerminal((String) value);
		});
	}
	
	public void renderWorld(LevelRenderContext ctx) {
		if(bridge == null) return;
		
		displays.forEach((d) -> d.render(ctx));
	}
	
	// called in the pick() method of MinecraftMixin
	public void updatePointer() {
		if(bridge == null) return;
		
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		processPointerMotion(camera);
		
		if(Minecraft.getInstance().player == null || !Minecraft.getInstance().player.isUsingItem()) playerUsingWindowItem = false;
		if(playerUsingWindowItem) {
			ItemStack item = Minecraft.getInstance().player.getUseItem();
			if(item.is(WindowItem.WINDOW)) {
				WLCToplevel toplevel = getToplevel(item);
				
				if(toplevel != null) {
					WindowDisplay display = getOrCreateDisplay(toplevel);
					if(!playerWasUsingWindowItem) {
						display.anchorDistance = 2.0;
					}
					
					display.doGrabMove(camera.position(), new Vec3(camera.forwardVector()), new Vec3(camera.upVector()), camera.yRot());
					
					WaylandCraft.instance.bridge.focusSurface(toplevel);
				}
			}
			else playerUsingWindowItem = false;
		}
		playerWasUsingWindowItem = playerUsingWindowItem;
	}
	
	public void updateWorld(LevelExtractionContext ctx) {
		for(WLCPopup popup : bridge.getMappedPopups()) {
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
		
		for(WLCPopup popup : bridge.getMappedPopups()) {
			anchorToParent(popup);
		}
	}
	
	public void onClientTick(Minecraft minecraft) {
		if(minecraft.player == null) return;
		checkKeybinds(minecraft);
		
		updateDisplayRequests();
		
		itemManager.giveItemsIfMissing(bridge.getNewToplevels());
		
		boolean inWMScreen = Minecraft.getInstance().gui.screen() instanceof WindowManagerScreen;
		
		// Make sure the toplevels are focused in their respective order and being refocused when a toplevel disappears
		if(!inWMScreen) {
			WLCToplevel focus = bridge.getMostToLeastRecentFocus()
					.filter((t) -> hasDisplayFor(t))
					.findFirst()
					.orElse(null);
			
			bridge.focusSurface(focus);
		}
		
		updateOutputSize(inWMScreen);
	}
	
	public void startUsingWindowItem() {
		playerUsingWindowItem = true;
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
		
	private void checkKeybinds(Minecraft minecraft) {
		if(keyOpenScreen.consumeClick()) {
			disableKeyboardCapture();
			pointerGrabs.releaseAll();
			minecraft.setScreenAndShow(new WindowManagerScreen(WaylandCraft.instance));
		}
		else if(keyOpenAppLauncher.consumeClick()) {
			minecraft.setScreenAndShow(new AppLauncherScreen(WaylandCraft.instance));
		}
		else if(keyCaptureKeyboard.consumeClick()) {
			enableKeyboardCapture(false);
		}
		else if(keyToggleSharing.consumeClick()) {
			sharingOwner.toggleFocused();
		}
	}
	
	private void onClientJoin(ClientPacketListener listener, PacketSender sender, Minecraft minecraft) {
		minecraft.gui.chatListener().handleSystemMessage(Component.literal("Wayland compositor running on " + waylandSocket), false);
		if(x11Display != null) minecraft.gui.chatListener().handleSystemMessage(Component.literal("xwayland-satellite running on " + x11Display), false);
		itemManager.giveItemsIfMissing(bridge.getMappedToplevels());
	}
	
	private void onClientDisconnect(ClientPacketListener listener, Minecraft minecraft) {
		displays.clear();
		itemManager.reset();
		sharingOwner.reset();
	}
	
	@Nullable
	public static WLCToplevel getToplevel(ItemStack item) {
		if(item == null) return null;
		if(WaylandCraft.instance.bridge == null) return null;

		LocalPlayer player = Minecraft.getInstance().player;
		if(player == null) return null;

		WindowHandle data = WindowHandle.from(item);
		if(data == null) return null;
		if(!data.matchesPlayer(player)) return null;

		return WaylandCraft.instance.bridge.getToplevel(data.handle());
	}
	
	private void addWindowItemTooltip(ItemStack itemStack, TooltipContext ctx, TooltipFlag flag, List<Component> list) {
		WindowHandle handle = WindowHandle.from(itemStack);
		if(handle != null) {
			String text = "Handle 0x" + Long.toHexString(handle.handle());
			Component component = Component
					.literal(text)
					.withStyle(ChatFormatting.GRAY);
			list.add(component);
			String owner = "Owner " + handle.player();
			component = Component
					.literal(owner)
					.withStyle(ChatFormatting.GRAY);
			list.add(component);
		}
	}
	
	private void updateDisplayRequests() {
		// Hide all windows that were minimized and unset minimize requested state
		displays.removeIf((w) -> w.window instanceof WLCToplevel && ((WLCToplevel) w.window).requests.minimize);
		Stream.of(bridge.getToplevels()).forEach((t) -> t.requests.minimize = false);
		
		// Handle any maximize or unmaximize requests
		for(WLCToplevel toplevel : bridge.getMappedToplevels()) {
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
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(moveRequest.intValue());
			if(implicit != null) {
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new MoveGrab(implicit));
			}
		}
		
		ResizeRequest resizeRequest = bridge.checkResizeRequest();
		if(resizeRequest != null) {
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(resizeRequest.serial());
			if(implicit != null) {
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new ResizeGrab(implicit, resizeRequest.edges()));
			}
		}
		
		Integer dndRequest = bridge.checkDndRequest();
		if(dndRequest != null) {
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(dndRequest);
			if(implicit != null) {
				WaylandCraftCommon.LOGGER.info("DND STARTED");
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new DNDGrab(implicit));
			}
			else {
				// Couldn't match implicit grab, have to cancel dnd
				WaylandCraftCommon.LOGGER.info("drag and drop did not match implicit grab");
				bridge.dndCancel();
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
		destroyPointerOverlay();
		if(pointerCapture == null) return;
		if(pointerCapture instanceof LockedPointerCapture) bridge.unlockPointer();
		pointerCapture = null;
	}
	
	public void destroyPointerOverlay() {
		if(Minecraft.getInstance().gui.overlay() instanceof PointerCaptureOverlay overlay) {
			overlay.destroy();
			Minecraft.getInstance().gui.setOverlay(null);
		}
	}
	
	private void processPointerMotion(Camera camera) {
		this.cursorShape = null;
		
		if(pointerCapture != null) {
			if(!pointerCapture.isValid()) {
				disablePointerCapture();
				return;
			}
			
			if(pointerCapture instanceof LockedPointerCapture) this.cursorShape = bridge.getCursorShape();
			else this.cursorShape = CursorShape.HIDE;
			
			boolean locked = pointerCapture.surface != null && bridge.maybeLockPointer(pointerCapture.surface);
			boolean detach = settings.getDetachCursor();
			if(pointerCapture instanceof LockedPointerCapture && !locked) {
				PointerCapture old = pointerCapture;
				disablePointerCapture();
				if(detach) {
					pointerCapture = new MotionPointerCapture(old.display, old.pressedButtons);
				}
			}
			else if(pointerCapture instanceof MotionPointerCapture && locked) {
				PointerCapture old = pointerCapture;
				disablePointerCapture();
				if(detach) {
					pointerCapture = new LockedPointerCapture(old.display, old.surface, old.pressedButtons);
				}
			}
			
			return;
		}
		
		// Reset hovered display and pick block override
		this.hoveredDisplay = null;
		this.overridePickBlock = false;
		
		if(Minecraft.getInstance().gui.screen() instanceof WindowManagerScreen) {
			return;
		}
		else if(Minecraft.getInstance().gui.screen() != null) {
			pointerGrabs.releaseAll();
			bridge.sendMotionOutside();
			return;
		}
		
		Vec3 pos = camera.position();
		Vec3 look = new Vec3(camera.forwardVector());
		Vec3 up = new Vec3(camera.upVector());
		
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
			this.cursorShape = bridge.getCursorShape();
			
			pointerGrabs.moveWorld(pos, look, up, camera.yRot(), camera.xRot());
			if(finalHitResult != null) {
				pointerGrabs.hover(finalHitResult.target.window, finalHitResult.surface, finalHitResult.surfaceLocalRelative.x, finalHitResult.surfaceLocalRelative.y);
			}
			else {
				pointerGrabs.hoverNone();
			}
			
			return;
		}
		
		/* All of the following code will only be executed when there aren't any active pointer grabs */
		
		if(hoveredDisplay != null && !canStartInteracting()) hoveredDisplay = null;
		
		if(hoveredDisplay != null) {
			this.overridePickBlock = true;
		}
		
		if(hoveredDisplay != null && hoveredDisplay.dist >= 0) {
			WindowDisplay display = hoveredDisplay.target;
			WLCSurface surface = hoveredDisplay.surface;
			Vec3 rel = hoveredDisplay.surfaceLocalRelative;
			
			this.cursorShape = bridge.getCursorShape();
			bridge.sendMotionRefocus(surface, rel.x, rel.y);
			
			if(keyboardCaptureMode != KeyboardCaptureMode.NONE) {
				boolean pointerLocked = bridge.maybeLockPointer(surface);
				if(pointerLocked) {
					pointerCapture = new LockedPointerCapture(display, surface);
				}
				else if(settings.getDetachCursor()) {
					pointerCapture = new MotionPointerCapture(display);
				}
			}
			
			// Focus on hover
			if(settings.getFocusOnHover() && hoveredDisplay.target.window instanceof WLCToplevel toplevel) {
				bridge.focusSurface(toplevel);
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
		if(bridge == null) return false;
		
		if(pointerCapture != null) {
			if(action == 1 && !pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(correctButtonCode(button), 1);
				pointerCapture.pressedButtons.add(button);
			}
			else if(action == 0 && pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(correctButtonCode(button), 0);
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
		
		// Handle implicit pointer grab button presses
		if(action == 1) {
			// Start new implicit grab when conditions are met
			if(!pointerGrabs.isImplicitActive() && hoveredDisplay != null && hoveredDisplay.dist >= 0) {
				pointerGrabs.startImplicit(hoveredDisplay);
				WLCAbstractWindow window = hoveredDisplay.target.window;
				if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			}
			
			// If an implicit pointer grab is now active, capture the button press
			if(pointerGrabs.isImplicitActive()) {
				pointerGrabs.sendImplicitButton(button);
				return true;
			}
			
			// If clicking on a window at all, the button press should be captured, even if it wasn't passed on to the application
			if(hoveredDisplay != null) return true;
		}
		
		return false;
	}
	
	private boolean canStartInteracting() {
		LocalPlayer player = Minecraft.getInstance().player;
		if(player == null) return false;
		if(player.isUsingItem()) return false;
		return true;
	}
	
	/* Handle mouse being turned in game
	 * Returns true when the mouse move has been consumed
	 */
	public boolean onMouseTurn(double dx, double dy) {
		if(bridge == null) return false;
		if(pointerCapture == null) return false;
		if(pointerCapture instanceof LockedPointerCapture) bridge.sendRelativeMotion(dx, dy);
		return true;
	}
	
	/* Handle mouse scroll input
	 * Returns true when the mouse scroll action has been consumed
	 */
	public boolean onScroll(long windowHandle, double scrollX, double scrollY) {
		if(bridge == null) return false;
		
		if(playerUsingWindowItem) {
			WLCToplevel toplevel = getToplevel(Minecraft.getInstance().player.getUseItem());
			if(toplevel != null) {
				WindowDisplay display = getDisplay(toplevel);
				if(display != null) {
					display.adjustAnchorDistance(scrollY);
					return true;
				}
			}
		}

		if(pointerGrabs.isExclusiveGrabActive()) {
			pointerGrabs.onScroll(scrollX, scrollY);
			return true;
		}
		
		if(hoveredDisplay != null) {
			if(hoveredDisplay.dist < 0) return true;
			
			bridge.sendScroll(0, -scrollY);
			bridge.sendScroll(1, -scrollX);
			
			WLCAbstractWindow window = hoveredDisplay.target.window;
			if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			
			return true;
		}
		
		return false;
	}
	
	/* Handle keyboard input
	 * Returns true when the key press action has been consumed
	 */
	public boolean onKeyPress(long windowHandle, int key, int scancode, int action, int modifiers) {
		if(bridge == null) return false;
		
		// MOD_ALT is now a combined left+right bitmask (0x300); a single Alt
		// press only ever sets one of those two bits, so the old exact
		// "modifiers == MOD_ALT" check (correct back when MOD_ALT was GLFW's
		// single undifferentiated bit) never matched. Check "some Alt bit,
		// nothing else" instead, ignoring caps/num lock state.
		int relevantModifiers = modifiers & ~(InputConstants.MOD_CAPS_LOCK | InputConstants.MOD_NUM_LOCK);
		if(key == InputConstants.KEY_Q && (relevantModifiers & InputConstants.MOD_ALT) != 0 && (relevantModifiers & ~InputConstants.MOD_ALT) == 0) {
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
		
		if(keyboardCaptureMode == KeyboardCaptureMode.CAPTURE && key == InputConstants.KEY_ESCAPE) {
			disableKeyboardCapture();
			return true;
		}

		if(action == InputConstants.PRESS) {
			bridge.pressKey(scancode);
		}
		else if(action == InputConstants.RELEASE) {
			bridge.releaseKey(scancode);
		}
		
		return true;
	}
	
	/* SDL's scancodes are USB-HID-based (SDL_SCANCODE_A = 4), a completely
	 * different numbering scheme from the Linux evdev keycodes (KEY_A = 30)
	 * that our embedded Wayland compositor's native side works in (both for
	 * emitting wl_keyboard.key events and for driving libxkbcommon, which
	 * expects an "XKB keycode" = evdev keycode + 8). This table is the
	 * inverse of SDL's own linux_scancode_table (src/events/scancodes_linux.h),
	 * covering every SDL scancode that table maps to; anything not covered
	 * falls back to the raw scancode, which is wrong but at least won't
	 * underflow when the native side subtracts 8 back off.
	 */
	private static final int[] SDL_SCANCODE_TO_EVDEV = buildScancodeToEvdevTable();

	private static int[] buildScancodeToEvdevTable() {
		int[] table = new int[287];
		Arrays.fill(table, -1);

		table[4] = 30; // A
		table[5] = 48; // B
		table[6] = 46; // C
		table[7] = 32; // D
		table[8] = 18; // E
		table[9] = 33; // F
		table[10] = 34; // G
		table[11] = 35; // H
		table[12] = 23; // I
		table[13] = 36; // J
		table[14] = 37; // K
		table[15] = 38; // L
		table[16] = 50; // M
		table[17] = 49; // N
		table[18] = 24; // O
		table[19] = 25; // P
		table[20] = 16; // Q
		table[21] = 19; // R
		table[22] = 31; // S
		table[23] = 20; // T
		table[24] = 22; // U
		table[25] = 47; // V
		table[26] = 17; // W
		table[27] = 45; // X
		table[28] = 21; // Y
		table[29] = 44; // Z
		table[30] = 2; // 1
		table[31] = 3; // 2
		table[32] = 4; // 3
		table[33] = 5; // 4
		table[34] = 6; // 5
		table[35] = 7; // 6
		table[36] = 8; // 7
		table[37] = 9; // 8
		table[38] = 10; // 9
		table[39] = 11; // 0
		table[40] = 28; // RETURN
		table[41] = 1; // ESCAPE
		table[42] = 14; // BACKSPACE
		table[43] = 15; // TAB
		table[44] = 57; // SPACE
		table[45] = 12; // MINUS
		table[46] = 13; // EQUALS
		table[47] = 26; // LEFTBRACKET
		table[48] = 27; // RIGHTBRACKET
		table[49] = 43; // BACKSLASH
		table[51] = 39; // SEMICOLON
		table[52] = 40; // APOSTROPHE
		table[53] = 41; // GRAVE
		table[54] = 51; // COMMA
		table[55] = 52; // PERIOD
		table[56] = 53; // SLASH
		table[57] = 58; // CAPSLOCK
		table[58] = 59; // F1
		table[59] = 60; // F2
		table[60] = 61; // F3
		table[61] = 62; // F4
		table[62] = 63; // F5
		table[63] = 64; // F6
		table[64] = 65; // F7
		table[65] = 66; // F8
		table[66] = 67; // F9
		table[67] = 68; // F10
		table[68] = 87; // F11
		table[69] = 88; // F12
		table[70] = 210; // PRINTSCREEN
		table[71] = 70; // SCROLLLOCK
		table[72] = 119; // PAUSE
		table[73] = 110; // INSERT
		table[74] = 102; // HOME
		table[75] = 104; // PAGEUP
		table[76] = 111; // DELETE
		table[77] = 107; // END
		table[78] = 109; // PAGEDOWN
		table[79] = 106; // RIGHT
		table[80] = 105; // LEFT
		table[81] = 108; // DOWN
		table[82] = 103; // UP
		table[83] = 69; // NUMLOCKCLEAR
		table[84] = 98; // KP_DIVIDE
		table[85] = 55; // KP_MULTIPLY
		table[86] = 74; // KP_MINUS
		table[87] = 78; // KP_PLUS
		table[88] = 96; // KP_ENTER
		table[89] = 79; // KP_1
		table[90] = 80; // KP_2
		table[91] = 81; // KP_3
		table[92] = 75; // KP_4
		table[93] = 76; // KP_5
		table[94] = 77; // KP_6
		table[95] = 71; // KP_7
		table[96] = 72; // KP_8
		table[97] = 73; // KP_9
		table[98] = 82; // KP_0
		table[99] = 83; // KP_PERIOD
		table[100] = 86; // NONUSBACKSLASH
		table[101] = 127; // APPLICATION
		table[102] = 116; // POWER
		table[103] = 117; // KP_EQUALS
		table[104] = 183; // F13
		table[105] = 184; // F14
		table[106] = 185; // F15
		table[107] = 186; // F16
		table[108] = 187; // F17
		table[109] = 188; // F18
		table[110] = 189; // F19
		table[111] = 190; // F20
		table[112] = 191; // F21
		table[113] = 192; // F22
		table[114] = 193; // F23
		table[115] = 194; // F24
		table[117] = 138; // HELP
		table[118] = 139; // MENU
		table[119] = 353; // SELECT
		table[120] = 128; // STOP
		table[121] = 129; // AGAIN
		table[122] = 131; // UNDO
		table[123] = 137; // CUT
		table[124] = 133; // COPY
		table[125] = 135; // PASTE
		table[126] = 136; // FIND
		table[127] = 113; // MUTE
		table[128] = 115; // VOLUMEUP
		table[129] = 114; // VOLUMEDOWN
		table[133] = 121; // KP_COMMA
		table[135] = 89; // INTERNATIONAL1
		table[136] = 93; // INTERNATIONAL2
		table[137] = 124; // INTERNATIONAL3
		table[138] = 92; // INTERNATIONAL4
		table[139] = 94; // INTERNATIONAL5
		table[140] = 95; // INTERNATIONAL6
		table[144] = 122; // LANG1
		table[145] = 123; // LANG2
		table[146] = 90; // LANG3
		table[147] = 91; // LANG4
		table[148] = 85; // LANG5
		table[153] = 222; // ALTERASE
		table[154] = 99; // SYSREQ
		table[155] = 223; // CANCEL
		table[156] = 355; // CLEAR
		table[165] = 132; // FRONT
		table[182] = 179; // KP_LEFTPAREN
		table[183] = 180; // KP_RIGHTPAREN
		table[215] = 118; // KP_PLUSMINUS
		table[224] = 29; // LCTRL
		table[225] = 42; // LSHIFT
		table[226] = 56; // LALT
		table[227] = 125; // LGUI
		table[228] = 97; // RCTRL
		table[229] = 54; // RSHIFT
		table[230] = 100; // RALT
		table[231] = 126; // RGUI
		table[257] = 373; // MODE
		table[258] = 142; // SLEEP
		table[259] = 143; // WAKE
		table[260] = 402; // CHANNEL_INCREMENT
		table[261] = 403; // CHANNEL_DECREMENT
		table[262] = 200; // MEDIA_PLAY
		table[263] = 201; // MEDIA_PAUSE
		table[264] = 167; // MEDIA_RECORD
		table[265] = 208; // MEDIA_FAST_FORWARD
		table[266] = 168; // MEDIA_REWIND
		table[267] = 163; // MEDIA_NEXT_TRACK
		table[268] = 165; // MEDIA_PREVIOUS_TRACK
		table[269] = 166; // MEDIA_STOP
		table[270] = 161; // MEDIA_EJECT
		table[271] = 164; // MEDIA_PLAY_PAUSE
		table[272] = 226; // MEDIA_SELECT
		table[273] = 181; // AC_NEW
		table[274] = 134; // AC_OPEN
		table[275] = 206; // AC_CLOSE
		table[276] = 174; // AC_EXIT
		table[277] = 234; // AC_SAVE
		table[279] = 130; // AC_PROPERTIES
		table[280] = 217; // AC_SEARCH
		table[281] = 172; // AC_HOME
		table[282] = 158; // AC_BACK
		table[283] = 159; // AC_FORWARD
		table[285] = 173; // AC_REFRESH
		table[286] = 156; // AC_BOOKMARKS

		return table;
	}

	public static int correctScancode(int scancode) {
		int evdev = (scancode >= 0 && scancode < SDL_SCANCODE_TO_EVDEV.length) ? SDL_SCANCODE_TO_EVDEV[scancode] : -1;
		if(evdev < 0) evdev = scancode;
		return evdev + 8;
	}

	/* SDL numbers mouse buttons 1-based (LEFT=1, MIDDLE=2, RIGHT=3, X1=4,
	 * X2=5, ...), in a different order than the Linux evdev BTN_* codes our
	 * embedded compositor's wl_pointer.button event expects (BTN_LEFT=0x110,
	 * BTN_RIGHT=0x111, BTN_MIDDLE=0x112, then BTN_SIDE/BTN_EXTRA/...). A
	 * flat "0x110 + button" offset (this port's old GLFW-derived code)
	 * mismatches every button by one, and swaps middle/right outright.
	 */
	public static int correctButtonCode(int sdlButton) {
		if(sdlButton == 2) return 0x112; // BTN_MIDDLE
		if(sdlButton == 3) return 0x111; // BTN_RIGHT
		return 0x110 + (sdlButton - 1);
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
		window.moveOrigin(parent.localToWorld(popup.offsetX, popup.offsetY, 0.01));
	}
	
	public static enum KeyboardCaptureMode {
		
		NONE, CAPTURE, HARD_CAPTURE;
		
	}
	
	public abstract class PointerCapture {
		
		public final WindowDisplay display;
		public final HashSet<Integer> pressedButtons;
		
		public WLCSurface surface;
		
		public PointerCapture(WindowDisplay display, WLCSurface surface, Collection<Integer> pressedButtons) {
			this.display = display;
			this.surface = surface;
			this.pressedButtons = new HashSet<Integer>(pressedButtons);
		}
		
		public boolean isValid() {
			return displays.contains(display);
		}
		
	}
	
	public class LockedPointerCapture extends PointerCapture {
		
		public LockedPointerCapture(WindowDisplay display, WLCSurface surface, Collection<Integer> pressedButtons) {
			super(display, surface, pressedButtons);
		}
		
		public LockedPointerCapture(WindowDisplay display, WLCSurface surface) {
			this(display, surface, Collections.emptyList());
		}
		
		@Override
		public boolean isValid() {
			return super.isValid() && surface.isAlive();
		}
		
	}
	
	public class MotionPointerCapture extends PointerCapture {
		
		public MotionPointerCapture(WindowDisplay display, Collection<Integer> pressedButtons) {
			super(display, null, pressedButtons);
			
			if(Minecraft.getInstance().gui.overlay() == null) Minecraft.getInstance().gui.setOverlay(new PointerCaptureOverlay());
		}
		
		public MotionPointerCapture(WindowDisplay display) {
			this(display, Collections.emptyList());
		}
		
	}
	
	public class PointerCaptureOverlay extends Overlay {
		
		public static final ScopedValue<Void> STOP_KEYMAPPING_SET_ALL = ScopedValue.newInstance();
		
		public PointerCaptureOverlay() {
			Minecraft.getInstance().mouseHandler.releaseMouse();
		}
		
		public void destroy() {
			ScopedValue.where(STOP_KEYMAPPING_SET_ALL, null).run(() -> {
				Minecraft.getInstance().mouseHandler.grabMouse();
			});
		}
		
		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			if(!(pointerCapture instanceof MotionPointerCapture motionCapture)) return;
			
			Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
			Camera.NearPlane plane = camera.getNearPlane(Minecraft.getInstance().options.fov().get().intValue());
			MouseHandler mouseHandler = Minecraft.getInstance().mouseHandler;
			Window window = Minecraft.getInstance().getWindow();
			
			double rx = mouseHandler.xpos() / window.getWidth() * 2 - 1;
			double ry = -(mouseHandler.ypos() / window.getHeight() * 2 - 1);
			
			Vec3 pos = camera.position();
			Vec3 look = plane.getPointOnPlane((float) rx, (float) ry).normalize();
			
			DisplayHitResult result = pointerCapture.display.intersect(pos, look);
			if(result.isMiss()) {
				bridge.sendMotionOutside();
				motionCapture.surface = null;
			}
			else {
				bridge.sendMotionRefocus(result.surface, result.surfaceLocalRelative.x, result.surfaceLocalRelative.y);
				motionCapture.surface = result.surface;
			}
		}

	}

}

