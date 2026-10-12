package dev.graft.legacy.video;

import dev.graft.legacy.MGL;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

import static dev.graft.legacy.video.VideoSettings.*;

/** Static entry points called from the Minecraft code {@link VideoTransformer} patches. */
public final class Hooks {
	/** Graft's Metal renderer is on (otherwise -Dgraft.bench runs vanilla OpenGL and GL calls go to LWJGL). */
	private static final boolean METAL_ON = !Boolean.getBoolean("graft.disabled");

	static void vsync(boolean on) {
		if (METAL_ON) {
			dev.graft.legacy.Win.setVSyncEnabled(on);
		} else {
			org.lwjgl.opengl.Display.setVSyncEnabled(on);
		}
	}

	/** 1.7.10 (no GlStateManager): the current color. */
	static void color(float r, float g, float b, float a) {
		if (METAL_ON) {
			MGL.glColor4f(r, g, b, a);
		} else {
			GL11.glColor4f(r, g, b, a);
		}
	}

	// ---- smooth lighting level (Block.getAmbientOcclusionLightValue return)
	public static float aoLight(float v) {
		int level = SMOOTH_LIGHTING_LEVEL.v;
		return level >= 100 ? v : 1f - (1f - v) * level / 100f;
	}

	// ---- dynamic lights (World/ChunkCache combined light return)
	static volatile int[] lights = new int[0];
	private static final int MAX_LIGHTS = 64;
	private static final int[] scan = new int[MAX_LIGHTS * 4];
	private static int tick;

	public static int light(int packed, int x, int y, int z) {
		int[] l = lights;
		if (l.length == 0) {
			return packed;
		}
		int block = (packed & 0xFFFF) >> 4, best = 0;
		for (int i = 0; i < l.length; i += 4) {
			int d = Math.abs(x - l[i]) + Math.abs(y - l[i + 1]) + Math.abs(z - l[i + 2]);
			best = Math.max(best, l[i + 3] - d);
		}
		return best > block ? packed & 0xFFFF0000 | best << 4 : packed;
	}

	public static int light(int packed, Object pos) {
		return lights.length == 0 || pos == null ? packed : light(packed, Game.x(pos), Game.y(pos), Game.z(pos));
	}

	static void lightsChanged() {
		tick = 0;
	}

	private static boolean waterApplied;
	private static final double[] lastPos = new double[3], pos = new double[3];
	static volatile boolean idle;

	/** Client tick end. */
	public static void clientTick() {
		if (!waterApplied && Game.inWorld()) {
			waterApplied = true;
			if (CLEAR_WATER.v != 0) {
				Game.waterOpacity(1);
			}
		}
		if (Game.playerPos(pos)) {
			idle = Arrays.equals(pos, lastPos);
			System.arraycopy(pos, 0, lastPos, 0, 3);
		}
		int mode = DYNAMIC_LIGHTS.v;
		int[] old = lights, now;
		if (mode == 0 || !Game.inWorld()) {
			now = new int[0];
		} else {
			if (mode == 1 && tick++ % 10 != 0) {
				return;
			}
			now = Arrays.copyOf(scan, Game.scanLights(scan, MAX_LIGHTS) * 4);
		}
		if (Arrays.equals(old, now)) {
			return;
		}
		lights = now;
		dirtyMissing(old, now);
		dirtyMissing(now, old);
	}

	/** Re-meshes the area of every source in `a` that `b` does not have. */
	private static void dirtyMissing(int[] a, int[] b) {
		outer:
		for (int i = 0; i < a.length; i += 4) {
			for (int j = 0; j < b.length; j += 4) {
				if (a[i] == b[j] && a[i + 1] == b[j + 1] && a[i + 2] == b[j + 2] && a[i + 3] == b[j + 3]) {
					continue outer;
				}
			}
			int r = a[i + 3];
			Game.markDirty(a[i] - r, a[i + 1] - r, a[i + 2] - r, a[i] + r, a[i + 1] + r, a[i + 2] + r);
		}
	}

	/** Light level a held or dropped item gives off: blocks by their own light, plus a few glowing items. */
	static int itemLight(String itemName, int blockLight) {
		if (blockLight > 0) {
			return blockLight;
		}
		if (itemName == null) {
			return 0;
		}
		switch (itemName.substring(itemName.indexOf(':') + 1)) {
			case "lava_bucket": case "nether_star": return 15;
			case "blaze_rod": case "blaze_powder": return 10;
			case "glowstone_dust": case "prismarine_crystals": case "magma_cream": return 8;
			default: return 0;
		}
	}

