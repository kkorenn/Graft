package dev.graft.legacy.video;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Every option of the OptiFine-style Video Settings screens. Values Graft owns live in .minecraft/graftvideo.txt
 * (key:value, unknown keys kept); vanilla ones stay in options.txt and are read/written through {@link Game}.
 * Nothing here touches Minecraft classes until a vanilla or live option is used.
 */
public final class VideoSettings {
	static final int V7 = 1, V8 = 2, V12 = 4, ALL = V7 | V8 | V12;
	static final String METAL = "Metal", SOON = "Coming soon", LATER = "Later";

	public static final class Opt {
		public final String key, name;
		final String[] labels;
		final int min, max, step, def;
		final boolean slider;
		IntFunction<String> format;
		String[] tip = new String[0];
		String status;
		int versions = ALL;
		String vanilla;
		Runnable apply;
		IntGetter getter;
		IntSetter setter;
		public volatile int v;

		Opt(String key, String name, String[] labels, int min, int max, int step, int def, boolean slider) {
			this.key = key;
			this.name = name;
			this.labels = labels;
			this.min = min;
			this.max = max;
			this.step = step;
			this.def = def;
			this.slider = slider;
			this.v = def;
		}

		Opt tip(String... lines) {
			tip = lines;
			return this;
		}

		Opt status(String s) {
			status = s;
			return this;
		}

		Opt only(int mask) {
			versions = mask;
			return this;
		}

		Opt vanilla(String k) {
			vanilla = k;
			return this;
		}

		Opt apply(Runnable r) {
			apply = r;
			return this;
		}

		Opt format(IntFunction<String> f) {
			format = f;
			return this;
		}

		Opt live(IntGetter g, IntSetter s) {
			getter = g;
			setter = s;
			return this;
		}

		boolean stored() {
			return vanilla == null && getter == null && status == null;
		}

		public int get() {
			return getter != null ? getter.get() : vanilla != null ? Game.get(vanilla) : v;
		}

		/** Sets the value (clamped to the range); returns whether it changed. Side effects run in {@link #applyNow}. */
		boolean set(int value) {
			value = Math.max(min, Math.min(max, value));
			if (value == get()) {
				return false;
			}
			if (setter != null) {
				setter.set(value);
			} else if (vanilla != null) {
				Game.set(vanilla, value);
			} else {
				v = value;
			}
			return true;
		}

		void applyNow() {
			if (apply != null) {
				apply.run();
			}
		}

		/** Next value for a click (shift = previous), wrapping around. */
		int next(boolean back) {
			int n = get() + (back ? -step : step);
			return n > max ? min : n < min ? max : n;
		}

		String valueLabel(int value) {
			if (status != null) {
				return status;
			}
			if (format != null) {
				return format.apply(value);
			}
			return labels != null ? labels[(value - min) / step] : Integer.toString(value);
		}

		public String label() {
			return name + ": " + valueLabel(status != null ? def : get());
		}
	}

	interface IntGetter {
		int get();
	}

	interface IntSetter {
		void set(int v);
	}

	private static final List<Opt> ALL_OPTS = new ArrayList<Opt>();

	private static Opt cycle(String key, String name, int def, String... labels) {
		Opt o = new Opt(key, name, labels, 0, labels.length - 1, 1, def, false);
		ALL_OPTS.add(o);
		return o;
	}

	private static Opt onOff(String key, String name, boolean def) {
		return cycle(key, name, def ? 1 : 0, "OFF", "ON");
	}

	private static Opt slider(String key, String name, int min, int max, int step, int def) {
		Opt o = new Opt(key, name, null, min, max, step, def, true);
		ALL_OPTS.add(o);
		return o;
	}

	private static Opt placeholder(String key, String name, String status) {
		return cycle(key, name, 0, "OFF").status(status);
	}

	private static Runnable reload() {
		return () -> Game.reloadRenderers();
	}

