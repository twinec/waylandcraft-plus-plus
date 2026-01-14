package dev.evvie.waylandcraft.bridge;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeEGL;

import dev.evvie.waylandcraft.BufferTexture.DmabufTexture;
import dev.evvie.waylandcraft.WindowFramebuffer;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import net.minecraft.client.Minecraft;

public class WaylandCraftBridge {
	
	private long instance;
	private ArrayList<WLCToplevel> toplevels = new ArrayList<WLCToplevel>();
	private ArrayList<WLCPopup> popups = new ArrayList<WLCPopup>();
	private ArrayList<WLCSurface> surfaces = new ArrayList<WLCSurface>();
	private ArrayList<DmabufTexture> dmabufs = new ArrayList<DmabufTexture>();
	
	private LinkedList<WLCToplevel> focusOrder = new LinkedList<WLCToplevel>();
	
	static {
		System.loadLibrary("waylandcraft");
	}
	
	private WaylandCraftBridge(long handle) {
		this.instance = handle;
	}
	
	public static WaylandCraftBridge start() {
		long eglDisplay = GLFWNativeEGL.glfwGetEGLDisplay();
		long eglConfig = GLFWNativeEGL.glfwGetEGLConfig(Minecraft.getInstance().getWindow().getWindow());
		
		if(eglDisplay == 0 || eglConfig == 0) {
			throw new RuntimeException("Failed to get EGL display or config!");
		}
		
		long handle = init(GLFW.Functions.GetProcAddress, eglDisplay);
		return new WaylandCraftBridge(handle);
	}
	
	protected WLCToplevel getOrCreateToplevel(long handle) {
		for(WLCToplevel toplevel : toplevels) {
			if(toplevel.getHandle() == handle) return toplevel;
		}
		WLCToplevel toplevel = new WLCToplevel(handle);
		
		long surfaceHandle = toplevelSurface(this.instance, handle);
		WLCSurface surface = getOrCreateSurface(surfaceHandle);
		toplevel.surface = surface;
		
		toplevels.add(toplevel);
		return toplevel;
	}
	
	protected WLCPopup getOrCreatePopup(long handle) {
		for(WLCPopup popup : popups) {
			if(popup.getHandle() == handle) return popup;
		}
		WLCPopup popup = new WLCPopup(handle);
		
		long surfaceHandle = popupSurface(this.instance, handle);
		WLCSurface surface = getOrCreateSurface(surfaceHandle);
		popup.surface = surface;
		
		popup.parentHandle = popupParent(this.instance, handle);
		
		popups.add(popup);
		return popup;
	}
	
	protected WLCSurface getOrCreateSurface(long handle) {
		for(WLCSurface surface : surfaces) {
			if(surface.getHandle() == handle) return surface;
		}
		WLCSurface surface = new WLCSurface(handle);
		surfaces.add(surface);
		return surface;
	}
	
	protected DmabufTexture getDmabuf(long handle) {
		for(DmabufTexture dmabuf : dmabufs) {
			if(dmabuf.handle == handle) return dmabuf;
		}
		return null;
	}
	
	protected void addDmabuf(DmabufTexture dmabuf) {
		dmabufs.add(dmabuf);
	}
	
	private void deleteNonExistingToplevels(long[] remainingHandles) {
		ArrayList<WLCToplevel> toplevels_new = new ArrayList<WLCToplevel>();
		for(WLCToplevel toplevel : this.toplevels) {
			if(ArrayUtils.contains(remainingHandles, toplevel.getHandle())) {
				toplevels_new.add(toplevel);
			}
			else {
				freeToplevel(this.instance, toplevel.takeHandle());
			}
		}
		this.toplevels = toplevels_new;
	}
	
	private void deleteNonExistingPopups(long[] remainingHandles) {
		ArrayList<WLCPopup> popups_new = new ArrayList<WLCPopup>();
		for(WLCPopup popup : this.popups) {
			if(ArrayUtils.contains(remainingHandles, popup.getHandle())) {
				popups_new.add(popup);
			}
			else {
				freePopup(this.instance, popup.takeHandle());
			}
		}
		this.popups = popups_new;
	}
	
