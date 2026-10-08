package dev.evvie.waylandcraft.render;

import java.nio.ByteBuffer;
import java.util.Optional;

import org.joml.Vector4f;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTQueueFamilyForeign;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceLayers;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.dmabuf.Dmabuf;
import dev.evvie.waylandcraft.egl.EGL;
import dev.evvie.waylandcraft.egl.EGLHelper;
import dev.evvie.waylandcraft.mixin.IGlDeviceMixin;
import dev.evvie.waylandcraft.mixin.IGlTextureMixin;
import dev.evvie.waylandcraft.mixin.IGpuDeviceMixin;
import dev.evvie.waylandcraft.mixin.IVulkanCommandEncoderMixin;
import dev.evvie.waylandcraft.vulkan.VulkanHelper;
import dev.evvie.waylandcraft.vulkan.VulkanHelper.ImportedDmabufVulkan;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public abstract class BufferTexture {
	
	public static final int FORMAT_ARGB8888 = 0;
	public static final int FORMAT_XRGB8888 = 1;
	
	public final int width;
	public final int height;
	public final int format;
	
	public BufferTexture(int width, int height, int format) {
		this.width = width;
		this.height = height;
		this.format = format;
	}
	
	public abstract GpuTextureView getTextureView();
	public abstract void release();
	
	public static BufferTexture createShmTexture(long ptr, int width, int height, int format, int stride) {
		GpuDeviceBackend deviceBackend = ((IGpuDeviceMixin) RenderSystem.getDevice()).getBackend();
		if(deviceBackend instanceof IGlDeviceMixin) {
			return new GlShmBufferTexture(ptr, width, height, format, stride);
		}
		else if(deviceBackend instanceof VulkanDevice) {
			return new VulkanShmBufferTexture(ptr, width, height, format, stride);
		}
		
		throw new RuntimeException("Unsupported backed");
	}
	
	public static BufferTexture createSinglePixelTexture(byte r, byte g, byte b, byte a) {
		return new SinglePixelBufferTexture(r, g, b, a);
	}
	
	public static DmabufTexture createDmabufTexture(Dmabuf dmabuf) throws DmabufImportFailedException {
		GpuDeviceBackend deviceBackend = ((IGpuDeviceMixin) RenderSystem.getDevice()).getBackend();
		if(deviceBackend instanceof IGlDeviceMixin) {
			return new GlDmabufTexture(dmabuf);
		}
		else if(deviceBackend instanceof VulkanDevice) {
			return new VulkanDmabufTexture(dmabuf);
		}
		
		throw new RuntimeException("Unsupported backed");
	}
	
	private static class SinglePixelBufferTexture extends BufferTexture {
		
		private GpuTexture texture;
		private GpuTextureView textureView = null;
		
		private SinglePixelBufferTexture(byte r, byte g, byte b, byte a) {
			super(1, 1, FORMAT_ARGB8888);
			
			texture = RenderSystem.getDevice().createTexture("buffertexture-" + this.hashCode(), GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			textureView = RenderSystem.getDevice().createTextureView(texture);
			
			float c1 = Byte.toUnsignedInt(r) / 255.0f;
			float c2 = Byte.toUnsignedInt(g) / 255.0f;
			float c3 = Byte.toUnsignedInt(b) / 255.0f;
			float c4 = Byte.toUnsignedInt(a) / 255.0f;
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f(c1, c2, c3, c4));
		}
		
		@Override
		public GpuTextureView getTextureView() {
			return textureView;
		}
		
		@Override
		public void release() {
			textureView.close();
			texture.close();
			textureView = null;
		}
		
	}
	
	private static abstract class GlBasicBufferTexture extends BufferTexture {
		
		public final int id;
		private GlTexture texture;
		private GpuTextureView textureView;
		
		private GlBasicBufferTexture(int width, int height, int format) {
			super(width, height, format);
			this.id = GlStateManager._genTexture();
			
			texture = IGlTextureMixin.createTexture(GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, "buffertexture-" + this.hashCode(), GpuFormat.RGBA8_UINT, width, height, 1, 1, id, ((IGlDeviceMixin) ((IGpuDeviceMixin) RenderSystem.getDevice()).getBackend()).invokeFrameBufferCache());
			textureView = RenderSystem.getDevice().createTextureView(texture);
		}
		
		@Override
		public GpuTextureView getTextureView() {
			return textureView;
		}
		
		@Override
		public void release() {
			textureView.close();
			texture.close();
			textureView = null;
		}
		
	}
	
	private static class GlShmBufferTexture extends GlBasicBufferTexture {
		
		private final long ptr;
		private final int stride;
		
		private GlShmBufferTexture(long ptr, int width, int height, int format, int stride) {
			super(width, height, format);
			this.ptr = ptr;
			this.stride = stride;
			
			init();
		}
		
		private void init() {
			// Bind directly: the texture name is often one that was just deleted and reused,
			// which GlStateManager's binding cache would wrongly treat as already bound. The
			// previous bindings are restored afterwards, so the cache stays correct.
			int previousTexture = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
			int previousUnpackBuffer = GL33.glGetInteger(GL33.GL_PIXEL_UNPACK_BUFFER_BINDING);
			GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, 0);
			GL33.glBindTexture(GL33.GL_TEXTURE_2D, this.id);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAX_LEVEL, 0);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_LOD, 0);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAX_LOD, 0);
			
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_LINEAR);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
			
			GlStateManager._pixelStore(GL33.GL_UNPACK_ROW_LENGTH, stride / 4);
			GlStateManager._pixelStore(GL33.GL_UNPACK_SKIP_PIXELS, 0);
			GlStateManager._pixelStore(GL33.GL_UNPACK_SKIP_ROWS, 0);
			GlStateManager._pixelStore(GL33.GL_UNPACK_ALIGNMENT, 4);
			
			GL33.nglTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA8, width, height, 0, GL33.GL_BGRA, GL33.GL_UNSIGNED_INT_8_8_8_8_REV, this.ptr);
			
			GL33.glBindTexture(GL33.GL_TEXTURE_2D, previousTexture);
			GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
		}
		
	}
	
	private static class VulkanShmBufferTexture extends BufferTexture {
		
		private VulkanGpuTexture texture;
		private GpuTextureView textureView;
		
		public VulkanShmBufferTexture(long ptr, int width, int height, int format, int stride) {
			super(width, height, format);
			
			ScopedValue.where(VulkanHelper.VULKAN_GPU_TEXTURE_CREATE_FORMAT_OVERRIDE, VK12.VK_FORMAT_B8G8R8A8_UNORM).run(() -> {
				texture = (VulkanGpuTexture) RenderSystem.getDevice().createTexture("buffertexture-" + this.hashCode(), GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
				textureView = RenderSystem.getDevice().createTextureView(texture);
			});
			
			writeTextureData(ptr, width, height, stride);
		}
		
		private void writeTextureData(long ptr, int width, int height, int stride) {
			int uploadSize = stride * height;
			ByteBuffer buf = MemoryUtil.memByteBuffer(ptr, uploadSize);
			VulkanCommandEncoder commandEncoder = (VulkanCommandEncoder) ((VulkanDevice) ((IGpuDeviceMixin) RenderSystem.getDevice()).getBackend()).createCommandEncoder();
			
			GpuBufferSlice stagingBuffer = commandEncoder.transientMemory().uploadStaging(buf, 1L, GpuBuffer.USAGE_COPY_SRC);
			try(MemoryStack stack = MemoryStack.stackPush()) {
				VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
				region.bufferOffset(stagingBuffer.offset());
				region.bufferRowLength(stride / 4);
				region.bufferImageHeight(height);
				VkImageSubresourceLayers imageSubresource = region.imageSubresource();
				imageSubresource.aspectMask(1);
				imageSubresource.mipLevel(0);
				imageSubresource.baseArrayLayer(0);
				imageSubresource.layerCount(1);
				region.imageOffset().set(0, 0, 0);
				region.imageExtent().set(width, height, 1);
				VK12.vkCmdCopyBufferToImage(((IVulkanCommandEncoderMixin) commandEncoder).invokeCommandBuffer(), ((VulkanGpuBuffer) stagingBuffer.buffer()).vkBuffer(), texture.vkImage(), 1, region);
				((IVulkanCommandEncoderMixin) commandEncoder).invokeMemoryBarrier(stack);
			}
		}
		
		@Override
		public GpuTextureView getTextureView() {
			return textureView;
		}
		
		@Override
		public void release() {
			textureView.close();
			texture.close();
			textureView = null;
		}
		
	}
	
	public static final RenderPipeline DMABUF_BLIT = RenderPipelines.register(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/dmabuf_blit"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()
	);
	
	public static abstract class DmabufTexture extends BufferTexture {
		
		public final long handle;
		protected RenderTarget target;
		protected GpuTexture internalTexture = null;
		protected GpuTextureView internalView = null;
		
		private DmabufTexture(Dmabuf buf) throws DmabufImportFailedException {
			super(buf.width(), buf.height(), BufferTexture.FORMAT_ARGB8888);
			this.handle = buf.handle();
			
			target = new TextureTarget("dmabuf-target-" + this.hashCode(), width, height, GpuFormat.RGBA8_UNORM, null);
		}
		
		// Destroys internal data
		public abstract void doFree();
		
		@Override
		public GpuTextureView getTextureView() {
			if(target == null) return null;
			return target.getColorTextureView();
		}
		
		public void copyData() {
			if(internalTexture == null) return;
			
			try(RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Dmabuf blit", target.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))) {
				renderPass.setPipeline(RenderSystem.getCompiledPipeline(DMABUF_BLIT));
				RenderSystem.bindDefaultUniforms(renderPass);
				renderPass.setUniform("InSampler", internalView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				renderPass.draw(3, 1, 0, 0);
			}
		}
		
		public void doReleaseTexure() {
			target.destroyBuffers();
			target = null;
		}
		
		@Override
		public void release() {
			// Don't release texture id as dmabuf textures might get reused
		}
		
	}
	
	public static class DmabufImportFailedException extends Exception {
	}
	
	private static class GlDmabufTexture extends DmabufTexture {
		
		private final long eglImage;
		private int eglImageTex = -1;
		
		private GlDmabufTexture(Dmabuf buf) throws DmabufImportFailedException {
			super(buf);
			
			long dpy = EGL.getEGLDisplay();
			eglImage = EGLHelper.importDmabufToImage(dpy, buf);
			if(eglImage == EGL.EGL_NO_IMAGE) {
				WaylandCraftCommon.LOGGER.error("Failed to import dmabuf! EGL error: " + EGL.eglGetErrorString());
				throw new DmabufImportFailedException();
			}
			
			init();
			copyData();
		}
		
		private void init() {
			/* Create texture for EGLImage */
			eglImageTex = GlStateManager._genTexture();
			GlStateManager._bindTexture(eglImageTex);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAX_LEVEL, 0);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_LOD, 0);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAX_LOD, 0);
			
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_LINEAR);
			GlStateManager._texParameter(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
			
			long glEGLImageTargetTexture2DOES = SDLVideo.SDL_GL_GetProcAddress("glEGLImageTargetTexture2DOES");
			JNI.invokeJV(GL33.GL_TEXTURE_2D, eglImage, glEGLImageTargetTexture2DOES);
			
			GlTexture glTexture = IGlTextureMixin.createTexture(GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, "eglimage-" + this.hashCode(), GpuFormat.RGBA8_UINT, width, height, 1, 1, eglImageTex, ((IGlDeviceMixin) ((IGpuDeviceMixin) RenderSystem.getDevice()).getBackend()).invokeFrameBufferCache());
			internalTexture = glTexture;
			internalView = RenderSystem.getDevice().createTextureView(internalTexture);
		}

		@Override
		public void doFree() {
			if(internalTexture == null) return;

			long dpy = EGL.getEGLDisplay();
			EGL.eglDestroyImage(dpy, eglImage);

			GlStateManager._deleteTexture(eglImageTex);
			internalView.close();
			internalTexture = null;
		}
		
	}
	
	private static class DmabufOverrideVulkanGpuTexture extends VulkanGpuTexture {
		
		private ImportedDmabufVulkan importedDmabuf;
		
		public DmabufOverrideVulkanGpuTexture(VulkanDevice device, Dmabuf dmabuf, ImportedDmabufVulkan importedDmabuf) {
			super(device, GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, String.format("dmabuf-0x%016X", dmabuf.handle()), GpuFormat.RGBA8_UNORM, dmabuf.width(), dmabuf.height(), 1, 1);
			this.importedDmabuf = importedDmabuf;
		}
		
		@Override
		public void destroy() {
			// DO NOT CALL VulkanGpuTexture::destroy
			VulkanDevice device = VulkanHelper.getVulkanDevice();
			VulkanHelper.destroyImportedDmabuf(device, importedDmabuf);
		}
		
	}
	
	private static class VulkanDmabufTexture extends DmabufTexture {
		
		private ImportedDmabufVulkan importedDmabuf;
		
		public VulkanDmabufTexture(Dmabuf buf) throws DmabufImportFailedException {
			super(buf);
			
			VulkanDevice device = VulkanHelper.getVulkanDevice();
			importedDmabuf = VulkanHelper.importDmabuf(device, buf);
			if(importedDmabuf == null) {
				throw new DmabufImportFailedException();
			}
			
			ScopedValue
					.where(VulkanHelper.VULKAN_GPU_TEXTURE_IMAGE_CREATE_OVERRIDE, importedDmabuf.vkImage())
					.where(VulkanHelper.VULKAN_GPU_TEXTURE_CREATE_FORMAT_OVERRIDE, importedDmabuf.vkFormat())
					.where(VulkanHelper.VULKAN_GPU_TEXTURE_IMAGE_BARRIER_QUEUE_OVERRIDE, IntIntImmutablePair.of(device.graphicsQueue().queueFamilyIndex(), EXTQueueFamilyForeign.VK_QUEUE_FAMILY_FOREIGN_EXT))
					.run(() -> {
				this.internalTexture = new DmabufOverrideVulkanGpuTexture(device, buf, importedDmabuf);
				this.internalView = RenderSystem.getDevice().createTextureView(internalTexture);
			});
			
//			copyData();
		}
		
		@Override
		public void copyData() {
			VulkanDevice device = VulkanHelper.getVulkanDevice();
			
			WaylandCraft.instance.bridge.syncStartDmabufRead(this.handle);
			
			super.copyData();
			
			VulkanHelper.acquireDmabufTexture(device, importedDmabuf);
			VulkanHelper.releaseDmabufTexture(device, importedDmabuf);
			
			RenderSystem.queueFencedTask(() -> {
				WaylandCraft.instance.bridge.syncEndDmabufRead(this.handle);
			});
		}
		
		@Override
		public void doFree() {
			if(internalTexture == null) return;
			
			internalView.close();
			internalTexture.close();
			internalTexture = null;
		}
		
	}
	
}