	// ---- main
	public static final Opt GRAPHICS = cycle("graphics", "Graphics", 1, "Fast", "Fancy").vanilla("graphics")
		.tip("Fast: lower quality, faster", "Fancy: transparent leaves, fancy clouds, vignette and more");
	public static final Opt RENDER_DISTANCE = slider("renderDistance", "Render Distance", 2, 32, 1, 12).vanilla("renderDistance")
		.format(v -> v + " chunks").tip("Visible distance, 2 to 32 chunks", "Singleplayer worlds send up to 32 chunks; servers decide their own limit");
	public static final Opt SMOOTH_LIGHTING = cycle("ao", "Smooth Lighting", 2, "OFF", "Minimum", "Maximum").vanilla("ao")
		.tip("Softer light and shadow across block faces");
	public static final Opt SMOOTH_LIGHTING_LEVEL = slider("smoothLightingLevel", "Smooth Lighting Level", 0, 100, 5, 100)
		.format(v -> v == 0 ? "OFF" : v + "%").apply(reload()).tip("How dark the smooth lighting corners get", "0% = no darkening, 100% = vanilla");
	public static final Opt MAX_FRAMERATE = slider("maxFps", "Max Framerate", 0, 26, 1, 12).vanilla("fps")
		.format(v -> v == 0 ? "VSync" : v >= 26 ? "Unlimited" : v * 10 + " fps").tip("VSync: match the display refresh rate", "Unlimited: as fast as possible");
	public static final Opt GUI_SCALE = cycle("guiScale", "GUI Scale", 0, "Auto", "Small", "Normal", "Large").vanilla("guiScale");
	public static final Opt VIEW_BOBBING = onOff("viewBobbing", "View Bobbing", true).vanilla("bobbing");
	public static final Opt BRIGHTNESS = slider("gamma", "Brightness", 0, 100, 1, 0).vanilla("gamma")
		.format(v -> v == 0 ? "Moody" : v == 100 ? "Bright" : "+" + v + "%");
	public static final Opt DYNAMIC_LIGHTS = cycle("dynamicLights", "Dynamic Lights", 0, "OFF", "Fast", "Fancy")
		.apply(() -> Hooks.lightsChanged()).tip("Held, dropped and burning light sources light up the world",
			"Fast: updates every 500 ms", "Fancy: updates every tick");
	public static final Opt DYNAMIC_FOV = onOff("dynamicFov", "Dynamic FOV", true)
		.tip("Field of view changes when sprinting, flying or slowed down");
	public static final Opt RENDER_SCALE = slider("renderScale", "Render Scale", 50, 100, 5, 100)
		.live(() -> dev.graft.legacy.Scale.renderScale, v -> dev.graft.legacy.Scale.set(v))
		.format(v -> v >= 100 ? "Native" : v + "% (MetalFX)").tip("Renders the world at a lower resolution", "and upscales it with MetalFX");
	public static final Opt FULLSCREEN = onOff("fullscreen", "Fullscreen", false).vanilla("fullscreen");
	public static final Opt FULLSCREEN_MODE = placeholder("fullscreenMode", "Fullscreen Mode", "Native")
		.tip("Graft uses the native macOS full screen space at the display's resolution");
	public static final Opt FOG = cycle("fog", "Fog", 1, "Fast", "Fancy", "OFF")
		.tip("Fast and Fancy look the same on Metal", "OFF: no distance fog (water, lava and blindness fog stay)");
	public static final Opt FOG_START = slider("fogStart", "Fog Start", 2, 8, 1, 8).format(v -> "0." + v)
		.tip("Distance where the fog begins, as a fraction of the render distance");
	public static final Opt CLOUDS = cycle("clouds", "Clouds", 0, "Default", "Fast", "Fancy", "OFF")
		.tip("Default: as set by Graphics");
	public static final Opt CLOUD_HEIGHT = slider("cloudHeight", "Cloud Height", 0, 100, 5, 0).format(v -> v == 0 ? "OFF" : v + "%")
		.tip("Raises the clouds above their normal height");
	public static final Opt ENTITY_SHADOWS = onOff("entityShadows", "Entity Shadows", true).only(V7);
	public static final Opt ENTITY_SHADOWS_VANILLA = onOff("entityShadowsVanilla", "Entity Shadows", true).vanilla("entityShadows").only(V8 | V12);
	public static final Opt ALTERNATE_BLOCKS = onOff("alternateBlocks", "Alternate Blocks", true).vanilla("altBlocks").only(V8)
		.tip("Random model variants for some blocks");
	public static final Opt ALTERNATE_BLOCKS_12 = onOff("alternateBlocks", "Alternate Blocks", true).only(V12).apply(reload())
		.tip("Random model variants for some blocks");
	public static final Opt USE_VBOS = placeholder("useVbos", "Use VBOs", METAL).only(V8 | V12)
		.tip("Graft uploads chunk meshes straight to Metal buffers");
	public static final Opt RENDER_REGIONS = placeholder("renderRegions", "Render Regions", METAL)
		.tip("Graft batches chunk draws on the GPU already");
	public static final Opt ADVANCED_OPENGL = placeholder("advancedOpengl", "Advanced OpenGL", METAL).only(V7)
		.tip("There is no OpenGL under Graft");

