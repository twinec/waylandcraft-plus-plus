package dev.evvie.waylandcraft.bridge;

import org.jetbrains.annotations.Nullable;

public class WLCToplevel extends WLCAbstractWindow {
	
	@Nullable
	public String title;
	
	@Nullable
	public String appID;
	
	// Set to true when a toplevel requests to be minimized
	public boolean minimized = false;
	
	public WLCToplevel(long handle) {
		super(handle);
	}
	
}