	// ---- fog (EntityRenderer.setupFog)
	public static float fogStart(float s) {
		float far = Game.farPlane();
		if (Math.abs(s - far * 0.75f) > 0.01f) {
			return s;
		}
		return FOG.v == 2 ? far * 64f : far * FOG_START.v / 10f;
	}

	public static float fogEnd(float e) {
		float far = Game.farPlane();
		return FOG.v == 2 && Math.abs(e - far) <= 0.01f ? far * 64f + 1f : e;
	}

	/** 1.7.10: GL11.glFogf in setupFog. */
	public static void glFogf(int pname, float v) {
		v = pname == 0x0B63 ? fogStart(v) : pname == 0x0B64 ? fogEnd(v) : v;
		if (METAL_ON) {
			MGL.glFogf(pname, v);
		} else {
			GL11.glFogf(pname, v);
		}
	}

	// ---- clouds
	public static int clouds(int vanilla) {
		int m = CLOUDS.v;
		return m == 0 ? vanilla : m == 3 ? 0 : m;
	}

	public static boolean clouds(boolean vanilla) {
		int m = CLOUDS.v;
		return m == 0 ? vanilla : m != 3;
	}

	public static boolean cloudsFancy(boolean fancy) {
		int m = CLOUDS.v;
		return m == 0 || m == 3 ? fancy : m == 2;
	}

	public static float cloudHeight(float h) {
		return h + CLOUD_HEIGHT.v * 0.64f;
	}

	// ---- trees
	public static boolean treesFancy(boolean fancy) {
		int m = TREES.v;
		return m == 0 ? fancy : m != 1;
	}

	/** BlockLeaves.shouldSideBeRendered reads its fancy flag: Smart culls faces between leaves like Fast does. */
	public static boolean treesCull(boolean fancy) {
		return TREES.v != 3 && fancy;
	}

	// ---- rain & snow
	public static boolean noRain() {
		return RAIN.v == 3;
	}

	public static boolean rainFancy(boolean fancy) {
		int m = RAIN.v;
		return m == 1 ? false : m == 2 || fancy;
	}

	// ---- sky
	public static Object skyColor(Object vec) {
		return SKY.v == 0 ? Game.fogColor(vec) : vec;
	}

	public static float[] sunrise(float[] colors) {
		return SKY.v == 0 ? null : colors;
	}

	public static float stars(float b) {
		return STARS.v == 0 ? 0f : b;
	}

	/** TextureManager.bindTexture in renderSky: with Sun & Moon off their quads are drawn fully transparent. */
	public static void bindSky(Object textureManager, Object location) {
		Game.bindTexture(textureManager, location);
		if (SUN_MOON.v == 0) {
			String s = String.valueOf(location);
			if (s.endsWith("/sun.png") || s.endsWith("/moon_phases.png")) {
				Game.hideSunMoon();
			}
		}
	}

	// ---- entities
	public static boolean noCapes() {
		return CAPES.v == 0;
	}

	public static boolean capes(boolean hasCape) {
		return hasCape && CAPES.v != 0;
	}

	public static boolean droppedFancy(boolean fancy) {
		int m = DROPPED_ITEMS.v;
		return m == 0 ? fancy : m == 2;
	}

	public static int droppedCount(int n) {
		return DROPPED_ITEMS.v == 1 ? 1 : n;
	}

	public static boolean shadows(boolean on) {
		return on && ENTITY_SHADOWS.v != 0;
	}

	public static boolean vignette(boolean fancy) {
		int m = VIGNETTE.v;
		return m == 0 ? fancy : m == 2;
	}

	/** Entity.isInRangeToRenderDist gets a squared distance. */
	public static double entityDist(double d2) {
		int p = ENTITY_DISTANCE.v;
		return p == 100 ? d2 : d2 * 10000.0 / ((double)p * p);
	}

	public static double tileDist(double max2) {
		int p = ENTITY_DISTANCE.v;
		return p == 100 ? max2 : max2 * p * p / 10000.0;
	}

	// ---- biomes
	public static boolean noSwamp() {
		return SWAMP_COLORS.v == 0;
	}

	public static boolean noBiomeBlend() {
		return SMOOTH_BIOMES.v == 0;
	}

	public static long altRand(long rand) {
		return ALTERNATE_BLOCKS_12.v == 0 ? 0L : rand;
	}