	// ---- details
	public static final Opt TREES = cycle("trees", "Trees", 0, "Default", "Fast", "Fancy", "Smart").apply(reload())
		.tip("Fast: opaque leaves", "Fancy: transparent leaves", "Smart: transparent leaves without hidden inner faces");
	public static final Opt RAIN = cycle("rain", "Rain & Snow", 0, "Default", "Fast", "Fancy", "OFF");
	public static final Opt SKY = onOff("sky", "Sky", true);
	public static final Opt STARS = onOff("stars", "Stars", true);
	public static final Opt SUN_MOON = onOff("sunMoon", "Sun & Moon", true);
	public static final Opt CAPES = onOff("capes", "Show Capes", true).tip("Shows player capes");
	public static final Opt TRANSLUCENT = cycle("translucentBlocks", "Translucent Blocks", 0, "Default", "Fast", "Fancy").only(V8 | V12)
		.tip("Fast: translucent blocks are not re-sorted as you move", "Fancy: correct blending of water and stained glass");
	public static final Opt HELD_TOOLTIPS = onOff("heldItemTooltips", "Held Item Tooltips", true).vanilla("heldTooltips");
	public static final Opt DROPPED_ITEMS = cycle("droppedItems", "Dropped Items", 0, "Default", "Fast", "Fancy")
		.tip("Fast: one model per dropped stack", "Fancy: stacked 3D models");
	public static final Opt VIGNETTE = cycle("vignette", "Vignette", 0, "Default", "Fast", "Fancy")
		.tip("Darkened screen corners", "Fast: off, Fancy: on, Default: as set by Graphics");
	public static final Opt SWAMP_COLORS = onOff("swampColors", "Swamp Colors", true).apply(reload())
		.tip("Darker grass and leaves in swamps");
	public static final Opt SMOOTH_BIOMES = onOff("smoothBiomes", "Smooth Biomes", true).only(V8 | V12).apply(reload())
		.tip("Blends grass, leaf and water colors across biome borders");
	public static final Opt SMOOTH_BIOMES_7 = placeholder("smoothBiomes7", "Smooth Biomes", LATER).only(V7);
	public static final Opt ENTITY_DISTANCE = slider("entityDistance", "Entity Distance", 50, 500, 25, 100).format(v -> v + "%")
		.tip("How far entities and block entities are drawn");