	private void deleteNonExistingDmabufs(long[] remainingHandles) {
		ArrayList<DmabufTexture> dmabufs_new = new ArrayList<DmabufTexture>();
		for(DmabufTexture dmabuf : this.dmabufs) {
			if(ArrayUtils.contains(remainingHandles, dmabuf.handle)) {
				dmabufs_new.add(dmabuf);
			}
			else {
				dmabuf.free();
			}
		}
		this.dmabufs = dmabufs_new;
	}
	
	private void deleteUnvisitedSurfaces() {
		ArrayList<WLCSurface> surfaces_new = new ArrayList<WLCSurface>();
		for(WLCSurface surface : this.surfaces) {
			if(surface.visited) {
				surfaces_new.add(surface);
			}
			else {
				freeSurface(this.instance, surface.takeHandle());
			}
		}
		this.surfaces = surfaces_new;
	}
	
	private void findPopupParent(WLCPopup popup) {
		// Popups cannot change their parent, so if one is found, it's the one
		if(popup.parent != null) return;
		
		for(WLCToplevel toplevel : toplevels) {
			if(toplevel.getHandle() == popup.parentHandle) {
				popup.parent = toplevel;
				return;
			}
		}
		
		for(WLCPopup popup2 : popups) {
			if(popup2.getHandle() == popup.parentHandle) {
				popup.parent = popup2;
				return;
			}
		}
	}
	
	public void update() {
		// Update wayland clients
		update(this.instance);
		
		// Find all available toplevels and delete ones that no longer exist
		long[] toplevelHandles = toplevels(instance);
		deleteNonExistingToplevels(toplevelHandles);
		
		// Find all available popups and delete ones that no longer exist
		long[] popupHandles = popups(instance);
		deleteNonExistingPopups(popupHandles);
		
		long[] minimizeRequests = minimizeReq(instance);
		long[] maximizeRequests = maximizeReq(instance);
		long[] unmaximizeRequests = unmaximizeReq(instance);
		long[] fullscreenRequests = fullscreenReq(instance);
		long[] unfullscreenRequests = unfullscreenReq(instance);
		
		// Reset surface visited state
		for(WLCSurface surface : surfaces) {
			surface.visited = false;
		}
		
		// Create new toplevels when necessary
		// Update surface tree geometry and properties of all toplevels
		for(long handle : toplevelHandles) {
			WLCToplevel toplevel = getOrCreateToplevel(handle);
			WLCSurface root = toplevel.getSurfaceTree();
			toplevel.lastChild = updateSurfaceTree(root);
			
			updateGeometry(toplevel);
			toplevel.title = toplevelTitle(toplevel.getHandle());
			toplevel.appID = toplevelAppID(toplevel.getHandle());
			
			if(ArrayUtils.contains(minimizeRequests, handle)) toplevel.minimizeRequest = true;
			if(ArrayUtils.contains(maximizeRequests, handle)) toplevel.maximizeRequest = true;
			if(ArrayUtils.contains(unmaximizeRequests, handle)) toplevel.unmaximizeRequest = true;
			if(ArrayUtils.contains(fullscreenRequests, handle)) toplevel.fullscreenRequest = true;
			if(ArrayUtils.contains(unfullscreenRequests, handle)) toplevel.unfullscreenRequest = true;
		}
		
		// Create new popups when necessary
		// Update surface tree geometry, parent relationships and offsets of all popups
		for(long handle : popupHandles) {
			WLCPopup popup = getOrCreatePopup(handle);
			findPopupParent(popup);
			
			int[] offset = popupOffset(handle);
			popup.offsetX = offset[0];
			popup.offsetY = offset[1];
			
			WLCSurface root = popup.getSurfaceTree();
			popup.lastChild = updateSurfaceTree(root);
			updateGeometry(popup);
		}
		
		// All surface trees have now been walked. Now delete all unvisited surfaces
		deleteUnvisitedSurfaces();
		
		// Resolve surface parent handles to actual surfaces
		for(WLCSurface surface : surfaces) {
			if(surface.parentHandle != 0) {
				surface.parent = getOrCreateSurface(surface.parentHandle);
			}
			else {
				surface.parent = null;
			}
		}
		
		List<WLCAbstractWindow> allWindows = Stream.of(toplevels, popups).flatMap((l) -> l.stream()).collect(Collectors.toList());
		
		// Update all surface buffers
		for(WLCAbstractWindow window : allWindows) {
			WLCSurface root = window.getSurfaceTree();
			for(WLCSurface surface = root; surface != null; surface = surface.getNextChild()) {
				updateSurfaceData(instance, surface);
				calculateSubpos(surface);
			}
		}
		
		// Render windows
		for(WLCAbstractWindow window : allWindows) {
			if(window.framebuffer != null) window.framebuffer.freeTexture();
			window.framebuffer = WindowFramebuffer.renderWindow(window);
		}
		
		deleteNonExistingDmabufs(dmabufs(instance));
		
		updateFocusOrder();
		
		// Do client frame callbacks
		sendFrame(instance);
	}
	
