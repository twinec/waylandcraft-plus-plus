package dev.evvie.waylandcraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public class XDGDesktopManager {
	
	private HashMap<String, String> nameCache = new HashMap<String, String>();
	private HashMap<String, ResourceLocation> iconCache = new HashMap<String, ResourceLocation>();
	
	public String getName(String appID) {
		if(appID == null) return null;
		
		if(nameCache.containsKey(appID)) {
			return nameCache.get(appID);
		}
		
		String name = WaylandCraft.instance.bridge.resolveName(appID);
		nameCache.put(appID, name);
		return name;
	}
	
	public ResourceLocation getIcon(String appID) {
		if(appID == null) return null;
		
		if(iconCache.containsKey(appID)) {
			return iconCache.get(appID);
		}
		
		ResourceLocation location = null;
		IconTexture icon = tryRetrieveIcon(appID);
		if(icon != null) {
			TextureManager textureManager = Minecraft.getInstance().getTextureManager();
			location = new ResourceLocation(WaylandCraft.MOD_ID, "icon_" + appID);
			
			textureManager.register(location, icon);
		}
		
		iconCache.put(appID, location);
		return location;
	}
	
	private IconTexture tryRetrieveIcon(String appID) {
		try {
			return retrieveIcon(appID);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private String getExtension(File file) {
		String path = file.getAbsolutePath();
		int idx = path.lastIndexOf('.');
		if(idx < 0 || idx >= path.length() - 1) return "";
		
		return path.substring(idx + 1);
	}
	
	private IconTexture retrieveIcon(String appID) throws IOException {
		String iconPath = WaylandCraft.instance.bridge.resolveIconPath(appID);
		System.out.println("Found icon path: " + iconPath);
		
		if(iconPath == null) return null;
		
		File iconFile = new File(iconPath);
		
		/* This "file type check" is valid because according to the Icon Theme Specification
		 * the extension has to be one of ".png", ".xpm" and ".svg" (lowercase) and the extension
		 * signals what type of file we should expect.
		 */
		if(!getExtension(iconFile).equals("png")) {
			System.err.println("Icon is not PNG!");
			return null;
		}
		
		return new IconTexture(iconFile);
	}
	
	public static class IconTexture extends AbstractTexture {
		
		private final NativeImage image;
		
		public IconTexture(File file) throws IOException {
			FileInputStream stream = new FileInputStream(file);
			image = NativeImage.read(stream);
			TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
			image.upload(0, 0, 0, false);
		}
		
		@Override
		public void load(ResourceManager resourceManager) throws IOException {
		}
		
		@Override
		public void close() {
			if(image != null) {
				image.close();
				releaseId();
			}
		}
		
	}
	
}