	// ---- quality
	public static final Opt MIPMAP_LEVELS = slider("mipmapLevels", "Mipmap Levels", 0, 4, 1, 4).vanilla("mipmap")
		.format(v -> v == 0 ? "OFF" : Integer.toString(v)).tip("Smoother distant textures");
	public static final Opt MIPMAP_TYPE = cycle("mipmapType", "Mipmap Type", 1, "Nearest", "Linear", "Bilinear", "Trilinear")
		.apply(() -> Game.refreshResources()).tip("Filtering between mipmap levels", "Linear is vanilla");
	public static final Opt ANISOTROPIC = cycle("anisotropic", "Anisotropic Filtering", 0, "OFF", "2x", "4x", "8x", "16x")
		.apply(() -> Game.refreshResources()).tip("Sharper textures at grazing angles (block atlas)", "Needs mipmaps; a little GPU cost");
	public static final Opt ANTIALIASING = placeholder("antialiasing", "Antialiasing", LATER)
		.tip("Use Render Scale (MetalFX) for now");
	public static final Opt CLEAR_WATER = onOff("clearWater", "Clear Water", false).apply(() -> Hooks.clearWaterChanged())
		.tip("Light reaches deeper into water", "Applies to chunks as they are relit");
	public static final Opt BETTER_GRASS = cycle("betterGrass", "Better Grass", 0, "OFF", "Fast", "Fancy").only(V8 | V12).apply(reload())
		.tip("Fast: grass block sides fully covered in grass", "Fancy: only sides next to lower grass blocks");
	public static final Opt BETTER_GRASS_7 = placeholder("betterGrass7", "Better Grass", LATER).only(V7);
	public static final Opt BETTER_SNOW = placeholder("betterSnow", "Better Snow", LATER);
	public static final Opt RANDOM_ENTITIES = placeholder("randomEntities", "Random Entities", SOON);
	public static final Opt CUSTOM_FONTS = placeholder("customFonts", "Custom Fonts", SOON);
	public static final Opt CUSTOM_COLORS = placeholder("customColors", "Custom Colors", SOON);
	public static final Opt CUSTOM_SKY = placeholder("customSky", "Custom Sky", SOON);
	public static final Opt CUSTOM_ITEMS = placeholder("customItems", "Custom Items", SOON);
	public static final Opt CONNECTED_TEXTURES = placeholder("connectedTextures", "Connected Textures", SOON);
	public static final Opt NATURAL_TEXTURES = placeholder("naturalTextures", "Natural Textures", SOON);
	public static final Opt EMISSIVE_TEXTURES = placeholder("emissiveTextures", "Emissive Textures", SOON);
	public static final Opt CUSTOM_ENTITY_MODELS = placeholder("customEntityModels", "Custom Entity Models", SOON);

	// ---- animations
	public static final Opt WATER_ANIMATED = onOff("animWater", "Water Animated", true);
	public static final Opt LAVA_ANIMATED = onOff("animLava", "Lava Animated", true);
	public static final Opt FIRE_ANIMATED = onOff("animFire", "Fire Animated", true);
	public static final Opt PORTAL_ANIMATED = onOff("animPortal", "Portal Animated", true);
	public static final Opt REDSTONE_PARTICLES = onOff("particlesRedstone", "Redstone Particles", true);
	public static final Opt EXPLOSION_PARTICLES = onOff("particlesExplosion", "Explosion Particles", true);
	public static final Opt FLAME_PARTICLES = onOff("particlesFlame", "Flame Particles", true);
	public static final Opt SMOKE_PARTICLES = onOff("particlesSmoke", "Smoke Particles", true);
	public static final Opt VOID_PARTICLES = onOff("particlesVoid", "Void Particles", true);
	public static final Opt WATER_PARTICLES = onOff("particlesWater", "Water Particles", true);
	public static final Opt RAIN_SPLASH = onOff("particlesRain", "Rain Splash", true);
	public static final Opt PORTAL_PARTICLES = onOff("particlesPortal", "Portal Particles", true);
	public static final Opt POTION_PARTICLES = onOff("particlesPotion", "Potion Particles", true);
	public static final Opt DRIPPING = onOff("particlesDripping", "Dripping Water/Lava", true);
	public static final Opt TERRAIN_ANIMATED = onOff("animTerrain", "Terrain Animated", true);
	public static final Opt TEXTURES_ANIMATED = onOff("animTextures", "Textures Animated", true);
	public static final Opt FIREWORK_PARTICLES = onOff("particlesFirework", "Firework Particles", true);
	public static final Opt PARTICLES = cycle("particles", "Particles", 0, "All", "Decreased", "Minimal").vanilla("particles");
	static final Opt[] ANIMATIONS = {WATER_ANIMATED, LAVA_ANIMATED, FIRE_ANIMATED, PORTAL_ANIMATED, REDSTONE_PARTICLES, EXPLOSION_PARTICLES,
		FLAME_PARTICLES, SMOKE_PARTICLES, VOID_PARTICLES, WATER_PARTICLES, RAIN_SPLASH, PORTAL_PARTICLES, POTION_PARTICLES, DRIPPING,
		TERRAIN_ANIMATED, TEXTURES_ANIMATED, FIREWORK_PARTICLES};

