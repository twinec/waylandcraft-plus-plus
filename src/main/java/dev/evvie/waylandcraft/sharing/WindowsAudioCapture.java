package dev.evvie.waylandcraft.sharing;

import java.io.InputStream;

/* Audio an application plays on Windows, captured with WASAPI process loopback in the
 * native library (native/src/win32/audio.rs). Covers the process and its children, as
 * browsers play audio from a child process. Needs Windows 10 2004 or newer.
 */
class WindowsAudioCapture extends InputStream {

	private final long handle;
	// Guards the handle against stopCapture() racing with close() on another thread
	private final Object freeLock = new Object();
	private boolean closed = false;

	// Throws if the process can't be captured (or the native library is too old)
	WindowsAudioCapture(int pid, int sampleRate, int channels) {
		this.handle = start(pid, sampleRate, channels);
		if(handle == 0) throw new IllegalStateException("No audio capture handle");
	}

	@Override
	public int read() {
		byte[] one = new byte[1];
		return read(one, 0, 1) == 1 ? Byte.toUnsignedInt(one[0]) : -1;
	}

	// Blocks until the requested bytes are captured; -1 once stopped
	@Override
	public int read(byte[] buffer, int offset, int length) {
		synchronized(freeLock) {
			if(closed) return -1;
		}
		if(length == 0) return 0;
		byte[] data = read(handle, length);
		if(data.length == 0) return -1;
		System.arraycopy(data, 0, buffer, offset, data.length);
		return data.length;
	}

	// Makes a blocked read return; safe to call from any thread
	void stopCapture() {
		synchronized(freeLock) {
			if(!closed) stop(handle);
		}
	}

	// Only from the reading thread, after its last read
	@Override
	public void close() {
		synchronized(freeLock) {
			if(closed) return;
			closed = true;
			free(handle);
		}
	}

	private static native long start(int pid, int sampleRate, int channels);
	private static native byte[] read(long handle, int size);
	private static native void stop(long handle);
	private static native void free(long handle);

}
