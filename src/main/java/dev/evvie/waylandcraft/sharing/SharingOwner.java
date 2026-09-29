package dev.evvie.waylandcraft.sharing;

import java.util.Optional;
import org.joml.Vector4f;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.displays.WindowDisplay;
import dev.evvie.waylandcraft.render.WindowFramebuffer;
import dev.evvie.waylandcraft.sharing.SharingNetworking.AudioChunkPayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.DemandPayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.DisplayPosePayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.FrameInfo;
import dev.evvie.waylandcraft.sharing.SharingNetworking.KeyFrameRequestPayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.ShareStatePayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.VideoChunkPayload;
import dev.evvie.waylandcraft.sharing.SharingNetworking.WindowPose;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/* Owner side of window sharing. The player explicitly toggles sharing per window
 * (keybind, focused window). Shared windows are only captured while the server
 * reports demand: video while someone can see the window, audio while someone
 * is near it.
 *
 * Video is tuned for both moving content (videos, games) and text (terminals):
 * while the window keeps changing, frames are sent as H.264 (see H264Codec) at a
 * moderate size and up to MOTION_FPS; once it has been still for STILL_DELAY_MILLIS,
 * one lossless PNG at a higher resolution follows, so text becomes crisp. Nothing is
 * captured while the window content doesn't change.
 */
public class SharingOwner {

	private static final int MOTION_FPS = 15;
	private static final int MOTION_MAX_DIMENSION = 854;
	// A key frame every this many motion frames bounds how long a viewer who missed frames waits
	private static final int KEY_FRAME_INTERVAL = 30;

	private static final long STILL_DELAY_MILLIS = 600;
	private static final int STILL_MAX_DIMENSION = 1600;

	private static final long POSE_RESEND_MILLIS = 2000;

	private final WaylandCraft wlc;
	private final Map<Long, SharedWindow> shared = new HashMap<>();
	private final ExecutorService encoder = Executors.newSingleThreadExecutor((r) -> {
		Thread t = new Thread(r, "WaylandCraft sharing encoder");
		t.setDaemon(true);
		return t;
	});

