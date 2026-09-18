package dev.evvie.waylandcraft.settings;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.WaylandCraftCommon;

/* Settings for waylandcraft
 * 
 * All of the fields here that do not have the "transient" modifier are written to the settings.json!
 * Because of that and because these fields are also accessed using reflection, just don't change their names,
 * unless you also update all the references and are okay with user settings breaking across updates.
 */
public class WaylandCraftSettings {
	
	/* The settings fields shouldn't have a modifier so that they're package-private.
	 * This avoids code directly setting the values.
	 */
	
	int pixelsPerBlock = 500;
	boolean focusOnHover = false;
	String terminalChoice = "";

	/* Environment variables passed to launched apps (see XDGSpecHelper#exec_app's
	 * defaults, natively), keyed by name. An entry here overrides the matching
	 * native default, or is passed through as a brand new variable if it doesn't
	 * match one. Not one of the SETTINGS below since it isn't a single scalar
	 * value editable through a SettingsWidget row -- see WaylandCraftSettingsManager
	 * for how it's applied.
	 */
	Map<String, String> envOverrides = new HashMap<String, String>();

	/* This is where the field names go to avoid typos */
	public static final String PIXELS_PER_BLOCK = "pixelsPerBlock";
	public static final String FOCUS_ON_HOVER = "focusOnHover";
	public static final String TERMINAL_CHOICE = "terminalChoice";

	public static final String[] SETTINGS = new String[] {
			PIXELS_PER_BLOCK,
			FOCUS_ON_HOVER,
			TERMINAL_CHOICE
	};

	/* This is where the getters go */

	public int getPixelsPerBlock() {
		return pixelsPerBlock;
	}

	public boolean getFocusOnHover() {
		return focusOnHover;
	}

	public String getTerminalChoice() {
		return terminalChoice;
	}

	public Map<String, String> getEnvOverrides() {
		return envOverrides;
	}
	
	/* Methods to modifiy settings by name */
	
	protected void setIntSetting(String name, int value) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			field.setInt(this, value);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as int!");
			e.printStackTrace();
		}
	}
	
	protected void setBooleanSetting(String name, boolean value) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			field.setBoolean(this, value);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as boolean!");
			e.printStackTrace();
		}
	}
	
	protected void setTextSetting(String name, String value) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			field.set(this, value);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as text!");
			e.printStackTrace();
		}
	}
	
	protected @Nullable Object getSettingAnyType(String name) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			return field.get(this);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "'!");
			e.printStackTrace();
		}
		return null;
	}
	
	// Get int setting. Returns null only when setting was not found.
	protected @Nullable Integer getIntSetting(String name) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			return field.getInt(this);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as int!");
			e.printStackTrace();
		}
		return null;
	}
	
	// Get boolean setting. Returns null only when setting was not found.
	protected @Nullable Boolean getBooleanSetting(String name) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			return field.getBoolean(this);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as boolean!");
			e.printStackTrace();
		}
		return null;
	}
	
	// Get text setting. Returns null only when setting was not found.
	protected @Nullable String getTextSetting(String name) {
		try {
			Field field = WaylandCraftSettings.class.getDeclaredField(name);
			return (String) field.get(this);
		} catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
			WaylandCraftCommon.LOGGER.error("Invalid setting accessed: '" + name + "' as text!");
			e.printStackTrace();
		}
		return null;
	}
	
}
