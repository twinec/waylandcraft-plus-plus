package dev.evvie.waylandcraft.bridge;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.BufferTexture;

public class WLCSurface {
	
	// Set to zero when this surface no longer exists
	private long handle;
	
	// Used by native code to tag used surfaces
	protected boolean visited;
	
	@Nullable
	private BufferTexture buffer = null;
	
	// Either a child of this surface or one of its siblings
	@Nullable
	protected WLCSurface nextChild = null;
	
	protected long parentHandle = 0;
	
	@Nullable
	protected WLCSurface parent = null;
	
	// X and Y offsets relative to parent coords
	protected int xoff = 0;
	protected int yoff = 0;
	
	// Total calculated offsets
	public int xSubpos = 0;
	public int ySubpos = 0;
	
	protected WLCSurface(long handle) {
		this.handle = handle;
	}
	
	protected long getHandle() {
		return this.handle;
	}
	
	protected long takeHandle() {
		long old = this.handle;
		this.handle = 0;
		return old;
	}
	
	public boolean isAlive() {
		return handle != 0;
	}
	
	protected void attachShmBuffer(long ptr, int width, int height) {
		if(this.buffer != null) {
			this.buffer.release();
		}
		this.buffer = new BufferTexture(ptr, width, height);
	}
	
	@Nullable
	public BufferTexture getBuffer() {
		return this.buffer;
	}
	
	@Nullable
	public WLCSurface getParent() {
		return this.parent;
	}
	
	@Nullable
	public WLCSurface getNextChild() {
		return this.nextChild;
	}
	
}