	// ---- performance
	public static final Opt SMOOTH_FPS = placeholder("smoothFps", "Smooth FPS", METAL).tip("Metal paces frames itself");
	public static final Opt SMOOTH_WORLD = placeholder("smoothWorld", "Smooth World", LATER);
	public static final Opt FAST_RENDER = placeholder("fastRender", "Fast Render", METAL).tip("Graft's Metal renderer is always on");
	public static final Opt FAST_MATH = onOff("fastMath", "Fast Math", false).tip("Lower precision sin() and cos()");
	public static final Opt CHUNK_UPDATES = slider("chunkUpdates", "Chunk Updates", 0, 5, 1, 0)
		.format(v -> v == 0 ? "Default" : Integer.toString(v)).tip("Chunks rebuilt per frame", "Default: as many as fit in the frame time");
	public static final Opt DYNAMIC_UPDATES = onOff("dynamicUpdates", "Dynamic Updates", true)
		.tip("Rebuilds three times as many chunks while you stand still", "(only with a Chunk Updates limit)");
	public static final Opt LAZY_CHUNKS = placeholder("lazyChunkLoading", "Lazy Chunk Loading", LATER);

	// ---- other
	public static final Opt LAGOMETER = onOff("lagometer", "Lagometer", false).tip("Frame time graph at the bottom of the screen");
	public static final Opt SHOW_FPS = onOff("showFps", "Show FPS", false).tip("Compact FPS counter in the top left corner");
	public static final Opt WEATHER = onOff("weather", "Weather", true).tip("Rain, snow and thunder (client side only)");
	public static final Opt TIME = cycle("time", "Time", 0, "Default", "Day Only", "Night Only").tip("Client-side sky time");
	public static final Opt AUTOSAVE = cycle("autosave", "Autosave", 0, "Default", "2s", "20s", "3min", "30min")
		.tip("How often singleplayer worlds are saved", "Default: every 45 seconds");
	public static final Opt SCREENSHOT_SIZE = placeholder("screenshotSize", "Screenshot Size", LATER);
	public static final Opt ADVANCED_TOOLTIPS = onOff("advancedTooltips", "Advanced Tooltips", false).vanilla("advTooltips");

	static final int MAIN = 0, DETAILS = 1, QUALITY = 2, ANIMATIONS_PAGE = 3, PERFORMANCE = 4, OTHER = 5;
	static final String[] TITLES = {"Video Settings", "Details Settings", "Quality Settings", "Animation Settings", "Performance Settings", "Other Settings"};

