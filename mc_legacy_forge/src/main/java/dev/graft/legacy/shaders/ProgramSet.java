package dev.graft.legacy.shaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The programs of a pack for one dimension and option set: sources with includes and standard macros, their directives,
 * program.X.enabled conditions and the documented fallback chain (gbuffers_water -> gbuffers_terrain -> ...).
 */
public final class ProgramSet {
	public static final class Source {
		public final String name, vertex, fragment;
		/** Non-null if the pack also has a geometry or compute stage for it (unsupported on 1.8.9 Metal). */
		public final String geometry;
		public final Directives directives;

		Source(String name, String vertex, String fragment, String geometry, Directives directives) {
			this.name = name;
			this.vertex = vertex;
			this.fragment = fragment;
			this.geometry = geometry;
			this.directives = directives;
		}
	}

	/** Program -> program used in its place when it is missing or disabled (OptiFine's documented table). */
	public static final Map<String, String> FALLBACK = new HashMap<String, String>();
	public static final List<String> GBUFFERS = new ArrayList<String>();

	static {
		String[][] f = {{"gbuffers_line", "gbuffers_basic"}, {"gbuffers_textured", "gbuffers_basic"}, {"gbuffers_textured_lit", "gbuffers_textured"},
			{"gbuffers_skybasic", "gbuffers_basic"}, {"gbuffers_skytextured", "gbuffers_textured"}, {"gbuffers_clouds", "gbuffers_textured"},
			{"gbuffers_terrain", "gbuffers_textured_lit"}, {"gbuffers_terrain_solid", "gbuffers_terrain"}, {"gbuffers_terrain_cutout_mip", "gbuffers_terrain"},
			{"gbuffers_terrain_cutout", "gbuffers_terrain"}, {"gbuffers_damagedblock", "gbuffers_terrain"}, {"gbuffers_block", "gbuffers_terrain"},
			{"gbuffers_beaconbeam", "gbuffers_textured"}, {"gbuffers_item", "gbuffers_textured_lit"}, {"gbuffers_entities", "gbuffers_textured_lit"},
			{"gbuffers_entities_glowing", "gbuffers_entities"}, {"gbuffers_armor_glint", "gbuffers_textured"}, {"gbuffers_spidereyes", "gbuffers_textured"},
			{"gbuffers_hand", "gbuffers_textured_lit"}, {"gbuffers_weather", "gbuffers_textured_lit"}, {"gbuffers_water", "gbuffers_terrain"},
			{"gbuffers_hand_water", "gbuffers_hand"}, {"shadow_solid", "shadow"}, {"shadow_cutout", "shadow"}};
		GBUFFERS.add("gbuffers_basic");
		for (String[] e : f) {
			FALLBACK.put(e[0], e[1]);
			if (e[0].startsWith("gbuffers_") && !e[0].equals("gbuffers_line")) { // gbuffers_line is 1.16+ (1.8.9 outlines are gbuffers_basic)
				GBUFFERS.add(e[0]);
			}
		}
	}

	/** Every program name the 1.8.9 pipeline may run, in pipeline order. */
	public static List<String> pipelineNames() {
		List<String> out = new ArrayList<String>();
		out.add("shadow");
		for (int i = 0; i < 100; i++) {
			out.add("prepare" + (i == 0 ? "" : i));
		}
		out.addAll(GBUFFERS);
		for (int i = 0; i < 100; i++) {
			out.add("deferred" + (i == 0 ? "" : i));
		}
		for (int i = 0; i < 100; i++) {
			out.add("composite" + (i == 0 ? "" : i));
		}
		out.add("final");
		return out;
	}

	public final String world, dir;
	public final Map<String, String> macros;
	private final Map<String, Source> programs = new LinkedHashMap<String, Source>();
	/** Programs present in the pack but switched off by program.X.enabled or a profile. */
	public final List<String> disabled = new ArrayList<String>();

	/**
	 * Loads every program of the pipeline that the pack defines for this world. Programs are read only from the world folder
	 * when it exists (as documented), else from shaders/.
	 */
	public ProgramSet(ShaderPack pack, String world, Map<String, String> standardMacros, Options.Values values, PackConfig config) {
		this.world = world;
		this.dir = pack.hasDirectory(world) ? world + "/" : "";
		this.macros = standardMacros;
		final Map<String, double[]> switches = new HashMap<String, double[]>();
		for (Map.Entry<String, String> e : pack.options.macros(values).entrySet()) {
			String v = e.getValue();
			switches.put(e.getKey(), new double[] {v.isEmpty() || v.equals("true") ? 1 : parse(v)});
		}
		for (Options.Option o : pack.options.all.values()) {
			if (o.bool && !switches.containsKey(o.name)) {
				switches.put(o.name, new double[] {0});
			}
		}
		for (String name : pipelineNames()) {
			if (!pack.exists(dir + name + ".vsh") || !pack.exists(dir + name + ".fsh")) {
				continue;
			}
			String cond = config.get("program." + world + "/" + name + ".enabled");
			if (cond == null) {
				cond = config.get("program." + name + ".enabled");
			}
			boolean off = values.disabledPrograms.contains(name) || values.disabledPrograms.contains(world + "/" + name);
			try {
				off |= cond != null && !Expr.parse(cond).bool(Expr.env(switches));
			} catch (RuntimeException e) {
				// unparsable condition: keep the program
			}
			if (off) {
				disabled.add(name);
				continue;
			}
			programs.put(name, load(pack, name, values));
		}
	}

	private static double parse(String v) {
		try {
			return Double.parseDouble(v.replaceAll("[fF]$", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private Source load(ShaderPack pack, String name, Options.Values values) {
		String vs = pack.expand(dir + name + ".vsh", values), fs = pack.expand(dir + name + ".fsh", values);
		String gs = pack.exists(dir + name + ".gsh") ? pack.expand(dir + name + ".gsh", values) : null;
		vs = ShaderPack.withMacros(vs, macros);
		fs = ShaderPack.withMacros(fs, macros);
		Directives d = Directives.parse(ShaderPack.conditionals(fs, new HashMap<String, String>()), ShaderPack.conditionals(vs, new HashMap<String, String>()));
		return new Source(name, vs, fs, gs == null ? null : ShaderPack.withMacros(gs, macros), d);
	}

	/** The program defined under this exact name, or null. */
	public Source get(String name) {
		return programs.get(name);
	}

	/** The program to use for a stage: the named one, else its fallbacks; null = fixed function. */
	public Source resolve(String name) {
		for (String n = name; n != null; n = FALLBACK.get(n)) {
			Source s = programs.get(n);
			if (s != null) {
				return s;
			}
		}
		return null;
	}

	public java.util.Collection<Source> all() {
		return programs.values();
	}

	/** A const setting from whichever program declares it (they are global in OptiFine's model). */
	public String setting(String name) {
		for (Source s : programs.values()) {
			String v = s.directives.consts.get(name);
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	public float settingFloat(String name, float def) {
		String v = setting(name);
		if (v == null) {
			return def;
		}
		try {
			return Float.parseFloat(v.replaceAll("[fF]$", ""));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** GL internal format name of a buffer from any program's directives, or null. */
	public String format(int buffer) {
		for (Source s : programs.values()) {
			String f = s.directives.formats.get(buffer);
			if (f != null) {
				return f;
			}
		}
		return null;
	}
}