	// ---- quality
	/** BlockModelRenderer.renderModel: the grass block model with Better Grass sides. */
	public static Object blockModel(Object model, Object world, Object state, Object pos) {
		int m = BETTER_GRASS.v;
		return m == 0 || model == null ? model : Game.betterGrass(model, world, state, pos, m == 2);
	}

	private static final int[] MIP_FILTER = {0x2700, 0x2702, 0x2701, 0x2703};

	/** TextureUtil's mipmapped min filter (vanilla GL_NEAREST_MIPMAP_LINEAR) follows Mipmap Type. */
	public static void texParameteri(int target, int pname, int param) {
		boolean mipmapped = pname == 0x2801 && param == 0x2702;
		param = mipmapped ? MIP_FILTER[MIPMAP_TYPE.v] : param;
		int aniso = mipmapped ? 1 << ANISOTROPIC.v : 1; // GL_TEXTURE_MAX_ANISOTROPY on the mipmapped atlases
		if (METAL_ON) {
			MGL.glTexParameteri(target, pname, param);
			if (mipmapped) {
				MGL.glTexParameteri(target, 0x84FE, aniso);
			}
		} else {
			GL11.glTexParameteri(target, pname, param);
			if (aniso > 1) {
				GL11.glTexParameterf(target, 0x84FE, aniso);
			}
		}
	}

	static void clearWaterChanged() {
		Game.waterOpacity(CLEAR_WATER.v != 0 ? 1 : 3);
		Game.reloadRenderers();
	}

	// ---- animations
	public static boolean skipSprite(Object sprite) {
		String name = Game.spriteName(sprite);
		if (name == null) {
			return false;
		}
		String n = name.substring(name.lastIndexOf('/') + 1);
		n = n.substring(n.indexOf(':') + 1);
		Opt o = n.startsWith("water_") ? WATER_ANIMATED : n.startsWith("lava_") ? LAVA_ANIMATED : n.startsWith("fire_layer") ? FIRE_ANIMATED
			: n.equals("portal") ? PORTAL_ANIMATED
			: name.contains("items/") || n.equals("clock") || n.equals("compass") ? TEXTURES_ANIMATED : TERRAIN_ANIMATED;
		return o.v == 0;
	}

	/** EnumParticleTypes ids (1.8.9 and 1.12.2) to their names, which are the 1.7.10 particle names. */
	private static final String[] PARTICLES_BY_ID = {"explode", "largeexplode", "hugeexplosion", "fireworksSpark", "bubble", "splash", "wake",
		"suspended", "depthsuspend", "crit", "magicCrit", "smoke", "largesmoke", "spell", "instantSpell", "mobSpell", "mobSpellAmbient", "witchMagic",
		"dripWater", "dripLava", "angryVillager", "happyVillager", "townaura", "note", "portal", "enchantmenttable", "flame", "lava", "footstep",
		"cloud", "reddust", "snowballpoof", "snowshovel", "slime", "heart", "barrier", "iconcrack_", "blockcrack_", "blockdust_", "droplet", "take",
		"mobappearance"};

	public static boolean noParticle(int id) {
		return id >= 0 && id < PARTICLES_BY_ID.length && noParticle(PARTICLES_BY_ID[id]);
	}

	public static boolean noParticle(String name) {
		Opt o = particleOpt(name);
		return o != null && o.v == 0;
	}

	static Opt particleOpt(String name) {
		if (name == null) {
			return null;
		}
		switch (name) {
			case "reddust": return REDSTONE_PARTICLES;
			case "explode": case "largeexplode": case "hugeexplosion": return EXPLOSION_PARTICLES;
			case "flame": case "lava": return FLAME_PARTICLES;
			case "smoke": case "largesmoke": return SMOKE_PARTICLES;
			case "depthsuspend": return VOID_PARTICLES;
			case "bubble": case "splash": case "wake": case "suspended": return WATER_PARTICLES;
			case "droplet": return RAIN_SPLASH;
			case "portal": return PORTAL_PARTICLES;
			case "spell": case "instantSpell": case "mobSpell": case "mobSpellAmbient": case "witchMagic": return POTION_PARTICLES;
			case "dripWater": case "dripLava": return DRIPPING;
			case "fireworksSpark": return FIREWORK_PARTICLES;
			default: return null;
		}
	}

