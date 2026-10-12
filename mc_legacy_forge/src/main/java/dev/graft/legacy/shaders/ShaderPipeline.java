package dev.graft.legacy.shaders;

import dev.graft.legacy.MGL;
import dev.graft.legacy.MetalGlsl;
import dev.graft.legacy.Programs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graft's own OptiFine-format shader pipeline for the legacy versions, written on Graft's GL layer (MGL/Programs, i.e.
 * Metal underneath). Version modules report frame state and call the hooks: {@link #beginWorld} at the start of the world
 * pass, {@link #section} at each profiler section (the stage tags), {@link #clear} for the pass's clears and
 * {@link #endWorld} after the hand. Packs load and translate on a background thread; until a pack is ready the game
 * renders as usual. Render thread only, except the loader.
 */
public final class ShaderPipeline {
	private static final Logger LOG = LogManager.getLogger("Graft Shaders");

	/** True while the world is being rendered with a pack (read by MGL). */
	public static volatile boolean active;
	/** Minecraft's framebuffer during the world pass, redirected to the gbuffers (read by MGL.glBindFramebuffer). */
	public static int mainFb = Integer.MIN_VALUE, gbufferFb;
	public static final FrameState frame = new FrameState();
	/** Draws all loaded terrain (solid + cutout layers) with the current matrices; set by the version module. */
	public static Runnable shadowTerrain;
	/** Last load status / errors, for the UI. */
	public static volatile String status = "";
	/** MC_VERSION of the running game (set by the version module). */
	public static int mcVersion = 10809;
	/** Why the pipeline stays off for good (OptiFine installed), shown by the shaders screen; null = available. */
	public static String disabledReason;
	/**
	 * Chunk builder state while a pack is installed: shader vertex layout on, block id table ((id << 4 | meta) -> pack id,
	 * null = legacy ids) and a generation the version module watches to rebuild chunks.
	 */
	public static volatile boolean extendedTerrain;
	public static volatile int[] blockIdTable;
	public static volatile int terrainGeneration;
	public static volatile IdMap itemIds, entityIds;
	/** Builds the block id table from a pack's block.properties (version module; runs on the loader thread). */
	public static java.util.function.Function<IdMap, int[]> blockTableBuilder;
	/** Shadow pass extras from the version module: translucent terrain, and entities + block entities. */
	public static Runnable shadowTranslucent, shadowEntities;
	/** True while the shadow pass draws: stage tags and texture toggles are ignored. */
	public static boolean inShadow;

	private static File gameDir, packsDir, cacheDir, configFile;
	private static String selected;
	private static int optionsVersion;

	// ---------------------------------------------------------------- selection and loading

	public static void init(File gameDirectory) {
		gameDir = gameDirectory;
		packsDir = new File(gameDir, "shaderpacks");
		packsDir.mkdirs();
		cacheDir = new File(gameDir, "graft/shadercache");
		MGL.openPipelineArchive(new File(cacheDir, "pipelines.metallib"));
		configFile = new File(gameDir, "config/graft-shaders.properties");
		Properties p = new Properties();
		if (configFile.isFile()) {
			try (InputStream in = new FileInputStream(configFile)) {
				p.load(in);
			} catch (IOException ignored) {
			}
		}
		String s = p.getProperty("shaderPack", "").trim();
		selected = s.isEmpty() ? null : s;
	}

	public static File packsDir() {
		return packsDir;
	}

	/** Pack folders and zips in .minecraft/shaderpacks. */
	public static List<String> listPacks() {
		List<String> out = new ArrayList<String>();
		File[] fs = packsDir == null ? null : packsDir.listFiles();
		if (fs != null) {
			for (File f : fs) {
				if (f.isDirectory() && new File(f, "shaders").isDirectory() || f.isFile() && f.getName().toLowerCase().endsWith(".zip")) {
					out.add(f.getName());
				}
			}
		}
		java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	public static String selected() {
		return selected;
	}

	/** Selects a pack (null = off) and saves the choice; the pack loads in the background. */
	public static void select(String pack) {
		selected = pack;
		Properties p = new Properties();
		p.setProperty("shaderPack", pack == null ? "" : pack);
		configFile.getParentFile().mkdirs();
		try (OutputStream out = new FileOutputStream(configFile)) {
			p.store(out, "Graft shader pack");
		} catch (IOException ignored) {
		}
		status = pack == null ? "" : "Loading " + pack + "...";
	}

	/** Reloads the current pack (after option changes). */
	public static void reload() {
		optionsVersion++;
	}

	public static File optionsFile(String pack) {
		return new File(packsDir, pack + ".txt");
	}

	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "Graft shader loader");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		}
	});

	/** A pack translated and compiled off the render thread. */
	static final class Loaded {
		String key;
		ShaderPack pack;
		PackConfig config;
		ProgramSet set;
		final Map<String, MetalGlsl.Linked> linked = new LinkedHashMap<String, MetalGlsl.Linked>();
		final Map<String, long[]> functions = new HashMap<String, long[]>();
		final Map<String, ProgramSet.Source> sources = new HashMap<String, ProgramSet.Source>();
		Map<String, String> macros;
		IdMap itemIds, entityIds;
		int[] blockTable;
		final Map<String, Overrides> overrides = new HashMap<String, Overrides>();
		final Map<String, Map<String, Long>> pipelines = new HashMap<String, Map<String, Long>>();
	}

	/** blend.<program>[.<buffer>] / alphaTest.<program> of one program (see Programs.setOverrides). */
	static final class Overrides {
		int blend = -1, alphaFunc = -1;
		int[] attach;
		float alphaRef;
	}

	private static volatile Loaded ready;
	private static String loadingKey;

	private static void startLoad(final String key, final String packName, final String world) {
		loadingKey = key;
		LOADER.submit(new Runnable() {
			@Override
			public void run() {
				long t0 = System.nanoTime();
				try {
					Loaded l = load(packName, world);
					l.key = key;
					ready = l;
					status = packName + ": " + l.linked.size() + " programs ready in " + (System.nanoTime() - t0) / 1000000 + " ms"
						+ (status.startsWith("!") ? status : "");
					LOG.info("Graft: shader pack {} ({}) loaded: {}", packName, world, status);
				} catch (Throwable e) {
					status = "!" + packName + ": " + e;
					LOG.error("Graft: could not load shader pack " + packName, e);
				}
			}
		});
	}

	private static Loaded load(String packName, String world) throws IOException {
		Loaded l = new Loaded();
		l.pack = ShaderPack.open(new File(packsDir, packName));
		if (l.pack == null) {
			throw new IOException("not a shader pack (no shaders/ folder)");
		}
		Options.Values values = Options.Values.load(optionsFile(packName));
		ShaderPack.Environment env = new ShaderPack.Environment();
		env.world = world;
		env.mcVersion = mcVersion;
		Map<String, String> macros = env.macros();
		Map<String, String> all = new LinkedHashMap<String, String>(macros);
		all.putAll(l.pack.options.macros(values));
		l.config = new PackConfig(l.pack, all);
		l.macros = all;
		IdMap blocks = IdMap.load(l.pack, "block.properties", "block", all);
		l.itemIds = IdMap.load(l.pack, "item.properties", "item", all);
		l.entityIds = IdMap.load(l.pack, "entity.properties", "entity", all);
		if (blocks != null && blockTableBuilder != null) {
			l.blockTable = blockTableBuilder.apply(blocks);
		}
		l.set = new ProgramSet(l.pack, world, macros, values, l.config);
		List<ProgramSet.Source> sources = new ArrayList<ProgramSet.Source>(l.set.all());
		if (l.set.get("final") == null) {
			sources.add(new ProgramSet.Source("final", BUILTIN_FINAL_VS, BUILTIN_FINAL_FS, null, Directives.parse(BUILTIN_FINAL_FS, BUILTIN_FINAL_VS)));
		}
		int failed = 0;
		for (ProgramSet.Source s : sources) {
			if (s.geometry != null) {
				LOG.warn("Graft: {} has a geometry shader (unsupported), using its fallback", s.name);
				continue;
			}
			MetalGlsl.Linked linked = PackCompiler.translate(s, cacheDir);
			long[] fns = linked.error == null ? Programs.compileFunctions(linked) : null;
			if (fns == null) {
				failed++;
				LOG.warn("Graft: shader program {} failed: {}", s.name, linked.error == null ? "Metal rejected it" : first(linked.error, 2000));
				continue;
			}
			l.linked.put(s.name, linked);
			l.functions.put(s.name, fns);
			l.sources.put(s.name, s);
		}
		if (failed > 0) {
			status = "! (" + failed + " programs failed, see log)";
		}
		for (String name : l.linked.keySet()) {
			l.overrides.put(name, overrides(name, drawBuffers(l.sources.get(name)), l.config));
		}
		prewarm(l);
		return l;
	}

	/**
	 * Pipeline states of the predictable draws, built here on the loader thread: chunk terrain for the terrain, water and
	 * shadow programs, full-screen quads for prepare/deferred/composite/final. The rest (entities, particles: their vertex
	 * layouts vary) compile on first use, then come from the pipeline archive on the next launch.
	 */
	private static void prewarm(Loaded l) {
		long t0 = System.nanoTime();
		int[] formats = targetFormats(l.set, l.sources.values());
		String terrain = resolveIn(l, "gbuffers_terrain"), water = resolveIn(l, "gbuffers_water");
		int n = 0;
		for (String name : l.linked.keySet()) {
			ProgramSet.Source src = l.sources.get(name);
			Overrides ov = l.overrides.get(name);
			int[] db = drawBuffers(src), internal = new int[db.length];
			Map<String, Long> into = new HashMap<String, Long>();
			try {
				if (name.equals("shadow")) {
					for (int k = 0; k < db.length; k++) {
						String f = db[k] < 2 ? l.set.format(100 + db[k]) : null;
						internal[k] = internalFormat(f != null ? f : "RGBA8");
					}
					MGL.prewarm(l.functions.get(name), Math.max(0, ov.blend), ov.attach, true, internal, true, into);
				} else if (name.equals(terrain) || name.equals(water)) {
					for (int k = 0; k < db.length; k++) {
						internal[k] = formats[db[k]];
					}
					if (name.equals(terrain)) {
						MGL.prewarm(l.functions.get(name), Math.max(0, ov.blend), ov.attach, true, internal, true, into);
					}
					if (name.equals(water)) { // translucent layer: the game's blend unless the pack overrides it
						MGL.prewarm(l.functions.get(name), ov.blend >= 0 ? ov.blend : MGL.blendState(0x302, 0x303, 1, 0), ov.attach, true, internal, true, into);
					}
				} else if (name.equals("final")) {
					MGL.prewarm(l.functions.get(name), Math.max(0, ov.blend), ov.attach, false, new int[] {0}, true, into);
				} else if (!name.startsWith("gbuffers_")) { // prepare / deferred / composite
					for (int k = 0; k < db.length; k++) {
						internal[k] = formats[db[k]];
					}
					MGL.prewarm(l.functions.get(name), Math.max(0, ov.blend), ov.attach, false, internal, false, into);
				}
			} catch (RuntimeException e) {
				log("prewarm " + name + ": " + e);
			}
			n += into.size();
			l.pipelines.put(name, into);
		}
		LOG.info("Graft: {} pipeline states prewarmed in {} ms", n, (System.nanoTime() - t0) / 1000000);
	}

	private static String resolveIn(Loaded l, String name) {
		for (String n = name; n != null; n = ProgramSet.FALLBACK.get(n)) {
			if (l.linked.containsKey(n)) {
				return n;
			}
		}
		return null;
	}

	/** GL internal format of every colortex as the pack declares it (gdepth defaults to RGBA32F when sampled, as documented). */
	static int[] targetFormats(ProgramSet set, java.util.Collection<ProgramSet.Source> sources) {
		boolean gdepthSampled = false;
		for (ProgramSet.Source s : sources) {
			gdepthSampled |= s.directives.samplers.contains("gdepth");
		}
		int[] out = new int[BUFFERS];
		for (int b = 0; b < BUFFERS; b++) {
			String name = set.format(b);
			out[b] = internalFormat(name != null ? name : b == 1 && gdepthSampled ? "RGBA32F" : "RGBA8");
		}
		return out;
	}

	private static String first(String s, int n) {
		return s.length() > n ? s.substring(0, n) : s;
	}

	private static final String BUILTIN_FINAL_VS = "#version 120\nvarying vec2 mc_tc;\nvoid main() {\n\tgl_Position = ftransform();\n\tmc_tc = gl_MultiTexCoord0.xy;\n}\n";
	private static final String BUILTIN_FINAL_FS = "#version 120\nuniform sampler2D colortex0;\nvarying vec2 mc_tc;\nvoid main() {\n\tgl_FragColor = texture2D(colortex0, mc_tc);\n}\n";

	static void log(String s) {
		LOG.warn("Graft: {}", s);
	}

	// ---------------------------------------------------------------- installed pack (render thread)

	private static final int[] BUFFER0 = {0};
	private static final int TEX = 0xDE1, FB = 0x8D40, READ_FB = 0x8CA8, COLOR0 = 0x8CE0, DEPTH_ATT = 0x8D00, UNIT0 = 0x84C0;

	/** A linked program and what binding it needs. */
	static final class Prog {
		String name;
		int id, frame = -1;
		int[] drawBuffers;
		String[] uNames;
		int[] uLoc, uKind, uN;
		boolean waterShadow;
		/** buffer -> false where flip.<program>.<buffer>=false */
		boolean[] noFlip = new boolean[BUFFERS];
		/** buffers whose mipmaps this (composite) program wants (const bool <buffer>MipmapEnabled) */
		boolean[] mipmap = new boolean[BUFFERS];
		/** per-draw uniforms (entityId, blockEntityId, entityColor, renderStage), -1 = not declared */
		int locEntityId, locBlockEntityId, locEntityColor, locRenderStage;
		/** scale.<program>=<scale> [<offset x> <offset y>] */
		float scale = 1, offX, offY;
	}

	private static Loaded cur;
	private static final Map<String, Prog> progs = new HashMap<String, Prog>();
	private static final Map<String, Prog> resolved = new HashMap<String, Prog>();
	private static Uniforms uniforms;
	private static final List<Prog> deferred = new ArrayList<Prog>(), composite = new ArrayList<Prog>(), prepare = new ArrayList<Prog>();
	/** colortex0-15 (Metal: at most 8 of them per pass) */
	static final int BUFFERS = 16;

	private static void install(Loaded l) {
		uninstall();
		cur = l;
		uniforms = new Uniforms(l.config);
		for (Map.Entry<String, MetalGlsl.Linked> e : l.linked.entrySet()) {
			String name = e.getKey();
			MetalGlsl.Linked linked = e.getValue();
			Prog p = new Prog();
			p.name = name;
			p.id = Programs.createProgram(linked, l.functions.get(name));
			ProgramSet.Source src = l.sources.get(name);
			p.drawBuffers = drawBuffers(src);
			List<String> names = new ArrayList<String>();
			List<int[]> slots = new ArrayList<int[]>();
			for (MetalGlsl.Member m : linked.members) {
				String n = m.name.startsWith("mc_kw_") ? m.name.substring(6) : m.name;
				if (m.name.startsWith("mc_") && !m.name.startsWith("mc_kw_")) {
					continue;
				}
				int kind = m.type.startsWith("mat4") ? 2 : m.type.startsWith("mat3") ? 3 : m.type.startsWith("mat2") ? 4
					: m.type.startsWith("int") || m.type.startsWith("ivec") || m.type.startsWith("bool") || m.type.startsWith("bvec")
					|| m.type.startsWith("uint") || m.type.startsWith("uvec") ? 1 : 0;
				int n2 = Character.isDigit(m.type.charAt(m.type.length() - 1)) && kind < 2 ? m.type.charAt(m.type.length() - 1) - '0' : 1;
				names.add(n);
				slots.add(new int[] {Programs.glGetUniformLocation(p.id, n), kind, n2});
			}
			p.uNames = names.toArray(new String[0]);
			p.uLoc = new int[names.size()];
			p.uKind = new int[names.size()];
			p.uN = new int[names.size()];
			for (int i = 0; i < names.size(); i++) {
				p.uLoc[i] = slots.get(i)[0];
				p.uKind[i] = slots.get(i)[1];
				p.uN[i] = slots.get(i)[2];
			}
			p.waterShadow = linked.samplers.contains("watershadow");
			boolean gbuffers = name.startsWith("gbuffers_") || name.startsWith("shadow");
			Programs.glUseProgram(p.id);
			for (String s : linked.samplers) {
				String n = s.startsWith("mc_kw_") ? s.substring(6) : s;
				int unit = gbuffers ? gbufferUnit(n, p.waterShadow) : compositeUnit(n, p.waterShadow);
				if (unit >= 0) {
					Programs.glUniform1i(Programs.glGetUniformLocation(p.id, n), unit);
				}
			}
			for (int b = 0; b < BUFFERS; b++) {
				p.noFlip[b] = "false".equals(l.config.get("flip." + name + "." + bufferName(b))) || "false".equals(l.config.get("flip." + name + ".colortex" + b));
				p.mipmap[b] = src.directives.constBool(bufferName(b) + "MipmapEnabled", false) || src.directives.constBool("colortex" + b + "MipmapEnabled", false);
			}
			p.locEntityId = Programs.glGetUniformLocation(p.id, "entityId");
			p.locBlockEntityId = Programs.glGetUniformLocation(p.id, "blockEntityId");
			p.locEntityColor = Programs.glGetUniformLocation(p.id, "entityColor");
			p.locRenderStage = Programs.glGetUniformLocation(p.id, "renderStage");
			float[] sc = numbers(l.config.get("scale." + name), l.macros);
			if (sc.length > 0 && sc[0] > 0) {
				p.scale = Math.min(1, sc[0]);
				p.offX = sc.length > 2 ? sc[1] : 0;
				p.offY = sc.length > 2 ? sc[2] : 0;
			}
			Overrides ov = l.overrides.get(name);
			if (ov != null && (ov.blend != -1 || ov.attach != null || ov.alphaFunc != -1)) {
				Programs.setOverrides(p.id, ov.blend, ov.attach, ov.alphaFunc, ov.alphaRef);
			}
			Programs.addPipelines(p.id, l.pipelines.get(name));
			progs.put(name, p);
		}
		Programs.glUseProgram(0);
		for (int i = 0; i < 100; i++) {
			String n = i == 0 ? "" : String.valueOf(i);
			Prog d = progs.get("deferred" + n), c = progs.get("composite" + n), pr = progs.get("prepare" + n);
			if (d != null) {
				deferred.add(d);
			}
			if (c != null) {
				composite.add(c);
			}
			if (pr != null) {
				prepare.add(pr);
			}
		}
		targetsDirty = true;
		itemIds = l.itemIds;
		entityIds = l.entityIds;
		blockIdTable = l.blockTable;
		extendedTerrain = true;
		terrainGeneration++;
	}

	/** blend.<program>[.<buffer>]=<src> <dst> <srcA> <dstA> | off and alphaTest.<program>=<func> <ref> | off. */
	static Overrides overrides(String name, int[] drawBuffers, PackConfig config) {
		Overrides o = new Overrides();
		o.blend = blend(config.get("blend." + name));
		for (int k = 0; k < drawBuffers.length; k++) {
			int b = drawBuffers[k];
			String v = config.get("blend." + name + ".colortex" + b);
			if (v == null && b < 8) {
				v = config.get("blend." + name + "." + BUFFER_NAMES[b]);
			}
			if (v != null) {
				if (o.attach == null) {
					o.attach = new int[drawBuffers.length];
					Arrays.fill(o.attach, -1);
				}
				o.attach[k] = blend(v);
			}
		}
		String a = config.get("alphaTest." + name);
		if (a != null) {
			String[] t = a.trim().split("\\s+");
			o.alphaFunc = t[0].equalsIgnoreCase("off") || t[0].equalsIgnoreCase("false") ? 0 : glFunc(t[0]);
			try {
				o.alphaRef = t.length > 1 ? Float.parseFloat(t[1]) : 0;
			} catch (NumberFormatException e) {
				o.alphaFunc = -1;
			}
		}
		return o;
	}

	/** Encoded MGL blend state of a blend directive value, 0 = off, -1 = none/invalid. */
	static int blend(String v) {
		if (v == null) {
			return -1;
		}
		String[] t = v.trim().split("\\s+");
		if (t[0].equalsIgnoreCase("off")) {
			return 0;
		}
		if (t.length != 4) {
			return -1;
		}
		int[] f = new int[4];
		for (int i = 0; i < 4; i++) {
			f[i] = glFactor(t[i]);
			if (f[i] < 0) {
				return -1;
			}
		}
		return MGL.blendState(f[0], f[1], f[2], f[3]);
	}

	private static int glFactor(String n) {
		String[] names = {"ZERO", "ONE", "SRC_COLOR", "ONE_MINUS_SRC_COLOR", "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "DST_ALPHA", "ONE_MINUS_DST_ALPHA",
			"DST_COLOR", "ONE_MINUS_DST_COLOR", "SRC_ALPHA_SATURATE"};
		int[] gl = {0, 1, 0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306, 0x307, 0x308};
		for (int i = 0; i < names.length; i++) {
			if (names[i].equalsIgnoreCase(n)) {
				return gl[i];
			}
		}
		return -1;
	}

	private static int glFunc(String n) {
		String[] names = {"NEVER", "LESS", "EQUAL", "LEQUAL", "GREATER", "NOTEQUAL", "GEQUAL", "ALWAYS"};
		for (int i = 0; i < names.length; i++) {
			if (names[i].equalsIgnoreCase(n)) {
				return 0x200 + i;
			}
		}
		return 0x204;
	}

	/** Numbers of a directive value; option macros are substituted ("REFLECTION_RES REFLECTION_RES"). */
	static float[] numbers(String v, Map<String, String> macros) {
		if (v == null) {
			return new float[0];
		}
		String[] t = v.trim().split("\\s+");
		float[] out = new float[t.length];
		int n = 0;
		for (String s : t) {
			String m = macros != null ? macros.get(s) : null;
			try {
				out[n++] = Float.parseFloat((m != null ? m : s).replaceAll("[fF]$", ""));
			} catch (NumberFormatException e) {
				n--;
			}
		}
		return Arrays.copyOf(out, n);
	}

	static String bufferName(int b) {
		return b < 8 ? BUFFER_NAMES[b] : "colortex" + b;
	}

	private static void uninstall() {
		for (Prog p : progs.values()) {
			Programs.glDeleteProgram(p.id);
		}
		progs.clear();
		resolved.clear();
		deferred.clear();
		composite.clear();
		prepare.clear();
		cur = null;
		deleteTargets();
		if (extendedTerrain) {
			extendedTerrain = false;
			blockIdTable = null;
			itemIds = entityIds = null;
			terrainGeneration++;
		}
	}

	private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");

	/** Declared draw buffers, else the gl_FragData outputs the shader writes (0..max), else buffer 0. */
	private static int[] drawBuffers(ProgramSet.Source s) {
		int[] d = s.directives.drawBuffers;
		if (d == null) {
			int max = 0;
			Matcher m = FRAG_DATA.matcher(s.fragment);
			while (m.find()) {
				max = Math.max(max, Integer.parseInt(m.group(1)));
			}
			d = new int[max + 1];
			for (int i = 0; i <= max; i++) {
				d[i] = i;
			}
		}
		int n = 0;
		int[] out = new int[d.length];
		for (int b : d) {
			if (b >= 0 && b < BUFFERS && n < 8) {
				out[n++] = b;
			}
		}
		return Arrays.copyOf(out, n);
	}

	private static final String[] BUFFER_NAMES = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	/** Texture unit of a sampler in gbuffers/shadow programs (OptiFine's documented table), -1 if none. */
	static int gbufferUnit(String n, boolean waterShadow) {
		switch (n) {
			case "texture": case "tex": case "gtexture": return 0;
			case "lightmap": return 1;
			case "normals": return 2;
			case "specular": return 3;
			case "shadow": return waterShadow ? 5 : 4;
			case "watershadow": case "shadowtex0": return 4;
			case "shadowtex1": return 5;
			case "depthtex0": return 6;
			case "gaux1": case "colortex4": return 7;
			case "gaux2": case "colortex5": return 8;
			case "gaux3": case "colortex6": return 9;
			case "gaux4": case "colortex7": return 10;
			case "depthtex1": return 11;
			case "shadowcolor": case "shadowcolor0": return 13;
			case "shadowcolor1": return 14;
			case "noisetex": return 15;
			default: return highColortex(n);
		}
	}

	/** colortex8-15 sit on units 16-23 in every stage. */
	private static int highColortex(String n) {
		int b = Directives.bufferIndex(n, "");
		return b >= 8 && b < BUFFERS ? 8 + b : -1;
	}

	/** Texture unit of a sampler in deferred/composite/final programs, -1 if none. */
	static int compositeUnit(String n, boolean waterShadow) {
		switch (n) {
			case "gcolor": case "colortex0": return 0;
			case "gdepth": case "colortex1": return 1;
			case "gnormal": case "colortex2": return 2;
			case "composite": case "colortex3": return 3;
			case "shadow": return waterShadow ? 5 : 4;
			case "watershadow": case "shadowtex0": return 4;
			case "shadowtex1": return 5;
			case "gdepthtex": case "depthtex0": return 6;
			case "gaux1": case "colortex4": return 7;
			case "gaux2": case "colortex5": return 8;
			case "gaux3": case "colortex6": return 9;
			case "gaux4": case "colortex7": return 10;
			case "depthtex1": return 11;
			case "depthtex2": return 12;
			case "shadowcolor": case "shadowcolor0": return 13;
			case "shadowcolor1": return 14;
			case "noisetex": return 15;
			default: return highColortex(n);
		}
	}

	// ---------------------------------------------------------------- render targets

	private static boolean targetsDirty = true;
	private static int width, height, shadowRes;
	private static final int[] colorA = new int[BUFFERS], colorB = new int[BUFFERS], formats = new int[BUFFERS], bufW = new int[BUFFERS], bufH = new int[BUFFERS];
	private static final boolean[] flipped = new boolean[BUFFERS], used = new boolean[BUFFERS], clear = new boolean[BUFFERS], mipmapped = new boolean[BUFFERS];
	private static final float[][] clearColor = new float[BUFFERS][];
	private static int depth0, depth1, depth2, compositeFb, shadowFb, shadowDepth0, shadowDepth1, noise, defNormal, defSpecular;
	private static final int[] shadowColor = new int[2];
	/** Custom textures from texture.<stage>.<name>: stage -> unit -> texture. */
	private static final Map<String, Map<Integer, Integer>> customTextures = new HashMap<String, Map<Integer, Integer>>();

	private static int main(int b) {
		return flipped[b] ? colorB[b] : colorA[b];
	}

	private static int alt(int b) {
		return flipped[b] ? colorA[b] : colorB[b];
	}

	private static void ensureTargets(int w, int h) {
		if (!targetsDirty && w == width && h == height) {
			return;
		}
		deleteTargets();
		targetsDirty = false;
		width = w;
		height = h;
		ProgramSet set = cur.set;
		Arrays.fill(used, false);
		used[0] = true;
		boolean gdepthSampled = false;
		boolean[] gbufferWritten = new boolean[BUFFERS];
		Arrays.fill(mipmapped, false);
		for (Prog p : progs.values()) {
			boolean gb = p.name.startsWith("gbuffers_");
			for (int b : p.drawBuffers) {
				used[b] = true;
				gbufferWritten[b] |= gb;
			}
			for (int b = 0; b < BUFFERS; b++) {
				mipmapped[b] |= p.mipmap[b];
			}
		}
		for (ProgramSet.Source s : cur.sources.values()) {
			for (String n : s.directives.samplers) {
				int b = Directives.bufferIndex(n, "");
				if (b >= 0 && b < BUFFERS) {
					used[b] = true;
				}
				gdepthSampled |= n.equals("gdepth");
			}
		}
		for (int b = 0; b < BUFFERS; b++) {
			flipped[b] = false;
			String name = set.format(b);
			formats[b] = internalFormat(name != null ? name : b == 1 && gdepthSampled ? "RGBA32F" : "RGBA8");
			String c = set.setting(bufferName(b) + "Clear");
			if (c == null) {
				c = set.setting("colortex" + b + "Clear");
			}
			clear[b] = !"false".equals(c);
			String cc = set.setting("colortex" + b + "ClearColor");
			if (cc == null) {
				cc = set.setting(bufferName(b) + "ClearColor");
			}
			clearColor[b] = cc != null ? vec4(cc) : b == 0 ? null : b == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
			// size.buffer.<buffer>=<w> <h> (pixels, or fractions of the screen), for buffers only deferred/composite passes draw
			bufW[b] = w;
			bufH[b] = h;
			String sz = cur.config.get("size.buffer." + bufferName(b));
			if (sz == null) {
				sz = cur.config.get("size.buffer.colortex" + b);
			}
			float[] dim = numbers(sz, cur.macros);
			if (dim.length >= 2 && !gbufferWritten[b] && dim[0] > 0 && dim[1] > 0) {
				boolean rel = dim[0] != (int)dim[0] || dim[1] != (int)dim[1] || dim[0] <= 1 && dim[1] <= 1;
				bufW[b] = Math.max(1, rel ? Math.round(w * Math.min(dim[0], 4)) : (int)dim[0]);
				bufH[b] = Math.max(1, rel ? Math.round(h * Math.min(dim[1], 4)) : (int)dim[1]);
			}
			if (used[b]) {
				colorA[b] = texture(bufW[b], bufH[b], formats[b], true, mipmapped[b]);
				colorB[b] = texture(bufW[b], bufH[b], formats[b], true, mipmapped[b]);
			}
		}
		depth0 = texture(w, h, 0x1902, false, false);
		depth1 = texture(w, h, 0x1902, false, false);
		depth2 = texture(w, h, 0x1902, false, false);
		gbufferFb = MGL.glGenFramebuffers();
		compositeFb = MGL.glGenFramebuffers();
		// shadow map
		shadowRes = Math.max(16, Math.min(8192, (int)set.settingFloat("shadowMapResolution", 1024)));
		if (progs.containsKey("shadow")) {
			shadowFb = MGL.glGenFramebuffers();
			shadowDepth0 = texture(shadowRes, shadowRes, 0x1902, true, false);
			shadowDepth1 = texture(shadowRes, shadowRes, 0x1902, true, false);
			for (int i = 0; i < 2; i++) {
				String f = set.format(100 + i);
				shadowColor[i] = texture(shadowRes, shadowRes, internalFormat(f != null ? f : "RGBA8"), true, false);
			}
			boolean hw = "true".equals(set.setting("shadowHardwareFiltering"));
			for (int t : new int[] {shadowDepth0, shadowDepth1}) {
				if (hw || "true".equals(set.setting(t == shadowDepth0 ? "shadowHardwareFiltering0" : "shadowHardwareFiltering1"))) {
					MGL.glBindTexture(TEX, t);
					MGL.glTexParameteri(TEX, 0x884C, 0x884E); // GL_TEXTURE_COMPARE_MODE = GL_COMPARE_R_TO_TEXTURE
					MGL.glTexParameteri(TEX, 0x884D, 0x203); // GL_LEQUAL
				}
			}
			MGL.glBindFramebuffer(FB, shadowFb);
			MGL.glFramebufferTexture2D(FB, COLOR0, TEX, shadowColor[0], 0);
			MGL.glFramebufferTexture2D(FB, COLOR0 + 1, TEX, shadowColor[1], 0);
			MGL.glFramebufferTexture2D(FB, DEPTH_ATT, TEX, shadowDepth0, 0);
		}
		noise = loadNoise(set);
		defNormal = solid(0x7f7fffff);
		defSpecular = solid(0x00000000);
		loadCustomTextures();
		LOG.info("Graft: shader targets {}x{} buffers {} formats {} shadow {}", w, h, Arrays.toString(used), Arrays.toString(formats),
			shadowFb != 0 ? shadowRes : 0);
	}

	private static void deleteTargets() {
		for (int b = 0; b < BUFFERS; b++) {
			if (colorA[b] != 0) {
				MGL.glDeleteTextures(colorA[b]);
				MGL.glDeleteTextures(colorB[b]);
			}
			colorA[b] = colorB[b] = 0;
		}
		for (int t : new int[] {depth0, depth1, depth2, shadowDepth0, shadowDepth1, shadowColor[0], shadowColor[1], noise, defNormal, defSpecular}) {
			if (t != 0) {
				MGL.glDeleteTextures(t);
			}
		}
		for (Map<Integer, Integer> m : customTextures.values()) {
			for (int t : m.values()) {
				MGL.glDeleteTextures(t);
			}
		}
		customTextures.clear();
		depth0 = depth1 = depth2 = shadowDepth0 = shadowDepth1 = shadowColor[0] = shadowColor[1] = noise = defNormal = defSpecular = 0;
		for (int fb : new int[] {gbufferFb, compositeFb, shadowFb}) {
			if (fb != 0) {
				MGL.glDeleteFramebuffers(fb);
			}
		}
		gbufferFb = compositeFb = shadowFb = 0;
		targetsDirty = true;
	}

	private static int texture(int w, int h, int internal, boolean linear, boolean mipmaps) {
		int t = MGL.glGenTextures();
		MGL.glBindTexture(TEX, t);
		MGL.glTexParameteri(TEX, 0x2801, mipmaps ? 0x2703 : linear ? 0x2601 : 0x2600);
		MGL.glTexParameteri(TEX, 0x2800, linear ? 0x2601 : 0x2600);
		MGL.glTexParameteri(TEX, 0x2802, 0x812F);
		MGL.glTexParameteri(TEX, 0x2803, 0x812F);
		MGL.glTexParameteri(TEX, 0x813D, mipmaps ? 31 - Integer.numberOfLeadingZeros(Math.max(w, h)) : 0); // mip levels
		MGL.glTexImage2D(TEX, 0, internal, w, h, 0, internal == 0x1902 ? 0x1902 : 0x1908, internal == 0x1902 ? 0x1406 : 0x1401, (ByteBuffer)null);
		return t;
	}

	private static int solid(int rgba) {
		int t = MGL.glGenTextures();
		MGL.glBindTexture(TEX, t);
		MGL.glTexParameteri(TEX, 0x813D, 0);
		ByteBuffer b = ByteBuffer.allocateDirect(4);
		b.put(0, (byte)(rgba >>> 24)).put(1, (byte)(rgba >>> 16)).put(2, (byte)(rgba >>> 8)).put(3, (byte)rgba);
		MGL.glTexImage2D(TEX, 0, 0x8058, 1, 1, 0, 0x1908, 0x1401, b);
		return t;
	}

	private static int loadNoise(ProgramSet set) {
		String path = cur.config.get("texture.noise");
		if (path != null) {
			int t = loadPackTexture(path);
			if (t != 0) {
				return t;
			}
		}
		int res = Math.max(1, Math.min(4096, (int)set.settingFloat("noiseTextureResolution", 256)));
		ByteBuffer b = ByteBuffer.allocateDirect(res * res * 4);
		java.util.Random r = new java.util.Random(0x6a4f0e4bL);
		for (int i = 0; i < res * res * 4; i++) {
			b.put(i, (byte)r.nextInt(256));
		}
		int t = MGL.glGenTextures();
		MGL.glBindTexture(TEX, t);
		MGL.glTexParameteri(TEX, 0x813D, 0);
		MGL.glTexParameteri(TEX, 0x2801, 0x2601);
		MGL.glTexParameteri(TEX, 0x2800, 0x2601);
		MGL.glTexImage2D(TEX, 0, 0x8058, res, res, 0, 0x1908, 0x1401, b);
		return t;
	}

	/** texture.<gbuffers|deferred|composite>.<unit name>=<pack path> (2D images only). */
	private static void loadCustomTextures() {
		for (String key : cur.config.props.stringPropertyNames()) {
			String[] k = key.split("\\.");
			if (k.length < 3 || !k[0].equals("texture") || !(k[1].equals("gbuffers") || k[1].equals("deferred") || k[1].equals("composite"))) {
				continue;
			}
			String value = cur.config.get(key);
			if (value.contains(" ") || value.startsWith("minecraft:")) {
				continue; // ponytail: raw (.dat) and resource-pack/dynamic textures not loaded yet
			}
			int unit = k[1].equals("gbuffers") ? gbufferUnit(k[2], false) : compositeUnit(k[2], false);
			int t = unit >= 0 ? loadPackTexture(value) : 0;
			if (t != 0) {
				Map<Integer, Integer> m = customTextures.get(k[1]);
				if (m == null) {
					customTextures.put(k[1], m = new HashMap<Integer, Integer>());
				}
				m.put(unit, t);
			}
		}
	}

	private static int loadPackTexture(String path) {
		byte[] data = cur.pack.bytes(path);
		if (data == null) {
			return 0;
		}
		try {
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
			if (img == null) {
				return 0;
			}
			int w = img.getWidth(), h = img.getHeight();
			int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
			ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4);
			for (int y = 0; y < h; y++) { // GL rows go bottom-up
				for (int x = 0; x < w; x++) {
					int c = argb[(h - 1 - y) * w + x], o = (y * w + x) * 4;
					b.put(o, (byte)(c >> 16)).put(o + 1, (byte)(c >> 8)).put(o + 2, (byte)c).put(o + 3, (byte)(c >>> 24));
				}
			}
			String meta = new String(cur.pack.bytes(path + ".mcmeta") != null ? cur.pack.bytes(path + ".mcmeta") : new byte[0], "UTF-8");
			boolean blur = meta.contains("\"blur\"") && meta.matches("(?s).*\"blur\"\\s*:\\s*true.*"), clamp = meta.matches("(?s).*\"clamp\"\\s*:\\s*true.*");
			int t = MGL.glGenTextures();
			MGL.glBindTexture(TEX, t);
			MGL.glTexParameteri(TEX, 0x813D, 0);
			MGL.glTexParameteri(TEX, 0x2801, blur ? 0x2601 : 0x2600);
			MGL.glTexParameteri(TEX, 0x2800, blur ? 0x2601 : 0x2600);
			MGL.glTexParameteri(TEX, 0x2802, clamp ? 0x812F : 0x2901);
			MGL.glTexParameteri(TEX, 0x2803, clamp ? 0x812F : 0x2901);
			MGL.glTexImage2D(TEX, 0, 0x8058, w, h, 0, 0x1908, 0x1401, b);
			return t;
		} catch (Exception e) {
			log("texture " + path + ": " + e);
			return 0;
		}
	}

	private static float[] vec4(String s) {
		Matcher m = Pattern.compile("-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").matcher(s);
		float[] v = new float[4];
		int n = 0;
		while (m.find() && n < 4) {
			v[n++] = Float.parseFloat(m.group());
		}
		for (int i = n; i < 4 && n > 0; i++) {
			v[i] = n == 1 ? v[0] : i == 3 ? 1 : 0;
		}
		return v;
	}

	/** GL internal format of an OptiFine format name (formats Metal lacks fall back to the nearest MGL supports). */
	static int internalFormat(String name) {
		switch (name.trim().toUpperCase()) {
			case "R8": case "R8_SNORM": case "R8I": case "R8UI": return 0x8229;
			case "RG8": case "RG8_SNORM": case "RG8I": case "RG8UI": return 0x822B;
			case "R16": case "R16_SNORM": case "R16I": case "R16UI": return 0x822A;
			case "RG16": case "RG16_SNORM": case "RG16I": case "RG16UI": return 0x822C;
			case "RGB16": case "RGBA16": case "RGB16_SNORM": case "RGBA16_SNORM": case "RGBA16I": case "RGBA16UI": case "RGB16I": case "RGB16UI": return 0x805B;
			case "R16F": return 0x822D;
			case "RG16F": return 0x822F;
			case "RGB16F": case "RGBA16F": return 0x881A;
			case "R32F": case "R32I": case "R32UI": return 0x822E;
			case "RG32F": case "RG32I": case "RG32UI": return 0x8230;
			case "RGB32F": case "RGBA32F": case "RGB32I": case "RGBA32I": case "RGB32UI": case "RGBA32UI": return 0x8814;
			case "RGB10_A2": return 0x8059;
			case "R11F_G11F_B10F": return 0x8C3A;
			default: return 0x8058; // RGBA8 and everything 8-bit / packed
		}
	}

	// ---------------------------------------------------------------- frame hooks

	private static String stageProgram;
	private static boolean textured = true, clearedThisPass, depthCopied, cameraCaptured;
	private static Prog bound;
	private static int frameNo;
	private static final IntBuffer ints = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
	private static final FloatBuffer floats = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

	/** Start of the world pass (frame state filled). Returns whether shaders render this frame. */
	public static boolean beginWorld() {
		active = false;
		MGL.handDepth = 1f;
		if (disabledReason != null) {
			return false;
		}
		String world = "world" + frame.dimension;
		if (selected == null) {
			if (cur != null) {
				uninstall();
			}
			return false;
		}
		String key = selected + "|" + world + "|" + optionsVersion;
		if (cur == null || !cur.key.equals(key)) {
			Loaded r = ready;
			if (r != null && r.key.equals(key)) {
				ready = null;
				install(r);
			} else if (!key.equals(loadingKey)) {
				startLoad(key, selected, world);
			}
			if (cur == null || !cur.key.equals(key)) {
				return false;
			}
		}
		MGL.glGetInteger(0xBA2, ints); // viewport
		int w = Math.max(1, ints.get(2)), h = Math.max(1, ints.get(3));
		ensureTargets(w, h);
		mainFb = MGL.glGetInteger(0x8CA6);
		frameNo++;
		clearedThisPass = depthCopied = cameraCaptured = false;
		stageProgram = null;
		bound = null;
		textured = true;
		resolved.clear();
		// gbuffers draw into the main side of every buffer
		attachGbuffers();
		MGL.glFramebufferTexture2D(FB, DEPTH_ATT, TEX, depth0, 0);
		setDrawBuffers(BUFFER0);
		drawEntityId = drawBlockEntityId = -1;
		drawStage = 0;
		active = true;
		return true;
	}

	/** Binds the gbuffers framebuffer with the main side of every buffer (gbuffers write main). */
	private static void attachGbuffers() {
		MGL.glBindFramebuffer(FB, gbufferFb);
		for (int b = 0; b < BUFFERS; b++) {
			MGL.glFramebufferTexture2D(FB, COLOR0 + b, TEX, used[b] && bufW[b] == width && bufH[b] == height ? main(b) : 0, 0);
		}
	}

	/** A profiler section of the world pass started: the stage tag. */
	public static void section(String name) {
		if (!active || inShadow) {
			return;
		}
		String program = null;
		if (!cameraCaptured && (name.equals("sky") || name.equals("terrain"))) { // no "sky" section below 4 chunks render distance
			cameraCaptured = true;
			captureCamera();
			shadowPass();
			if (!prepare.isEmpty()) {
				runPasses(prepare, "prepare");
			}
		}
		int stage; // MC_RENDER_STAGE_* (renderStage uniform)
		switch (name) {
			case "sky": program = "gbuffers_skybasic"; stage = 1; break;
			case "clouds": program = "gbuffers_clouds"; stage = 20; break;
			case "terrain": program = "gbuffers_terrain"; stage = 8; break;
			case "entities": case "global": program = "gbuffers_entities"; stage = 11; break;
			case "blockentities": program = "gbuffers_block"; stage = 12; break;
			case "outline": program = "gbuffers_basic"; stage = 14; break;
			case "destroyProgress": program = "gbuffers_damagedblock"; stage = 13; break;
			case "litParticles": case "forge_render_last": case "FRenderLast": program = "gbuffers_textured_lit"; stage = 19; break;
			case "particles": program = "gbuffers_textured"; stage = 19; break;
			case "weather": program = "gbuffers_weather"; stage = 21; break;
			case "translucent": case "water": // 1.7.10 calls it water
				copyDepth();
				runDeferred();
				program = "gbuffers_water";
				stage = 17;
				break;
			case "hand":
				program = "gbuffers_hand";
				stage = 16;
				MGL.handDepth = handDepth; // the hand keeps the world's depth buffer: squeezed in front of it, as MC_HAND_DEPTH says
				break;
			default: return;
		}
		drawStage = stage;
		drawEntityId = drawBlockEntityId = -1;
		drawEntityColor[3] = 0;
		stageProgram = program;
		if (!bindStage() && bound != null) {
			applyDraw(bound);
		}
	}

	private static final float handDepth = 0.125f;
	private static int drawEntityId = -1, drawBlockEntityId = -1, drawStage;
	private static final float[] drawEntityColor = new float[4];
	private static Prog shadowProg;

	/** Per-draw entity uniforms (entityId from entity.properties, entityColor = hurt / creeper flash), before an entity renders. */
	public static void setEntity(int id, float r, float g, float b, float a) {
		if (drawEntityId == id && drawEntityColor[0] == r && drawEntityColor[1] == g && drawEntityColor[2] == b && drawEntityColor[3] == a) {
			return;
		}
		drawEntityId = id;
		drawEntityColor[0] = r;
		drawEntityColor[1] = g;
		drawEntityColor[2] = b;
		drawEntityColor[3] = a;
		Prog p = inShadow ? shadowProg : bound;
		if (active && p != null) {
			applyDraw(p);
		}
	}

	/** Per-draw blockEntityId (block.properties id of the block entity's block), before a block entity renders. */
	public static void setBlockEntity(int id) {
		if (drawBlockEntityId == id) {
			return;
		}
		drawBlockEntityId = id;
		Prog p = inShadow ? shadowProg : bound;
		if (active && p != null) {
			applyDraw(p);
		}
	}

	/** Writes the per-draw uniforms into the program in use (Programs keeps them in its block; no allocation). */
	private static void applyDraw(Prog p) {
		if (p.locEntityId >= 0) {
			Programs.glUniform1i(p.locEntityId, drawEntityId);
		}
		if (p.locBlockEntityId >= 0) {
			Programs.glUniform1i(p.locBlockEntityId, drawBlockEntityId);
		}
		if (p.locEntityColor >= 0) {
			Programs.glUniform4f(p.locEntityColor, drawEntityColor[0], drawEntityColor[1], drawEntityColor[2], drawEntityColor[3]);
		}
		if (p.locRenderStage >= 0) {
			Programs.glUniform1i(p.locRenderStage, inShadow ? 0 : drawStage);
		}
	}

	/** A block.properties / item.properties / entity.properties flag of the installed pack's shaders.properties. */
	public static boolean flag(String key, boolean def) {
		return cur != null ? cur.config.flag(key, def) : def;
	}

	/** The pack's sunPathRotation while a pack renders the world, else 0 (vanilla sun/moon rotation). */
	public static float sunPathRotation() {
		return active && uniforms != null ? uniforms.sunPathRotation : 0;
	}

	/** GL_TEXTURE_2D toggled on unit 0 during the world pass (sky: basic vs textured; others: basic without texture). */
	public static void textureToggled(boolean on) {
		if (on != textured) {
			textured = on;
			if (!inShadow) {
				bindStage();
			}
		}
	}

	/** The world pass clears: the first one becomes the buffer clears, the hand's depth clear is skipped. Returns whether to run it. */
	public static boolean clear(int mask) {
		if (!active) {
			return true;
		}
		if (!clearedThisPass) {
			clearedThisPass = true;
			clearTargets();
			return false;
		}
		return !"gbuffers_hand".equals(stageProgram) || (mask & ~0x100) != 0;
	}

	/** End of the world pass (after the hand): composites and final into Minecraft's framebuffer. */
	public static void endWorld() {
		if (!active) {
			return;
		}
		MGL.savePipelineArchive(false);
		active = false;
		MGL.handDepth = 1f;
		if (!depthCopied) {
			copyDepth();
			runDeferred();
		}
		// centerDepthSmooth: the depth at the screen centre (MGL returns last frame's read: no stall)
		int read = MGL.glGetInteger(0x8CAA);
		MGL.glBindFramebuffer(READ_FB, gbufferFb);
		floats.clear();
		MGL.glReadPixels(width / 2, height / 2, 1, 1, 0x1902, 0x1406, floats);
		uniforms.centerDepth = floats.get(0);
		MGL.glBindFramebuffer(READ_FB, read);
		Programs.glUseProgram(0);
		bound = null;
		int unit = MGL.glGetInteger(0x84E0) - UNIT0;
		int[] saved = new int[2];
		for (int u = 0; u < 2; u++) {
			MGL.glActiveTexture(UNIT0 + u);
			saved[u] = MGL.glGetInteger(0x8069);
		}
		fullscreenBegin();
		for (Prog p : composite) {
			pass(p, "composite");
		}
		Prog fin = progs.get("final");
		if (fin != null) {
			MGL.glBindFramebuffer(FB, mainFb);
			Programs.glUseProgram(fin.id);
			uniforms(fin);
			bindUnits("composite", fin.waterShadow);
			MGL.glViewport(0, 0, width, height);
			quad();
		}
		fullscreenEnd();
		Programs.glUseProgram(0);
		for (int u = 0; u < 2; u++) {
			MGL.glActiveTexture(UNIT0 + u);
			MGL.glBindTexture(TEX, saved[u]);
		}
		MGL.glActiveTexture(UNIT0 + unit);
		MGL.glBindFramebuffer(FB, mainFb);
	}

	/** Binds the stage's program; returns whether the program changed (its per-draw uniforms are then written too). */
	private static boolean bindStage() {
		String name = stageProgram;
		if (name == null) {
			return false;
		}
		if (!textured && !name.equals("gbuffers_terrain") && !name.equals("gbuffers_water") && !name.equals("gbuffers_block")) {
			name = name.equals("gbuffers_skybasic") ? "gbuffers_skybasic" : "gbuffers_basic";
		} else if (textured && name.equals("gbuffers_skybasic")) {
			name = "gbuffers_skytextured";
		}
		Prog p = resolve(name);
		if (p == bound) {
			return false;
		}
		bound = p;
		Programs.glUseProgram(p == null ? 0 : p.id);
		setDrawBuffers(p == null ? BUFFER0 : p.drawBuffers);
		if (p != null) {
			uniforms(p);
			applyDraw(p);
		}
		return true;
	}

	private static Prog resolve(String name) {
		if (resolved.containsKey(name)) {
			return resolved.get(name);
		}
		Prog p = null;
		for (String n = name; n != null && p == null; n = ProgramSet.FALLBACK.get(n)) {
			p = progs.get(n);
		}
		resolved.put(name, p);
		return p;
	}

	private static void setDrawBuffers(int[] buffers) {
		ints.clear();
		for (int b : buffers) {
			ints.put(COLOR0 + b);
		}
		ints.flip();
		MGL.glDrawBuffers(ints);
		ints.clear();
	}

	/** Copies this frame's uniform values into a program (once per frame; Programs keeps them in the program's block). */
	private static void uniforms(Prog p) {
		if (p.frame == frameNo) {
			return;
		}
		p.frame = frameNo;
		for (int i = 0; i < p.uNames.length; i++) {
			double[] v = uniforms.get(p.uNames[i]);
			int loc = p.uLoc[i];
			if (v == null || loc < 0) {
				continue;
			}
			switch (p.uKind[i]) {
				case 0:
					switch (p.uN[i]) {
						case 1: Programs.glUniform1f(loc, (float)v[0]); break;
						case 2: Programs.glUniform2f(loc, (float)v[0], (float)at(v, 1)); break;
						case 3: Programs.glUniform3f(loc, (float)v[0], (float)at(v, 1), (float)at(v, 2)); break;
						default: Programs.glUniform4f(loc, (float)v[0], (float)at(v, 1), (float)at(v, 2), (float)at(v, 3));
					}
					break;
				case 1:
					switch (p.uN[i]) {
						case 1: Programs.glUniform1i(loc, (int)Math.round(v[0])); break;
						case 2: Programs.glUniform2i(loc, (int)Math.round(v[0]), (int)Math.round(at(v, 1))); break;
						case 3: Programs.glUniform3i(loc, (int)Math.round(v[0]), (int)Math.round(at(v, 1)), (int)Math.round(at(v, 2))); break;
						default: Programs.glUniform4i(loc, (int)Math.round(v[0]), (int)Math.round(at(v, 1)), (int)Math.round(at(v, 2)), (int)Math.round(at(v, 3)));
					}
					break;
				case 2:
					if (v.length >= 16) {
						floats.clear();
						for (int k = 0; k < 16; k++) {
							floats.put((float)v[k]);
						}
						floats.flip();
						Programs.glUniformMatrix4(loc, false, floats);
					}
					break;
				default: // mat3/mat2 uniforms from custom expressions are not produced
			}
		}
	}

	private static double at(double[] v, int i) {
		return i < v.length ? v[i] : 0;
	}

	// ---------------------------------------------------------------- stages

	/** gbufferModelView/Projection: the camera matrices as they are when the sky starts (after the camera transform). */
	private static void captureCamera() {
		floats.clear();
		MGL.glGetFloat(0xBA6, floats);
		for (int i = 0; i < 16; i++) {
			uniforms.modelView[i] = floats.get(i);
		}
		MGL.glGetFloat(0xBA7, floats);
		for (int i = 0; i < 16; i++) {
			uniforms.projection[i] = floats.get(i);
		}
		uniforms.update(frame, cur.set, width, height);
		// gbuffers samplers on units 2-15 (Minecraft only uses 0 and 1)
		bindUnits("gbuffers", false);
	}

	private static final float[][] clearValues = new float[8][];

	/** The buffer clears as load actions: one render pass per 8 buffers (Metal's attachment limit), depth with the first. */
	private static void clearTargets() {
		int n = 0;
		int[] group = new int[8];
		boolean depth = true;
		for (int i = 0; i <= BUFFERS; i++) {
			if (i < BUFFERS && used[i] && clear[i] && bufW[i] == width && bufH[i] == height) {
				clearValues[n] = clearColor[i]; // null = the game's clear color (the fog color for colortex0)
				group[n++] = i;
			}
			if (n == 8 || i == BUFFERS && (n > 0 || depth)) {
				setDrawBuffers(n == 0 ? BUFFER0 : Arrays.copyOf(group, n));
				if (n == 0) {
					MGL.glClear(0x100);
				} else {
					MGL.clearBuffers(clearValues, depth);
				}
				depth = false;
				n = 0;
			}
		}
		Arrays.fill(clearValues, null);
		// scaled buffers (size.buffer) are not gbuffers attachments: cleared on their own
		for (int i = 0; i < BUFFERS; i++) {
			if (used[i] && clear[i] && (bufW[i] != width || bufH[i] != height)) {
				MGL.glBindFramebuffer(FB, compositeFb);
				MGL.glFramebufferTexture2D(FB, COLOR0, TEX, main(i), 0);
				for (int k = 1; k < 8; k++) {
					MGL.glFramebufferTexture2D(FB, COLOR0 + k, TEX, 0, 0);
				}
				setDrawBuffers(BUFFER0);
				clearValues[0] = clearColor[i];
				MGL.clearBuffers(clearValues, false);
				clearValues[0] = null;
				MGL.glBindFramebuffer(FB, gbufferFb);
			}
		}
		setDrawBuffers(bound == null ? BUFFER0 : bound.drawBuffers);
	}

	/** Units 2-15 for gbuffers/shadow programs, or 0-15 for composite/deferred/final passes. */
	private static void bindUnits(String stage, boolean waterShadow) {
		int unit = MGL.glGetInteger(0x84E0) - UNIT0;
		boolean gb = stage.equals("gbuffers");
		Map<Integer, Integer> custom = customTextures.get(stage);
		for (int u = gb ? 2 : 0; u < 24; u++) {
			int t;
			switch (u) {
				case 0: case 1: case 2: case 3: t = gb ? (u == 2 ? defNormal : u == 3 ? defSpecular : 0) : main(u); break;
				case 4: t = shadowDepth0; break;
				case 5: t = shadowDepth1; break;
				case 6: t = gb ? 0 : depth0; break; // gbuffers draw into depthtex0
				case 7: case 8: case 9: case 10: t = main(u - 3); break;
				case 11: t = depth1; break;
				case 12: t = gb ? 0 : depth2; break;
				case 13: t = shadowColor[0]; break;
				case 14: t = shadowColor[1]; break;
				case 15: t = noise; break;
				default: t = main(u - 8); // colortex8-15
			}
			if (custom != null && custom.containsKey(u)) {
				t = custom.get(u);
			}
			MGL.glActiveTexture(UNIT0 + u);
			MGL.glBindTexture(TEX, t);
		}
		MGL.glActiveTexture(UNIT0 + unit);
	}

	/** depthtex1/depthtex2 = depth before translucent terrain (and before the hand, which comes later). */
	private static void copyDepth() {
		depthCopied = true;
		int unit = MGL.glGetInteger(0x84E0) - UNIT0, read = MGL.glGetInteger(0x8CAA);
		MGL.glBindFramebuffer(READ_FB, gbufferFb);
		MGL.glActiveTexture(UNIT0 + 15);
		for (int t : new int[] {depth1, depth2}) {
			MGL.glBindTexture(TEX, t);
			MGL.glCopyTexSubImage2D(TEX, 0, 0, 0, 0, 0, width, height);
		}
		MGL.glBindTexture(TEX, noise);
		MGL.glActiveTexture(UNIT0 + unit);
		MGL.glBindFramebuffer(READ_FB, read);
	}

	private static void runDeferred() {
		if (!deferred.isEmpty()) {
			runPasses(deferred, "deferred");
		}
	}

	/** Deferred or prepare programs in the middle of the gbuffers: full-screen passes, then back to the gbuffers' state. */
	private static void runPasses(List<Prog> list, String stage) {
		int unit = MGL.glGetInteger(0x84E0) - UNIT0;
		int[] saved = new int[2];
		for (int u = 0; u < 2; u++) {
			MGL.glActiveTexture(UNIT0 + u);
			saved[u] = MGL.glGetInteger(0x8069);
		}
		Prog was = bound;
		fullscreenBegin();
		for (Prog p : list) {
			pass(p, stage);
		}
		fullscreenEnd();
		for (int u = 0; u < 2; u++) {
			MGL.glActiveTexture(UNIT0 + u);
			MGL.glBindTexture(TEX, saved[u]);
		}
		MGL.glActiveTexture(UNIT0 + unit);
		// back to the gbuffers (deferred flips changed which side is main for buffers they wrote)
		attachGbuffers();
		bindUnits("gbuffers", false);
		bound = null;
		if (was != null) {
			bound = null;
			Programs.glUseProgram(was.id);
			setDrawBuffers(was.drawBuffers);
			bound = was;
		} else {
			Programs.glUseProgram(0);
			setDrawBuffers(BUFFER0);
		}
	}

	private static final int[] savedViewport = new int[4];

	private static void fullscreenBegin() {
		MGL.glGetInteger(0xBA2, ints);
		for (int i = 0; i < 4; i++) {
			savedViewport[i] = ints.get(i);
		}
		MGL.glPushAttrib(0xFFFFF);
		MGL.glDisable(0xBE2); // blend
		MGL.glDisable(0xB71); // depth test
		MGL.glDisable(0xBC0); // alpha test
		MGL.glDisable(0xB44); // cull
		MGL.glDisable(0xB60); // fog
		MGL.glDepthMask(false);
		MGL.glMatrixMode(0x1701);
		MGL.glPushMatrix();
		MGL.glLoadIdentity();
		MGL.glOrtho(0, 1, 0, 1, -1, 1);
		MGL.glMatrixMode(0x1700);
		MGL.glPushMatrix();
		MGL.glLoadIdentity();
		MGL.glViewport(0, 0, width, height);
	}

	private static void fullscreenEnd() {
		MGL.glMatrixMode(0x1701);
		MGL.glPopMatrix();
		MGL.glMatrixMode(0x1700);
		MGL.glPopMatrix();
		MGL.glPopAttrib();
		MGL.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
	}

	/** One deferred/composite program: reads main, writes alt of its draw buffers, then flips them. */
	private static void pass(Prog p, String stage) {
		MGL.glBindFramebuffer(FB, compositeFb);
		for (int k = 0; k < 8; k++) {
			MGL.glFramebufferTexture2D(FB, COLOR0 + k, TEX, k < p.drawBuffers.length && used[p.drawBuffers[k]] ? alt(p.drawBuffers[k]) : 0, 0);
		}
		int[] att = new int[p.drawBuffers.length];
		for (int k = 0; k < att.length; k++) {
			att[k] = k;
		}
		setDrawBuffers(att);
		Programs.glUseProgram(p.id);
		uniforms(p);
		int unit = MGL.glGetInteger(0x84E0);
		for (int b = 0; b < BUFFERS; b++) {
			if (p.mipmap[b] && mipmapped[b] && used[b]) { // const bool <buffer>MipmapEnabled: mipmaps of what this pass reads
				MGL.glActiveTexture(UNIT0 + 15);
				MGL.glBindTexture(TEX, main(b));
				MGL.glGenerateMipmap(TEX);
			}
		}
		MGL.glActiveTexture(unit);
		bindUnits(stage, p.waterShadow);
		int b0 = p.drawBuffers.length > 0 ? p.drawBuffers[0] : 0, vw = bufW[b0], vh = bufH[b0];
		MGL.glViewport(Math.round(p.offX * vw), Math.round(p.offY * vh), Math.round(p.scale * vw), Math.round(p.scale * vh));
		quad();
		for (int b : p.drawBuffers) {
			if (!p.noFlip[b]) {
				flipped[b] = !flipped[b];
			}
		}
	}

	private static void quad() {
		MGL.glBegin(7);
		MGL.glTexCoord2f(0, 0);
		MGL.glVertex3f(0, 0, 0);
		MGL.glTexCoord2f(1, 0);
		MGL.glVertex3f(1, 0, 0);
		MGL.glTexCoord2f(1, 1);
		MGL.glVertex3f(1, 1, 0);
		MGL.glTexCoord2f(0, 1);
		MGL.glVertex3f(0, 1, 0);
		MGL.glEnd();
	}

	// ---------------------------------------------------------------- shadow pass

	private static void shadowPass() {
		Prog sp = progs.get("shadow");
		if (sp == null || shadowFb == 0 || shadowTerrain == null || !cur.config.flag("shadowTerrain", true)) {
			return;
		}
		float dist = cur.set.settingFloat("shadowDistance", 160), interval = cur.set.settingFloat("shadowIntervalSize", 2);
		shadowHalf = cur.set.settingFloat("shadowMapFov", 0) > 0 ? Float.MAX_VALUE / 4 : dist;
		// shadow view: looking down the sun/moon direction, snapped to a grid so shadows do not swim
		float[] mv = Mat.identity(uniforms.shadowModelView);
		Mat.translate(mv, 0, 0, -100);
		Mat.rotate(mv, 90, 1, 0, 0);
		Mat.rotate(mv, uniforms.shadowAngle * -360, 0, 0, 1);
		Mat.rotate(mv, uniforms.sunPathRotation, 1, 0, 0);
		if (interval > 0) {
			Mat.translate(mv, (float)(frame.cameraX % interval) - interval / 2, (float)(frame.cameraY % interval) - interval / 2,
				(float)(frame.cameraZ % interval) - interval / 2);
		}
		float fov = cur.set.settingFloat("shadowMapFov", 0);
		if (fov > 0) {
			float f = (float)(1 / Math.tan(Math.toRadians(fov) / 2));
			Mat.identity(uniforms.shadowProjection);
			uniforms.shadowProjection[0] = f;
			uniforms.shadowProjection[5] = f;
			uniforms.shadowProjection[10] = -(256 + 0.05f) / (256 - 0.05f);
			uniforms.shadowProjection[11] = -1;
			uniforms.shadowProjection[14] = -2 * 256 * 0.05f / (256 - 0.05f);
			uniforms.shadowProjection[15] = 0;
		} else {
			Mat.ortho(uniforms.shadowProjection, -dist, dist, -dist, dist, 0.05f, 256);
		}
		uniforms.updateShadow();
		MGL.glGetInteger(0xBA2, ints);
		int vx = ints.get(0), vy = ints.get(1), vw = ints.get(2), vh = ints.get(3);
		inShadow = true;
		shadowProg = sp;
		MGL.glBindFramebuffer(FB, shadowFb);
		setDrawBuffers(sp.drawBuffers);
		MGL.glViewport(0, 0, shadowRes, shadowRes);
		MGL.clearBuffers(SHADOW_CLEAR, true); // per-attachment white: glClearColor (cached by GlStateManager) stays as it is
		MGL.glMatrixMode(0x1701);
		MGL.glPushMatrix();
		loadMatrix(uniforms.shadowProjection);
		MGL.glMatrixMode(0x1700);
		MGL.glPushMatrix();
		loadMatrix(mv);
		Programs.glUseProgram(sp.id);
		uniforms(sp);
		drawEntityId = drawBlockEntityId = -1;
		applyDraw(sp);
		// terrain with our own state, restored after; entities set theirs through GlStateManager so its cache stays true
		MGL.glPushAttrib(0xFFFFF);
		shadowState();
		shadowTerrain.run();
		MGL.glPopAttrib();
		if (shadowEntities != null && (cur.config.flag("shadowEntities", true) || cur.config.flag("shadowBlockEntities", true)
			|| cur.config.flag("shadowPlayer", false))) {
			MGL.glMatrixMode(0x1700);
			MGL.glPushMatrix();
			shadowEntities.run();
			MGL.glMatrixMode(0x1700);
			MGL.glPopMatrix();
			MGL.glBindFramebuffer(FB, shadowFb); // in case entity code rebound Minecraft's framebuffer (entity outlines)
			setDrawBuffers(sp.drawBuffers);
			MGL.glViewport(0, 0, shadowRes, shadowRes);
			Programs.glUseProgram(sp.id);
			drawEntityId = drawBlockEntityId = -1;
			applyDraw(sp);
		}
		MGL.glPushAttrib(0xFFFFF);
		shadowState();
		// shadowtex1 = without translucent; shadowtex0 then also gets translucent terrain (shadowcolor = its tint)
		int unit = MGL.glGetInteger(0x84E0) - UNIT0, read = MGL.glGetInteger(0x8CAA), tex5;
		MGL.glBindFramebuffer(READ_FB, shadowFb);
		MGL.glActiveTexture(UNIT0 + 5);
		tex5 = MGL.glGetInteger(0x8069);
		MGL.glBindTexture(TEX, shadowDepth1);
		MGL.glCopyTexSubImage2D(TEX, 0, 0, 0, 0, 0, shadowRes, shadowRes);
		MGL.glBindTexture(TEX, tex5);
		MGL.glActiveTexture(UNIT0 + unit);
		MGL.glBindFramebuffer(READ_FB, read);
		if (shadowTranslucent != null && cur.config.flag("shadowTranslucent", true)) {
			shadowTranslucent.run();
		}
		MGL.glPopAttrib();
		Programs.glUseProgram(0);
		MGL.glMatrixMode(0x1701);
		MGL.glPopMatrix();
		MGL.glMatrixMode(0x1700);
		MGL.glPopMatrix();
		inShadow = false;
		MGL.glViewport(vx, vy, vw, vh);
		MGL.glBindFramebuffer(FB, gbufferFb);
		bound = null;
	}

	private static final float[][] SHADOW_CLEAR = {{1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1},
		{1, 1, 1, 1}};

	private static void shadowState() {
		MGL.glDisable(0xB44);
		MGL.glDisable(0xBE2);
		MGL.glEnable(0xB71);
		MGL.glDepthFunc(0x203);
		MGL.glDepthMask(true);
		MGL.glColorMask(true, true, true, true);
		MGL.glEnable(0xBC0);
		MGL.glAlphaFunc(0x204, 0.1f);
	}

	private static float shadowHalf;
	private static final float[] cullTmp = new float[3];

	/** Shadow-pass culling: whether a 16-block chunk centred at this camera-relative point can land in the shadow map. */
	public static boolean inShadowView(double rx, double ry, double rz) {
		Mat.transform(cullTmp, uniforms.shadowModelView, (float)rx, (float)ry, (float)rz, 1);
		float r = shadowHalf + 14f; // half the chunk's diagonal
		return Math.abs(cullTmp[0]) <= r && Math.abs(cullTmp[1]) <= r;
	}

	private static void loadMatrix(float[] m) {
		MGL.glLoadIdentity();
		floats.clear();
		floats.put(m);
		floats.flip();
		MGL.glMultMatrix(floats);
		floats.clear();
	}

	private ShaderPipeline() {
	}
}
