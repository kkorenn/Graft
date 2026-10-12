package dev.graft.legacy.shaders;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack options, found the documented way: "#define NAME" / "//#define NAME" switches (only when the same file also has
 * #ifdef/#ifndef NAME), "#define NAME value // [a b c]" choices, and the recognized "const" settings. Current values live
 * in {@link Values} and are written into the source lines as files are expanded.
 */
public final class Options {
	public static final class Option {
		public final String name, def;
		public final boolean bool, isConst;
		public final List<String> values;
		public final String comment;
		boolean ambiguous;

		Option(String name, String def, boolean bool, boolean isConst, List<String> values, String comment) {
			this.name = name;
			this.def = def;
			this.bool = bool;
			this.isConst = isConst;
			this.values = values;
			this.comment = comment;
		}

		/** Visible in the options UI (consts without allowed values only through screens/profiles). */
		public boolean visible() {
			return !ambiguous && (bool || values.size() > 1);
		}

		public boolean ambiguous() {
			return ambiguous;
		}
	}

	/** Chosen values (only differences from the defaults are stored) and programs disabled by a profile. */
	public static final class Values {
		public final Map<String, String> set = new TreeMap<String, String>();
		public final Set<String> disabledPrograms = new HashSet<String>();

		public static Values load(File f) {
			Values v = new Values();
			if (f.isFile()) {
				Properties p = new Properties();
				try (InputStream in = new FileInputStream(f)) {
					p.load(in);
				} catch (IOException ignored) {
				}
				for (String k : p.stringPropertyNames()) {
					v.set.put(k, p.getProperty(k).trim());
				}
			}
			return v;
		}

		public void save(File f) {
			Properties p = new Properties();
			p.putAll(set);
			try (OutputStream out = new FileOutputStream(f)) {
				p.store(out, "Graft shader options");
			} catch (IOException ignored) {
			}
		}
	}

	public final LinkedHashMap<String, Option> all = new LinkedHashMap<String, Option>();

	static final Set<String> CONSTS = new HashSet<String>(Arrays.asList("shadowMapResolution", "shadowDistance", "shadowDistanceRenderMul",
		"shadowIntervalSize", "generateShadowMipmap", "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowHardwareFiltering0",
		"shadowHardwareFiltering1", "shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap", "shadowcolor0Mipmap", "shadowColor0Mipmap",
		"shadowcolor1Mipmap", "shadowColor1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest", "shadowtex1Nearest",
		"shadow1MinMagNearest", "shadowcolor0Nearest", "shadowColor0Nearest", "shadowColor0MinMagNearest", "shadowcolor1Nearest",
		"shadowColor1Nearest", "shadowColor1MinMagNearest", "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife",
		"centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution"));

	private static final Pattern SWITCH = Pattern.compile("^(\\s*)(//+\\s*)?#\\s*define\\s+(\\w+)\\s*(//.*)?$");
	private static final Pattern VALUE = Pattern.compile("^(\\s*)#\\s*define\\s+(\\w+)\\s+([^\\s/]+)\\s*(//.*)?$");
	private static final Pattern CONST = Pattern.compile("^(\\s*)const\\s+(int|float|bool)\\s+(\\w+)\\s*=\\s*([^;]+?)\\s*;\\s*(//.*)?$");
	private static final Pattern IFDEF = Pattern.compile("^\\s*#\\s*(?:ifdef|ifndef)\\s+(\\w+)", Pattern.MULTILINE);
	private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");

	static Options discover(ShaderPack pack) {
		Options o = new Options();
		for (String f : pack.files()) {
			if (!isSource(f)) {
				continue;
			}
			String text = pack.read(f);
			if (text == null || text.indexOf("define") < 0 && text.indexOf("const") < 0) {
				continue;
			}
			Set<String> tested = new HashSet<String>();
			Matcher im = IFDEF.matcher(text);
			while (im.find()) {
				tested.add(im.group(1));
			}
			for (String line : text.split("\n")) {
				Matcher m;
				if ((m = SWITCH.matcher(line)).matches()) {
					if (tested.contains(m.group(3))) {
						o.add(new Option(m.group(3), m.group(2) == null ? "true" : "false", true, false, Arrays.asList("true", "false"), comment(m.group(4))));
					}
				} else if ((m = VALUE.matcher(line)).matches() && m.group(4) != null && LIST.matcher(m.group(4)).find()) {
					o.add(new Option(m.group(2), m.group(3), false, false, values(m.group(3), m.group(4)), comment(m.group(4))));
				} else if ((m = CONST.matcher(line)).matches() && CONSTS.contains(m.group(3))) {
					boolean b = m.group(2).equals("bool");
					List<String> vs = b ? Arrays.asList("true", "false") : m.group(5) != null && LIST.matcher(m.group(5)).find() ? values(m.group(4), m.group(5))
						: new ArrayList<String>(Arrays.asList(m.group(4)));
					o.add(new Option(m.group(3), m.group(4), b, true, vs, comment(m.group(5))));
				}
			}
		}
		return o;
	}

	static boolean isSource(String f) {
		return f.endsWith(".fsh") || f.endsWith(".vsh") || f.endsWith(".gsh") || f.endsWith(".glsl") || f.endsWith(".inc") || f.endsWith(".csh");
	}

	private void add(Option opt) {
		Option old = all.get(opt.name);
		if (old == null) {
			all.put(opt.name, opt);
		} else if (!old.def.equals(opt.def) || old.bool != opt.bool) {
			old.ambiguous = true;
		}
	}

	private static List<String> values(String def, String comment) {
		Matcher m = LIST.matcher(comment);
		List<String> out = new ArrayList<String>();
		if (m.find()) {
			for (String v : m.group(1).trim().split("\\s+")) {
				if (!v.isEmpty() && !out.contains(v)) {
					out.add(v);
				}
			}
		}
		if (!out.contains(def)) {
			out.add(0, def);
		}
		return out;
	}

	private static String comment(String c) {
		if (c == null) {
			return "";
		}
		return LIST.matcher(c.replaceFirst("^//+", "")).replaceAll("").trim();
	}

	/** The value in effect for an option ("true"/"false" for switches). */
	public String value(String name, Values v) {
		Option o = all.get(name);
		if (o == null) {
			return null;
		}
		String s = o.ambiguous ? null : v.set.get(name);
		return s != null ? s : o.def;
	}

	/** One source line with the chosen values written in. */
	String apply(String line, Values v) {
		if (v.set.isEmpty() || line.indexOf("define") < 0 && line.indexOf("const") < 0) {
			return line;
		}
		Matcher m;
		if ((m = SWITCH.matcher(line)).matches()) {
			String val = changed(m.group(3), v);
			if (val != null && all.get(m.group(3)).bool) {
				return m.group(1) + (val.equals("true") ? "" : "//") + "#define " + m.group(3) + (m.group(4) != null ? " " + m.group(4) : "");
			}
		} else if ((m = VALUE.matcher(line)).matches()) {
			String val = changed(m.group(2), v);
			if (val != null) {
				return m.group(1) + "#define " + m.group(2) + " " + val + (m.group(4) != null ? " " + m.group(4) : "");
			}
		} else if ((m = CONST.matcher(line)).matches()) {
			String val = changed(m.group(3), v);
			if (val != null) {
				return m.group(1) + "const " + m.group(2) + " " + m.group(3) + " = " + val + ";" + (m.group(5) != null ? " " + m.group(5) : "");
			}
		}
		return line;
	}

	private String changed(String name, Values v) {
		Option o = all.get(name);
		return o == null || o.ambiguous ? null : v.set.get(name);
	}

	/** Option macros for .properties conditionals: switches that are on, and choices with their value. */
	public Map<String, String> macros(Values v) {
		Map<String, String> m = new LinkedHashMap<String, String>();
		for (Option o : all.values()) {
			if (o.isConst) {
				continue;
			}
			String val = value(o.name, v);
			if (o.bool) {
				if (val.equals("true")) {
					m.put(o.name, "");
				}
			} else {
				m.put(o.name, val);
			}
		}
		return m;
	}

	/** Applies "profile.NAME" from shaders.properties: OPT, !OPT, OPT:val / OPT=val, profile.OTHER, !program.name. */
	public void applyProfile(String name, Properties props, Values v) {
		applyProfile(name, props, v, 0);
	}

	private void applyProfile(String name, Properties props, Values v, int depth) {
		String def = props.getProperty("profile." + name);
		if (def == null || depth > 8) {
			return;
		}
		for (String tok : def.trim().split("\\s+")) {
			if (tok.isEmpty()) {
				continue;
			}
			if (tok.startsWith("profile.")) {
				applyProfile(tok.substring(8), props, v, depth + 1);
			} else if (tok.startsWith("!program.")) {
				v.disabledPrograms.add(tok.substring(9));
			} else if (tok.startsWith("!")) {
				set(tok.substring(1), "false", v);
			} else {
				int sep = Math.max(tok.indexOf(':'), tok.indexOf('='));
				set(sep > 0 ? tok.substring(0, sep) : tok, sep > 0 ? tok.substring(sep + 1) : "true", v);
			}
		}
	}

	public void set(String name, String value, Values v) {
		Option o = all.get(name);
		if (o == null || o.ambiguous) {
			return;
		}
		if (o.def.equals(value)) {
			v.set.remove(name);
		} else {
			v.set.put(name, value);
		}
	}
}