	private void updateGeometry(WLCAbstractWindow window) {
		int[] data = surfaceXDGGeometry(window.surface.getHandle());
		SurfaceGeometry geometry;
		
		if(data == null) {
			geometry = new SurfaceGeometry(0, 0, window.surface.width(), window.surface.height());
		}
		else {
			geometry = new SurfaceGeometry(data[0], data[1], data[2], data[3]);
		}
		
		window.geometry = geometry;
	}
	
	private void calculateSubpos(WLCSurface surface) {
		if(surface.parent != null) {
			calculateSubpos(surface.parent);
			surface.xSubpos = surface.parent.xSubpos + surface.xoff;
			surface.ySubpos = surface.parent.ySubpos + surface.yoff;
		}
		else {
			surface.xSubpos = 0;
			surface.ySubpos = 0;
		}
	}
	
	public WLCToplevel[] getToplevels() {
		return toplevels.toArray(new WLCToplevel[toplevels.size()]);
	}
	
	public WLCPopup[] getPopups() {
		return popups.toArray(new WLCPopup[popups.size()]);
	}
	
	public String getSocket() {
		return socket(this.instance);
	}
	
	public boolean inputRegionContains(WLCSurface surface, double x, double y) {
		return checkInputRegion(surface.getHandle(), x, y);
	}
	
	public void sendMotion(WLCSurface surface, double x, double y) {
		pointerMotion(instance, surface.getHandle(), x, y);
	}
	
	public void sendRelativeMotion(double dx, double dy) {
		pointerRelMotion(instance, dx, dy);
	}
	
	public void sendMotionOutside() {
		pointerLeave(instance);
	}
	
	public boolean maybeLockPointer(WLCSurface surface) {
		return maybePointerLock(instance, surface.getHandle());
	}
	
	public void unlockPointer() {
		pointerUnlock(instance);
	}
	
	public void sendButton(int button, int state) {
		pointerButton(instance, button, state);
	}
	
	public void sendScroll(int axis, double value) {
		pointerAxis(instance, axis, value);
	}
	
	public void focusSurface(@Nullable WLCToplevel toplevel) {
		long handle = 0;
		if(toplevel != null) {
			handle = toplevel.getHandle();
		}
		keyboardFocus(instance, handle);
		
		// Make toplevel most recently focused
		if(toplevel != null) {
			focusOrder.remove(toplevel);
			focusOrder.addLast(toplevel);
		}
	}
	
	private void updateFocusOrder() {
		focusOrder.removeIf((t) -> !toplevels.contains(t));
		for(WLCToplevel toplevel : toplevels) {
			if(!focusOrder.contains(toplevel)) focusOrder.addLast(toplevel);
		}
	}
	
	// Find the most recently focused toplevel that exists
	public WLCToplevel getMostRecentFocus() {
		updateFocusOrder();
		return focusOrder.peekLast();
	}
	
	// Find the most recently focused toplevel that exists
	public Stream<WLCToplevel> getMostToLeastRecentFocus() {
		updateFocusOrder();
		return focusOrder.reversed().stream();
	}
	
	public void pressKey(int scancode) {
		keyboardInput(instance, scancode, 1);
	}
	
	public void releaseKey(int scancode) {
		keyboardInput(instance, scancode, 0);
	}
	
	public void internalKeyUpdate(int scancode, boolean pressed) {
		keyboardUpdate(instance, scancode, pressed);
	}
	
