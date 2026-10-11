package dev.graft.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

/**
 * Render scale, as in Graft for 26.2: the world (EntityRenderer.renderWorld, hand and shader packs included)
 * renders into a smaller framebuffer standing in for Minecraft's main one, and MetalFX upscales it back. The GUI is
 * drawn afterwards at full resolution. Set renderScale (50-100) with the Video Settings slider ({@link ScaleSlider}) or
 * in config/graft.properties. With an OptiFine shader pack the pack's buffers follow the same scale.
 */
public final class Scale {
	/** World resolution in percent of the window; below 100 it is upscaled with MetalFX. */
	static int renderScale = 100;
	private static boolean active;

	private static File file;

	static void load(File configDir) {
		file = new File(configDir, "graft.properties");
		try {
			renderScale = Math.max(50, Math.min(100, Integer.parseInt(read().getProperty("renderScale", "100").trim())));
		} catch (NumberFormatException e) {
			renderScale = 100;
		}
		if (!file.exists()) {
			save();
		}
	}

	private static Properties read() {
		Properties p = new Properties();
		if (file.exists()) {
			try (FileReader r = new FileReader(file)) {
				p.load(r);
			} catch (IOException ignored) {
			}
		}
		return p;
	}

	/** Rewrites renderScale in config/graft.properties, keeping the file's other keys. */
	private static void save() {
		if (file == null) {
			return;
		}
		Properties p = read();
		p.setProperty("renderScale", Integer.toString(renderScale));
		file.getParentFile().mkdirs();
		try (FileWriter w = new FileWriter(file)) {
			p.store(w, "Graft: renderScale = world resolution in percent (50-100), upscaled with MetalFX");
		} catch (IOException ignored) {
		}
	}

	/** Video Settings slider: applies from the next frame and is saved. */
	static void set(int percent) {
		if (percent == renderScale) {
			return;
		}
		renderScale = percent;
		if (percent >= 100 && resMul != null) {
			scaleShaderPack(1f); // begin() no longer runs the shader pack small
		}
		save();
	}

	private static java.lang.reflect.Method optifineShaders;
	private static boolean optifineChecked;

	/** OptiFine's Config.isShaders(): shader packs size their own buffers (OptiFine has its own Render Quality). */
	private static boolean shaderPackActive() {
		if (!optifineChecked) {
			optifineChecked = true;
			try {
				try {
					optifineShaders = Class.forName("Config").getMethod("isShaders");
				} catch (ClassNotFoundException e) {
					optifineShaders = Class.forName("net.optifine.Config").getMethod("isShaders");
				}
			} catch (ReflectiveOperationException | LinkageError e) {
				optifineShaders = null;
			}
		}
		try {
			return optifineShaders != null && (Boolean)optifineShaders.invoke(null);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	private static java.lang.reflect.Field resMul, displayWidth;

	/**
	 * Shader packs: OptiFine renders gbuffers/composites at renderDisplay * configRenderResMul (its Render Quality);
	 * Graft sets that to the render scale so the whole pack runs small, then MetalFX upscales the final image.
	 */
	private static void scaleShaderPack(float mul) {
		try {
			if (resMul == null) {
				Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
				resMul = shaders.getField("configRenderResMul");
				displayWidth = shaders.getDeclaredField("renderDisplayWidth");
				displayWidth.setAccessible(true);
			}
			if (resMul.getFloat(null) != mul) {
				resMul.setFloat(null, mul);
				displayWidth.setInt(null, -1); // makes Shaders.beginRender resize its buffers
			}
		} catch (ReflectiveOperationException | LinkageError e) {
			MGL.LOG.warn("Graft: could not set OptiFine's shader render resolution", e);
		}
	}

	/** EntityRenderer.renderWorld, start. */
	public static void begin() {
		if (renderScale >= 100 || !MGL.metalFxAvailable()) {
			return;
		}
		if (shaderPackActive()) {
			scaleShaderPack(renderScale / 100f);
		}
		Minecraft mc = Minecraft.getMinecraft();
		int main = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
		active = MGL.beginScaled(main, Math.max(1, mc.displayWidth * renderScale / 100), Math.max(1, mc.displayHeight * renderScale / 100));
	}

	/** EntityRenderer.renderWorld, every return. */
	public static void end() {
		if (active) {
			active = false;
			MGL.endScaled();
		}
	}

	private Scale() {
	}
}
