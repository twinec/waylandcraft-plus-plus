package dev.evvie.waylandcraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCSurface.ViewportSource;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public class WindowFramebuffer {
	
	private static int SHADER = -1;
	private static ResourceLocation SHADER_FRAG_LOC = new ResourceLocation(WaylandCraft.MOD_ID, "shaders/window.fsh");
	private static ResourceLocation SHADER_VERT_LOC = new ResourceLocation(WaylandCraft.MOD_ID, "shaders/window.vsh");
	
	public final WLCAbstractWindow window;
	
	private int tex;
	
	private int width = -1;
	private int height = -1;
	private int xoff = -1;
	private int yoff = -1;
	
	private WindowFramebuffer(WLCAbstractWindow window) {
		this.window = window;
	}
	
	public static WindowFramebuffer renderWindow(WLCAbstractWindow window) {
		WindowFramebuffer buf = new WindowFramebuffer(window);
		buf.init();
		return buf;
	}
	
	private void init() {
		ensureShaderCompiled();
		updateDimensions();
		render();
	}
	
	private void updateDimensions() {
		int minX = 0;
		int minY = 0;
		int maxX = 0;
		int maxY = 0;
		
		for(WLCSurface surface = window.getSurfaceTree(); surface != null; surface = surface.getNextChild()) {
			int sMinX = surface.xSubpos;
			int sMinY = surface.ySubpos;
			int sMaxX = sMinX + surface.width();
			int sMaxY = sMinY + surface.height();
			
			if(sMinX < minX) minX = sMinX;
			if(sMinY < minY) minY = sMinY;
			if(sMaxX > maxX) maxX = sMaxX;
			if(sMaxY > maxY) maxY = sMaxY;
		}
		
		this.xoff = -minX;
		this.yoff = -minY;
		this.width = maxX - minX;
		this.height = maxY - minY;
	}
	
	private void render() {
		tex = GL33.glGenTextures();
		GL33.glBindTexture(GL33.GL_TEXTURE_2D, tex);
		GL33.nglTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA, width, height, 0, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, 0);
		GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_LINEAR);
		GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
		GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
		
		if(width == 0 || height == 0) return;
		
		int fbo = GL33.glGenFramebuffers();
		GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, fbo);
		GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, tex, 0);
		
		int depth_stencil_rbo = GL33.glGenRenderbuffers();
		GL33.glBindRenderbuffer(GL33.GL_RENDERBUFFER, depth_stencil_rbo);
		GL33.glRenderbufferStorage(GL33.GL_RENDERBUFFER, GL33.GL_DEPTH24_STENCIL8, width, height);
		GL33.glBindRenderbuffer(GL33.GL_RENDERBUFFER, 0);
		
		GL33.glFramebufferRenderbuffer(GL33.GL_FRAMEBUFFER, GL33.GL_DEPTH_STENCIL_ATTACHMENT, GL33.GL_RENDERBUFFER, depth_stencil_rbo);
		
		if(GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) != GL33.GL_FRAMEBUFFER_COMPLETE) {
			WaylandCraft.LOGGER.error("Failed to create framebuffer!");
		}
		
		int vaoRestore = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING);
		
		drawSurfaces();
		
		GL33.glBindVertexArray(vaoRestore);
		Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
		
		GL33.glDeleteRenderbuffers(depth_stencil_rbo);
		GL33.glDeleteFramebuffers(fbo);
	}
	
	private void drawSurfaces() {
		GL33.glViewport(0, 0, width, height);
		GL33.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
		GL33.glClear(GL33.GL_COLOR_BUFFER_BIT | GL33.GL_DEPTH_BUFFER_BIT);
		
		GL33.glEnable(GL33.GL_DEPTH_TEST);
		GL33.glDepthFunc(GL33.GL_ALWAYS);
		
		GL33.glEnable(GL33.GL_BLEND);
		GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
		
		int vao = GL33.glGenVertexArrays();
		GL33.glBindVertexArray(vao);
		
		for(WLCSurface surface = window.getSurfaceTree(); surface != null; surface = surface.getNextChild()) {
			renderSurface(surface, xoff + surface.xSubpos, yoff + surface.ySubpos);
		}
		
		GL33.glDeleteVertexArrays(vao);
		GL33.glDisable(GL33.GL_BLEND);
		GL33.glDepthFunc(GL33.GL_LEQUAL);
	}
	
	public void freeTexture() {
		GL33.glDeleteTextures(tex);
	}
	
	private void renderSurface(WLCSurface surface, float x, float y) {
		BufferTexture buf = surface.getBuffer();
		if(buf == null) return;
		
		float w = surface.width();
		float h = surface.height();
		
		float crop_x1 = 0.0f;
		float crop_y1 = 0.0f;
		float crop_x2 = 1.0f;
		float crop_y2 = 1.0f;
		
		ViewportSource src = surface.getViewportSource();
		if(src != null) {
			crop_x1 = (float) (src.x / buf.width);
			crop_y1 = (float) (src.y / buf.height);
			crop_x2 = (float) ((src.x + src.width) / buf.width);
			crop_y2 = (float) ((src.y + src.height) / buf.height);
		}
		
		renderBuffer(buf, x, y, w, h, crop_x1, crop_y1, crop_x2, crop_y2);
	}
	
	private void renderBuffer(BufferTexture buf, float x, float y, float w, float h, float u1, float v1, float u2, float v2) {
		float[] data = new float[] {
				x,     y,     u1, v1,
				x + w, y    , u2, v1,
				x + w, y + h, u2, v2,
				
				x + w, y + h, u2, v2,
				x    , y + h, u1, v2,
				x    , y    , u1, v1,
		};
		
		Matrix4f mat = new Matrix4f().translate(-1.0f, -1.0f, 0.0f).scale(2.0f / width, 2.0f / height, 1.0f);
		
		int vbo = GL33.glGenBuffers();
		GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
		GL33.glBufferData(GL33.GL_ARRAY_BUFFER, data, GL33.GL_STATIC_DRAW);
		
		GL33.glEnableVertexAttribArray(0);
		GL33.glEnableVertexAttribArray(1);
		GL33.nglVertexAttribPointer(0, 2, GL33.GL_FLOAT, false, 4 * Float.BYTES, 0);
		GL33.nglVertexAttribPointer(1, 2, GL33.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		
		GL33.glUseProgram(SHADER);
		GL33.glUniformMatrix4fv(GL33.glGetUniformLocation(SHADER, "transform"), false, mat.get(new float[16]));
		
		GL33.glActiveTexture(GL33.GL_TEXTURE1);
		GL33.glBindTexture(GL33.GL_TEXTURE_2D, buf.id);
		GL33.glUniform1i(GL33.glGetUniformLocation(SHADER, "tex"), 1);
		GL33.glActiveTexture(GL33.GL_TEXTURE0);
		
		GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 6);
		
		GL33.glDisableVertexAttribArray(0);
		GL33.glDisableVertexAttribArray(1);
		GL33.glDeleteBuffers(vbo);
	}
	
	public int getWidth() {
		return width;
	}
	
	public int getHeight() {
		return height;
	}
	
	public int getXOff() {
		return xoff;
	}
	
	public int getYOff() {
		return yoff;
	}
	
	public int getTexture() {
		return tex;
	}
	
	private static void ensureShaderCompiled() {
		if(SHADER == -1) {
			try {
				compileShader();
			} catch (IOException e) {
				e.printStackTrace();
				throw new IllegalStateException("Failed to compile shader: IOException");
			}
		}
	}
	
	private static void compileShader() throws IOException {
		InputStream vertIn = Minecraft.getInstance().getResourceManager().getResource(SHADER_VERT_LOC).get().open();
		String vertCode = new String(vertIn.readAllBytes(), StandardCharsets.UTF_8);
		vertIn.close();
		
		InputStream fragIn = Minecraft.getInstance().getResourceManager().getResource(SHADER_FRAG_LOC).get().open();
		String fragCode = new String(fragIn.readAllBytes(), StandardCharsets.UTF_8);
		fragIn.close();
		
		SHADER = compileShaderProgram(vertCode, fragCode);
	}
	
	private static int compileVertexShader(String code) {
		int vertexShader = GL33.glCreateShader(GL33.GL_VERTEX_SHADER);
		GL33.glShaderSource(vertexShader, code);
		GL33.glCompileShader(vertexShader);
		
		int[] compileStatus = new int[1];
		GL33.glGetShaderiv(vertexShader, GL33.GL_COMPILE_STATUS, compileStatus);
		
		if(compileStatus[0] == GL33.GL_FALSE) {
			String info = GL33.glGetShaderInfoLog(vertexShader);
			GL33.glDeleteShader(vertexShader);
			throw new IllegalStateException("Failed to compile vertex shader:\n" + info);
		}
		
		return vertexShader;
	}
	
	private static int compileFragmentShader(String code) {
		int fragmentShader = GL33.glCreateShader(GL33.GL_FRAGMENT_SHADER);
		GL33.glShaderSource(fragmentShader, code);
		GL33.glCompileShader(fragmentShader);
		
		int[] compileStatus = new int[1];
		GL33.glGetShaderiv(fragmentShader, GL33.GL_COMPILE_STATUS, compileStatus);
		
		if(compileStatus[0] == GL33.GL_FALSE) {
			String info = GL33.glGetShaderInfoLog(fragmentShader);
			GL33.glDeleteShader(fragmentShader);
			throw new IllegalStateException("Failed to compile fragment shader:\n" + info);
		}
		
		return fragmentShader;
	}
	
	private static int compileShaderProgram(String vertexShaderCode, String fragmentShaderCode) {
		int vertexShader = compileVertexShader(vertexShaderCode);
		int fragmentShader = compileFragmentShader(fragmentShaderCode);
		
		int program = GL33.glCreateProgram();
		GL33.glAttachShader(program, vertexShader);
		GL33.glAttachShader(program, fragmentShader);
		GL33.glLinkProgram(program);
		
		GL33.glDeleteShader(vertexShader);
		GL33.glDeleteShader(fragmentShader);
		
		int[] linkStatus = new int[1];
		GL33.glGetProgramiv(program, GL33.GL_LINK_STATUS, linkStatus);
		
		if(linkStatus[0] == GL33.GL_FALSE) {
			String info = GL33.glGetProgramInfoLog(program);
			GL33.glDeleteProgram(program);
			throw new IllegalStateException("Failed to link shader:\n" + info);
		}
		
		return program;
	}
	
}