	public void resizeToplevelInteractive(WLCToplevel toplevel, int width, int height) {
		toplevelResize(toplevel.getHandle(), width, height, true);
	}
	
	public void resizeToplevel(WLCToplevel toplevel, int width, int height) {
		toplevelResize(toplevel.getHandle(), width, height, false);
	}
	
	public void maximizeToplevel(WLCToplevel toplevel) {
		toplevelMaximize(toplevel.getHandle());
	}
	
	public void fullscreenToplevel(WLCToplevel toplevel) {
		toplevelFullscreen(toplevel.getHandle());
	}
	
	private static native long init(long glfwGetProcAddress, long eglDisplay);
	private static native void update(long instance);
	private static native String socket(long instance);
	private static native void sendFrame(long instance);
	
	private static native void updateSurfaceData(long instance, WLCSurface surface);
	
	private static native long[] toplevels(long instance);
	private static native long toplevelSurface(long instance, long handle);
	private static native String toplevelTitle(long handle);
	private static native String toplevelAppID(long handle);
	// Resize toplevel
	private static native void toplevelResize(long handle, int width, int height, boolean interactive);
	
	// Collect all toplevels that have sent a minimize request and clear the list
	private static native long[] minimizeReq(long instance);
	// Collect all toplevels that have sent a maximize request and clear the list
	private static native long[] maximizeReq(long instance);
	// Collect all toplevels that have sent an unmaximize request and clear the list
	private static native long[] unmaximizeReq(long instance);
	// Collect all toplevels that have sent a fullscreen request and clear the list
	private static native long[] fullscreenReq(long instance);
	// Collect all toplevels that have sent an unfullscreen request and clear the list
	private static native long[] unfullscreenReq(long instance);
	
	private static native void toplevelMaximize(long handle);
	private static native void toplevelFullscreen(long handle);
	
	private static native long[] popups(long instance);
	private static native long popupSurface(long instance, long handle);
	// Query the parent of a popup
	// Returned handle is a handle either to a toplevel or another popup
	private static native long popupParent(long instance, long handle);
	// Query popup local offset coordinates
	// Returns two-element list containing x,y
	private static native int[] popupOffset(long handle);
	
	// Query the xdg_surface window geometry of a toplevel or popup.
	// handle should be the handle to the root WLCSurface
	// Returns four-element array containing x,y,width,height which could be null
	private static native int[] surfaceXDGGeometry(long handle);
	
	private static native long[] dmabufs(long instance);
	
	// Updates the surface tree given by the root surface
	// This changes the doubly linked list of the WLCSurfaces.
	// The returned surface is the last (most deeply nested) child
	private native WLCSurface updateSurfaceTree(WLCSurface root);
	
	// Check if point in surface input region
	private static native boolean checkInputRegion(long surfaceHandle, double x, double y);
	
	// Create pointer motion event
	private static native void pointerMotion(long instance, long handle, double x, double y);
	
	// Send relative pointer motion to surface with pointer focus
	private static native void pointerRelMotion(long instance, double dx, double dy);
	
	private static native boolean maybePointerLock(long instance, long handle);
	
	private static native void pointerUnlock(long instance);
	
	// Remove pointer focus from all surfaces
	private static native void pointerLeave(long instance);
	
	// Create pointer button event. `button` has to be the linux button code, state is 1 for pressed, 0 for released
	private static native void pointerButton(long instance, int button, int state);
	
	// Create pointer axis event. `axis` is the scroll axis (0 for vertical, 1 for horizontal)
	private static native void pointerAxis(long instance, int axis, double value);
	
	// Set keyboard focus to a wayland surface. The handle may be 0 to unfocus any surfaces
	private static native void keyboardFocus(long instance, long surfaceHandle);
	
	// Keyboard input. scancode is the raw keycode. action: 0 is released, 1 is pressed.
	private static native void keyboardInput(long instance, int scancode, int action);
	
	// Update internal key state
	private static native void keyboardUpdate(long instance, int scancode, boolean pressed);
	
	private static native void freeSurface(long instance, long handle);
	private static native void freeToplevel(long instance, long handle);
	private static native void freePopup(long instance, long handle);
	
}