	static final List<List<Opt>> PAGES = Arrays.asList(
		Arrays.asList(GRAPHICS, RENDER_DISTANCE, SMOOTH_LIGHTING, SMOOTH_LIGHTING_LEVEL, MAX_FRAMERATE, GUI_SCALE, VIEW_BOBBING, BRIGHTNESS,
			DYNAMIC_LIGHTS, DYNAMIC_FOV, RENDER_SCALE, FULLSCREEN, FOG, FOG_START, CLOUDS, CLOUD_HEIGHT, ENTITY_SHADOWS, ENTITY_SHADOWS_VANILLA,
			ALTERNATE_BLOCKS, ALTERNATE_BLOCKS_12, USE_VBOS, RENDER_REGIONS, ADVANCED_OPENGL, FULLSCREEN_MODE),
		Arrays.asList(CLOUDS, CLOUD_HEIGHT, TREES, RAIN, SKY, STARS, SUN_MOON, CAPES, FOG, FOG_START, TRANSLUCENT, HELD_TOOLTIPS, DROPPED_ITEMS,
			VIGNETTE, ALTERNATE_BLOCKS, ALTERNATE_BLOCKS_12, ENTITY_SHADOWS, ENTITY_SHADOWS_VANILLA, SWAMP_COLORS, SMOOTH_BIOMES, SMOOTH_BIOMES_7,
			ENTITY_DISTANCE),
		Arrays.asList(MIPMAP_LEVELS, MIPMAP_TYPE, ANISOTROPIC, ANTIALIASING, CLEAR_WATER, BETTER_GRASS, BETTER_GRASS_7, BETTER_SNOW, RANDOM_ENTITIES,
			CUSTOM_FONTS, CUSTOM_COLORS, CUSTOM_SKY, CUSTOM_ITEMS, CONNECTED_TEXTURES, NATURAL_TEXTURES, EMISSIVE_TEXTURES, CUSTOM_ENTITY_MODELS),
		Arrays.asList(WATER_ANIMATED, LAVA_ANIMATED, FIRE_ANIMATED, PORTAL_ANIMATED, REDSTONE_PARTICLES, EXPLOSION_PARTICLES, FLAME_PARTICLES,
			SMOKE_PARTICLES, VOID_PARTICLES, WATER_PARTICLES, RAIN_SPLASH, PORTAL_PARTICLES, POTION_PARTICLES, DRIPPING, TERRAIN_ANIMATED,
			TEXTURES_ANIMATED, FIREWORK_PARTICLES, PARTICLES),
		Arrays.asList(SMOOTH_FPS, SMOOTH_WORLD, FAST_RENDER, FAST_MATH, CHUNK_UPDATES, DYNAMIC_UPDATES, LAZY_CHUNKS, RENDER_REGIONS, USE_VBOS,
			ADVANCED_OPENGL),
		Arrays.asList(LAGOMETER, SHOW_FPS, WEATHER, TIME, FULLSCREEN, FULLSCREEN_MODE, AUTOSAVE, SCREENSHOT_SIZE, ADVANCED_TOOLTIPS));

	private static File file;
	private static final Map<String, String> unknown = new LinkedHashMap<String, String>();

	public static void load(File f) {
		file = f;
		unknown.clear();
		for (Opt o : ALL_OPTS) {
			o.v = o.def;
		}
		if (!f.isFile()) {
			return;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				int c = line.indexOf(':');
				if (c <= 0 || line.startsWith("#")) {
					continue;
				}
				String k = line.substring(0, c).trim(), val = line.substring(c + 1).trim();
				Opt o = stored(k);
				if (o == null) {
					if (!owned(k)) {
						unknown.put(k, val);
					}
					continue;
				}
				try {
					o.v = Math.max(o.min, Math.min(o.max, Integer.parseInt(val)));
				} catch (NumberFormatException e) {
					o.v = o.def;
				}
			}
		} catch (IOException ignored) {
		}
	}

	public static void save() {
		if (file == null) {
			return;
		}
		Map<String, String> out = new LinkedHashMap<String, String>();
		for (Opt o : ALL_OPTS) {
			if (o.stored()) {
				out.put(o.key, Integer.toString(o.v));
			}
		}
		for (Map.Entry<String, String> e : unknown.entrySet()) {
			out.putIfAbsent(e.getKey(), e.getValue());
		}
		File tmp = new File(file.getPath() + ".tmp");
		try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> e : out.entrySet()) {
				w.write(e.getKey() + ":" + e.getValue() + "\n");
			}
		} catch (IOException e) {
			return;
		}
		if (!tmp.renameTo(file)) {
			file.delete();
			tmp.renameTo(file);
		}
	}

	/** A key of an option whose value lives elsewhere (options.txt, config/graft.properties). */
	private static boolean owned(String key) {
		for (Opt o : ALL_OPTS) {
			if (o.key.equals(key)) {
				return true;
			}
		}
		return false;
	}

	static Opt stored(String key) {
		for (Opt o : ALL_OPTS) {
			if (o.key.equals(key) && o.stored()) {
				return o;
			}
		}
		return null;
	}

	private VideoSettings() {
	}
}