	// Downscales a window framebuffer into its capture target. The framebuffer holds straight
	// alpha; blending is off, so the color channels are copied as they are and alpha is ignored.
	private static final RenderPipeline CAPTURE_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/share_capture"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()
	);

	private class SharedWindow {
		final WLCToplevel toplevel;
		// Wayland client pid, read on the main thread (the compositor is not thread-safe)
		final int clientPid;
		boolean videoDemand = false;
		boolean audioDemand = false;

		long lastFrameTime = 0;
		long lastChangeTime = 0;
		// Content version of the last frame sent, and whether it was the lossless still
		long sentVersion = -1;
		boolean stillSent = false;
		int frameId = 0;
		boolean encoding = false;

		// H.264 state. The encoder itself is only touched on the encoder thread.
		H264Codec.FrameEncoder encoder = null;
		boolean forceKeyFrame = true;
		int framesSinceKey = 0;
		int encodedWidth = 0;
		int encodedHeight = 0;

		AudioCapture audio = null;

		// Per window, so a pending readback is never overwritten or resized by another capture
		TextureTarget captureTarget = null;

		WindowPose sentPose = null;
		boolean poseSent = false;
		long lastPoseTime = 0;

		SharedWindow(WLCToplevel toplevel, int clientPid) {
			this.toplevel = toplevel;
			this.clientPid = clientPid;
		}
	}

	public SharingOwner(WaylandCraft wlc) {
		this.wlc = wlc;
	}

	public boolean isShared(WLCToplevel toplevel) {
		return shared.containsKey(toplevel.getHandle());
	}

	// Titles of shared windows, for the HUD
	public List<String> sharedTitles() {
		return shared.values().stream().map((window) -> title(window.toplevel)).toList();
	}

	// Keybind shortcut: toggles sharing of the most recently focused window
	public void toggleFocused() {
		if(wlc.bridge == null) return;
		WLCToplevel toplevel = wlc.bridge.getMostRecentFocus();
		if(toplevel == null) {
			message("No window focused to share");
			return;
		}
		toggle(toplevel);
	}

	// Explicit per-window choice, from the window manager screen or the keybind
	public void toggle(WLCToplevel toplevel) {
		if(wlc.bridge == null || !toplevel.isAlive()) return;

		String name = title(toplevel);
		if(shared.containsKey(toplevel.getHandle())) {
			stop(toplevel.getHandle());
			message("Stopped sharing " + name);
		}
		else {
			if(!ClientPlayNetworking.canSend(ShareStatePayload.TYPE)) {
				message("This server doesn't have WaylandCraft window sharing");
				return;
			}
			shared.put(toplevel.getHandle(), new SharedWindow(toplevel, wlc.bridge.getToplevelPID(toplevel)));
			SharingNetworking.sendToServer(new ShareStatePayload(toplevel.getHandle(), true));
			message("Sharing " + name + " (video and audio) with other players who can see it in an item frame or where you place it. Stop it from the window manager or with the share key.");
		}
	}

	public static String title(WLCToplevel toplevel) {
		if(toplevel.title != null && !toplevel.title.isBlank()) return toplevel.title;
		if(toplevel.appID != null) return toplevel.appID;
		return "window";
	}

	public void onDemand(DemandPayload payload) {
		SharedWindow window = shared.get(payload.handle());
		if(window == null) return;
		if(payload.video() && !window.videoDemand) requestKeyFrame(window);
		window.videoDemand = payload.video();
		window.audioDemand = payload.audio();
	}

	public void onKeyFrameRequest(KeyFrameRequestPayload payload) {
		SharedWindow window = shared.get(payload.handle());
		if(window != null) requestKeyFrame(window);
	}

	// New viewers need a frame they can decode on their own, even if the window is idle
	private void requestKeyFrame(SharedWindow window) {
		window.forceKeyFrame = true;
		window.sentVersion = -1;
		window.stillSent = false;
	}

	public void reset() {
		for(Long handle : shared.keySet().toArray(Long[]::new)) stop(handle);
	}

	// Client tick: forget closed windows, start / stop audio capture with demand, sync floating poses
	public void tick() {
		Iterator<Map.Entry<Long, SharedWindow>> it = shared.entrySet().iterator();
		while(it.hasNext()) {
			SharedWindow window = it.next().getValue();
			if(!window.toplevel.isAlive()) {
				release(window);
				SharingNetworking.sendToServer(new ShareStatePayload(window.toplevel.getHandle(), false));
				it.remove();
				continue;
			}

			updateAudio(window);
			updatePose(window);
		}
	}

	private void updateAudio(SharedWindow window) {
		if(!window.audioDemand) {
			if(window.audio != null) window.audio.stop();
			window.audio = null;
			return;
		}

		if(window.audio == null) {
			long handle = window.toplevel.getHandle();
			String x11Display = wlc.x11Display;
			window.audio = new AudioCapture(() -> AudioCapture.resolveX11Pid(window.clientPid, x11Display, window.toplevel.title), (opus, timestamp) ->
				Minecraft.getInstance().execute(() -> SharingNetworking.sendToServer(new AudioChunkPayload(handle, timestamp, opus))));
		}
		window.audio.update();
	}

	// Free-floating displays of shared windows are placed for other players too
	private void updatePose(SharedWindow window) {
		WindowDisplay display = wlc.getDisplay(window.toplevel);
		WindowFramebuffer framebuffer = window.toplevel.framebuffer;
		WindowPose pose = null;
		if(display != null && display.isValid() && framebuffer != null && framebuffer.isValid()) {
			// The shared image is the whole framebuffer (including client-side decorations
			// outside the window geometry), so describe that rectangle
			Vec3 localX = display.localX();
			Vec3 localY = display.localY();
			Vec3 rectOrigin = display.origin()
				.add(localX.scale(-framebuffer.getXOff() - window.toplevel.geometry.x()))
				.add(localY.scale(-framebuffer.getYOff() - window.toplevel.geometry.y()));
			Vec3 center = rectOrigin.add(localX.scale(framebuffer.getWidth() / 2.0)).add(localY.scale(framebuffer.getHeight() / 2.0));
			pose = new WindowPose(center, display.normal(), display.down(),
				(float) localX.length() * framebuffer.getWidth(), (float) localY.length() * framebuffer.getHeight());
		}

		long now = System.currentTimeMillis();
		boolean changed = !window.poseSent || !Objects.equals(pose, window.sentPose);
		boolean resend = pose != null && now - window.lastPoseTime > POSE_RESEND_MILLIS;
		if(!changed && !resend) return;

		window.sentPose = pose;
		window.poseSent = true;
		window.lastPoseTime = now;
		SharingNetworking.sendToServer(new DisplayPosePayload(window.toplevel.getHandle(), pose));
	}

	// Render thread, after the bridge rendered window framebuffers
	public void captureFrames() {
		long now = System.currentTimeMillis();
		for(SharedWindow window : shared.values()) {
			WindowFramebuffer framebuffer = window.toplevel.framebuffer;
			if(framebuffer == null || !framebuffer.isValid()) continue;

			long version = framebuffer.getContentVersion();

			if(!window.videoDemand || window.encoding) continue;

			if(version != window.sentVersion) {
				// Content changed: send a motion frame, rate limited
				if(now - window.lastFrameTime < 1000 / MOTION_FPS) continue;
				window.lastFrameTime = now;
				window.lastChangeTime = now;
				window.sentVersion = version;
				window.stillSent = false;
				capture(window, framebuffer, false);
			}
			else if(!window.stillSent && now - window.lastChangeTime >= STILL_DELAY_MILLIS) {
				// Unchanged for a while: follow up with one sharp lossless frame
				window.stillSent = true;
				capture(window, framebuffer, true);
			}
		}
	}

	private void capture(SharedWindow window, WindowFramebuffer framebuffer, boolean still) {
		// Downscale on the GPU, then read back the small image. Even sizes for 4:2:0 chroma.
		int maxDimension = still ? STILL_MAX_DIMENSION : MOTION_MAX_DIMENSION;
		float scale = Math.min(1.0f, (float) maxDimension / Math.max(framebuffer.getWidth(), framebuffer.getHeight()));
		int width = Math.max(2, Math.round(framebuffer.getWidth() * scale) & ~1);
		int height = Math.max(2, Math.round(framebuffer.getHeight() * scale) & ~1);

		if(window.captureTarget == null || window.captureTarget.width != width || window.captureTarget.height != height) {
			// Safe to replace: captures for this window wait until its previous readback finished
			if(window.captureTarget != null) window.captureTarget.destroyBuffers();
			window.captureTarget = new TextureTarget("waylandcraft window share capture", width, height, GpuFormat.RGBA8_UNORM, null);
		}
		TextureTarget target = window.captureTarget;

		var device = RenderSystem.getDevice();
		var commands = device.createCommandEncoder();
		try(RenderPass pass = commands.createRenderPass(() -> "window share capture", target.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 1)))) {
			pass.setPipeline(CAPTURE_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", framebuffer.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		long timestamp = System.currentTimeMillis();

		byte kind;
		if(still) {
			kind = SharingNetworking.FRAME_STILL;
		}
		else {
			boolean resized = width != window.encodedWidth || height != window.encodedHeight;
			boolean key = window.forceKeyFrame || resized || window.framesSinceKey >= KEY_FRAME_INTERVAL;
			kind = key ? SharingNetworking.FRAME_KEY : SharingNetworking.FRAME_DELTA;
			window.forceKeyFrame = false;
			window.framesSinceKey = key ? 0 : window.framesSinceKey + 1;
			window.encodedWidth = width;
			window.encodedHeight = height;
		}

		// Read back asynchronously, like vanilla screenshots; works on every graphics backend.
		// Rows come back in texture order, and texture row 0 holds the window's top row.
		int size = width * height * 4;
		GpuBuffer readback = device.createBuffer(() -> "waylandcraft window share readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
		window.encoding = true;
		long handle = window.toplevel.getHandle();
		int frameId = ++window.frameId;
		commands.copyTextureToBuffer(target.getColorTexture(), readback, 0, () -> {
			ByteBuffer pixels = MemoryUtil.memAlloc(size);
			try(GpuBufferSlice.MappedView view = readback.map(true, false)) {
				MemoryUtil.memCopy(MemoryUtil.memAddress(view.data()), MemoryUtil.memAddress(pixels), size);
			}
			readback.close();
			encode(window, handle, frameId, kind, width, height, timestamp, pixels, still);
		}, 0);
	}

	private void encode(SharedWindow window, long handle, int frameId, byte kind, int width, int height, long timestamp, ByteBuffer pixels, boolean still) {
		encoder.execute(() -> {
			try {
				byte[] encoded;
				if(still) {
					encoded = encodePng(pixels, width, height);
				}
				else {
					if(window.encoder == null || !window.encoder.matches(width, height)) {
						if(window.encoder != null) window.encoder.close();
						window.encoder = H264Codec.createEncoder(width, height);
					}
					encoded = window.encoder.encode(pixels, kind == SharingNetworking.FRAME_KEY);
				}
				Minecraft.getInstance().execute(() -> {
					window.encoding = false;
					if(!shared.containsKey(handle)) {
						releaseCaptureTarget(window);
						return;
					}
					if(!sendFrame(handle, frameId, kind, width, height, timestamp, encoded) && kind != SharingNetworking.FRAME_STILL) {
						// A dropped H.264 frame breaks the chain for everyone
						window.forceKeyFrame = true;
					}
				});
			} catch(IOException e) {
				WaylandCraftCommon.LOGGER.error("Failed to encode shared window frame", e);
				Minecraft.getInstance().execute(() -> {
					window.encoding = false;
					if(!shared.containsKey(handle)) releaseCaptureTarget(window);
				});
			} finally {
				MemoryUtil.memFree(pixels);
			}
		});
	}

	// glReadPixels returns the target's row 0 first, which holds the window's top row
	private static byte[] encodePng(ByteBuffer pixels, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[width];
		for(int y = 0; y < height; y++) {
			for(int x = 0; x < width; x++) {
				int i = (y * width + x) * 4;
				int r = pixels.get(i) & 0xff;
				int g = pixels.get(i + 1) & 0xff;
				int b = pixels.get(i + 2) & 0xff;
				row[x] = r << 16 | g << 8 | b;
			}
			image.setRGB(0, y, width, 1, row, 0, width);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	// Returns false if the frame was too large to send
	private static boolean sendFrame(long handle, int frameId, byte kind, int width, int height, long timestamp, byte[] encoded) {
		int chunkSize = SharingNetworking.MAX_CHUNK_BYTES;
		int count = Math.max(1, (encoded.length + chunkSize - 1) / chunkSize);
		if(count > SharingNetworking.MAX_CHUNKS_PER_FRAME) return false;

		for(int i = 0; i < count; i++) {
			byte[] data = Arrays.copyOfRange(encoded, i * chunkSize, Math.min(encoded.length, (i + 1) * chunkSize));
			FrameInfo info = new FrameInfo(frameId, kind, width, height, timestamp, i, count);
			SharingNetworking.sendToServer(new VideoChunkPayload(handle, info, data));
		}
		return true;
	}

	private void stop(long handle) {
		SharedWindow window = shared.remove(handle);
		if(window == null) return;
		release(window);
		SharingNetworking.sendToServer(new ShareStatePayload(handle, false));
	}

	private void release(SharedWindow window) {
		if(window.audio != null) window.audio.stop();
		// With a readback in flight, the capture target is freed once it completes instead
		if(!window.encoding) releaseCaptureTarget(window);
		// The encoder belongs to the encoder thread
		encoder.execute(() -> {
			if(window.encoder != null) window.encoder.close();
			window.encoder = null;
		});
	}

	// Render thread
	private static void releaseCaptureTarget(SharedWindow window) {
		if(window.captureTarget != null) window.captureTarget.destroyBuffers();
		window.captureTarget = null;
	}

	private static void message(String text) {
		Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal("[WaylandCraft] " + text), false);
	}

}
