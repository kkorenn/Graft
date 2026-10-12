package dev.graft.legacy.shaders;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration a program declares in its (active) source: draw buffers, buffer formats/clears/mipmaps and the global
 * "const" settings (shadow map, sun path, half-lives...). Read from the conditional pass, so comments are still there
 * and only the taken #if branches count.
 */
public final class Directives {
	/** Color buffers the fragment shader writes, in output order; null = not declared. */
	public int[] drawBuffers;
	/** const settings by name, as written ("2048", "-40.0", "true", "RGBA16F", "vec4(0.0)"). */
	public final Map<String, String> consts = new HashMap<String, String>();
	/** Buffer index -> GL internal format name (colortexN / gcolor... and shadowcolorN as 100 + N). */
	public final Map<Integer, String> formats = new HashMap<Integer, String>();
	public boolean usesEntityAttrib, usesMidTexCoord, usesTangent;
	/** Samplers declared (by name), for deciding which buffers/depth copies a pack needs. */
	public final java.util.Set<String> samplers = new java.util.HashSet<String>();

	private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]+)\\s*\\*/");
	private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+?)\\s*\\*/");
	private static final Pattern CONST = Pattern.compile("\\bconst\\s+(?:int|float|bool|vec4|vec3|ivec3|vec2)\\s+(\\w+)\\s*=\\s*([^;]+?)\\s*;");
	private static final Pattern OLD = Pattern.compile("/\\*\\s*(SHADOWRES|SHADOWFOV|SHADOWHPL|WETNESSHL|DRYNESSHL|(\\w+)FORMAT)\\s*:\\s*([\\w.]+)\\s*\\*/");
	private static final Pattern SAMPLER = Pattern.compile("\\buniform\\s+\\w*sampler\\w*\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*;");
	private static final String[] LEGACY = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	public static Directives parse(String activeFragment, String activeVertex) {
		Directives d = new Directives();
		for (String src : new String[] {activeVertex, activeFragment}) {
			if (src == null) {
				continue;
			}
			Matcher m = CONST.matcher(src);
			while (m.find()) {
				d.consts.put(m.group(1), m.group(2).trim());
				int buf = bufferIndex(m.group(1), "Format");
				if (buf >= 0) {
					d.formats.put(buf, m.group(2).trim());
				}
			}
			m = OLD.matcher(src);
			while (m.find()) {
				String key = m.group(1), val = m.group(3);
				if (m.group(2) != null) {
					int buf = bufferIndex(m.group(2).toLowerCase(), "");
					if (buf >= 0) {
						d.formats.put(buf, val);
					}
				} else {
					d.consts.put(key.equals("SHADOWRES") ? "shadowMapResolution" : key.equals("SHADOWFOV") ? "shadowMapFov" : key.equals("SHADOWHPL")
						? "shadowDistance" : key.equals("WETNESSHL") ? "wetnessHalflife" : "drynessHalflife", val);
				}
			}
			m = SAMPLER.matcher(src);
			while (m.find()) {
				for (String n : m.group(1).split(",")) {
					d.samplers.add(n.trim());
				}
			}
		}
		if (activeVertex != null) {
			d.usesEntityAttrib = activeVertex.contains("mc_Entity");
			d.usesMidTexCoord = activeVertex.contains("mc_midTexCoord");
			d.usesTangent = activeVertex.contains("at_tangent");
		}
		if (activeFragment != null) {
			Matcher m = RENDERTARGETS.matcher(activeFragment);
			String last = null;
			boolean rt = false;
			while (m.find()) {
				last = m.group(1);
				rt = true;
			}
			if (last == null) {
				m = DRAWBUFFERS.matcher(activeFragment);
				while (m.find()) {
					last = m.group(1);
				}
			}
			if (last != null) {
				String[] parts = rt ? last.split("\\s*,\\s*") : last.split("");
				int[] out = new int[parts.length];
				int n = 0;
				for (String p : parts) {
					p = p.trim();
					if (p.isEmpty()) {
						continue;
					}
					try {
						out[n++] = rt ? Integer.parseInt(p) : Character.digit(p.charAt(0), 16);
					} catch (NumberFormatException e) {
						n--;
					}
				}
				d.drawBuffers = java.util.Arrays.copyOf(out, n);
			}
		}
		return d;
	}

	/** colortexN / gcolor..gaux4 / shadowcolorN prefix of a name with the given suffix -> buffer index, else -1. */
	static int bufferIndex(String name, String suffix) {
		if (!name.endsWith(suffix)) {
			return -1;
		}
		String n = name.substring(0, name.length() - suffix.length());
		for (int i = 0; i < LEGACY.length; i++) {
			if (n.equals(LEGACY[i])) {
				return i;
			}
		}
		try {
			if (n.startsWith("colortex")) {
				return Integer.parseInt(n.substring(8));
			}
			if (n.startsWith("shadowcolor")) {
				return 100 + (n.length() == 11 ? 0 : Integer.parseInt(n.substring(11)));
			}
		} catch (NumberFormatException ignored) {
		}
		return -1;
	}

	public float constFloat(String name, float def) {
		String v = consts.get(name);
		if (v == null) {
			return def;
		}
		try {
			return Float.parseFloat(v.replaceAll("[fF]$", ""));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public boolean constBool(String name, boolean def) {
		String v = consts.get(name);
		return v == null ? def : v.equals("true");
	}
}
