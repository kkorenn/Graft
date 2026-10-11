package dev.graft.metal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** User options, stored in config/graft.properties. Loaded once at startup, before the graphics backend is picked. */
public final class MetalConfig {
	/** Tag the swapchain as Display P3 (saturated, pre-26.3 look) instead of sRGB. Applies on restart. */
	public static boolean displayP3;
	/** Set once Metal has been made the selected renderer, so a later switch to OpenGL/Vulkan sticks. */
	public static boolean metalSelected;
	/** World render resolution in percent of the window (100 = native); below 100 it's upscaled with MetalFX. */
	public static int renderScale = 100;
	private static Path file;

	private MetalConfig() {
	}

	public static void load(Path path) {
		file = path;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(path)) {
			p.load(r);
		} catch (IOException e) {
			// Missing or unreadable: defaults.
		}
		displayP3 = Boolean.parseBoolean(p.getProperty("displayP3", "false"));
		metalSelected = Boolean.parseBoolean(p.getProperty("metalSelected", "false"));
		try {
			renderScale = Math.clamp(Integer.parseInt(p.getProperty("renderScale", "100")), 50, 100);
		} catch (NumberFormatException e) {
			renderScale = 100;
		}
		if (Files.notExists(path)) {
			save();
		}
	}

	public static void save() {
		if (file == null) {
			return;
		}
		Properties p = new Properties();
		p.setProperty("displayP3", Boolean.toString(displayP3));
		p.setProperty("metalSelected", Boolean.toString(metalSelected));
		p.setProperty("renderScale", Integer.toString(renderScale));
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Graft");
			}
		} catch (IOException e) {
			System.err.println("[graft] Failed to save " + file + ": " + e);
		}
	}
}