	/** EffectRenderer/ParticleManager.addEffect: particles that skip the named spawn path (rain splashes, firework sparks). */
	public static boolean noEffect(Object particle) {
		if (particle == null) {
			return false;
		}
		String c = particle.getClass().getName();
		c = c.substring(c.lastIndexOf('.') + 1);
		if (c.equals("EntityRainFX") || c.equals("ParticleRain")) {
			return RAIN_SPLASH.v == 0;
		}
		if (c.contains("Firework") && (c.contains("Spark") || c.contains("Overlay"))) {
			return FIREWORK_PARTICLES.v == 0;
		}
		return false;
	}

	// ---- performance
	private static int chunkCount;

	static int chunkLimit() {
		int n = CHUNK_UPDATES.v;
		return n > 0 && DYNAMIC_UPDATES.v != 0 && idle ? n * 3 : n;
	}

	public static void chunksBegin() {
		chunkCount = 0;
	}

	/** System.nanoTime in RenderGlobal.updateChunks, read after each scheduled chunk: past the limit the frame's budget is spent. */
	public static long chunkNanoTime(long t) {
		int limit = chunkLimit();
		return limit > 0 && ++chunkCount >= limit ? Long.MAX_VALUE : t;
	}

	/** 1.7.10 RenderGlobal.updateRenderers: far chunks rebuilt per frame (vanilla 2). */
	public static int farChunks(int vanilla) {
		int limit = chunkLimit();
		return limit > 0 ? limit : vanilla;
	}

	public static int resorts(int vanilla) {
		return TRANSLUCENT.v == 1 ? 0 : vanilla;
	}

	public static boolean fastMath() {
		return FAST_MATH.v != 0;
	}

	private static final float[] SIN = new float[4096];

	static {
		for (int i = 0; i < SIN.length; i++) {
			SIN[i] = (float)Math.sin(i * Math.PI * 2.0 / SIN.length);
		}
	}

	public static float sin(float f) {
		return SIN[(int)(f * 651.8986f) & 4095];
	}

	public static float cos(float f) {
		return SIN[(int)(f * 651.8986f + 1024f) & 4095];
	}

	/** 1.7.10 PlayerManager view radius clamp (vanilla 20): singleplayer sends up to 32 chunks. */
	public static int viewDistanceMax(int vanilla) {
		return Math.max(vanilla, 32);
	}

	// ---- other
	public static boolean noWeather(Object world) {
		return WEATHER.v == 0 && Game.isRemote(world);
	}

	public static boolean fixedTime(Object world) {
		return TIME.v != 0 && Game.isRemote(world);
	}

	/** Celestial angle: 0 is noon, 0.5 midnight. */
	public static float fixedAngle() {
		return TIME.v == 1 ? 0f : 0.5f;
	}

	private static final int[] AUTOSAVE_TICKS = {900, 40, 400, 3600, 36000};

	public static int autosave(int vanilla) {
		return AUTOSAVE.v == 0 ? vanilla : AUTOSAVE_TICKS[AUTOSAVE.v];
	}

	// ---- frame timing (lagometer, FPS)
	private static final long[] frames = new long[240];
	private static int frameIndex, fps, fpsCount;
	private static long lastFrame, fpsStart;

	/** Render tick start. */
	public static void frame() {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			frames[frameIndex] = now - lastFrame;
			frameIndex = (frameIndex + 1) % frames.length;
		}
		lastFrame = now;
		fpsCount++;
		if (now - fpsStart >= 1_000_000_000L) {
			fps = (int)(fpsCount * 1e9 / (now - fpsStart));
			fpsCount = 0;
			fpsStart = now;
		}
	}

	/** HUD overlay, after vanilla's: compact FPS and the lagometer. */
	public static void overlay(int width, int height, boolean debugScreen) {
		if (SHOW_FPS.v != 0 && !debugScreen) {
			Game.text(fps + " fps", 2, 2, 0xFFFFFF);
		}
		if (LAGOMETER.v != 0) {
			int base = height - 1;
			Game.rect(0, base - 33, frames.length, base - 32, 0x60FFFF00); // 30 fps
			Game.rect(0, base - 17, frames.length, base - 16, 0x6000FF00); // 60 fps
			for (int i = 0; i < frames.length; i++) {
				long ns = frames[(frameIndex + i) % frames.length];
				int h = (int)Math.min(60, ns / 1_000_000L);
				if (h > 0) {
					int color = ns < 17_000_000L ? 0xC000C000 : ns < 34_000_000L ? 0xC0E0E000 : 0xC0E00000;
					Game.rect(i, base - h, i + 1, base, color);
				}
			}
		}
	}

	private Hooks() {
	}
}
