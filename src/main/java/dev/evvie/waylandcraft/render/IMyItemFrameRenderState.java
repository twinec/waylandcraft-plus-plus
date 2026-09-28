package dev.evvie.waylandcraft.render;

import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.sharing.SharingNetworking.WindowKey;

public interface IMyItemFrameRenderState {
	
	void setToplevel(WLCToplevel toplevel);
	WLCToplevel getToplevel();
	
	// Another player's shared window shown in this frame, if any
	void setSharedWindow(WindowKey key);
	WindowKey getSharedWindow();
	
}
