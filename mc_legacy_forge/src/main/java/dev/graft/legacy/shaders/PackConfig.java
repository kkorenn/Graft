package dev.graft.legacy.shaders;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * shaders.properties of a pack for the current options: run through the conditional pass with the standard and option
 * macros, then read as a properties file. Profiles, screens and sliders come from the unconditioned file (as documented).
 */
public final class PackConfig {
	public final Properties props, raw;
	/** uniform.<type>.<name> / variable.<type>.<name> in file order: {kind, type, name, expression}. */
	public final List<String[]> customUniforms = new ArrayList<String[]>();
	private final List<String> profiles = new ArrayList<String>();

	public PackConfig(ShaderPack pack, Map<String, String> macros) {
		String text = pack.read("shaders.properties");
		raw = load(text == null ? "" : text.replaceAll("(?m)^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif|define|undef)\\b.*$", ""));
		props = load(text == null ? "" : ShaderPack.conditionals(text, new LinkedHashMap<String, String>(macros)));
		for (String line : (text == null ? "" : text).split("\n")) {
			String l = line.trim();
			int eq = l.indexOf('=');
			if (l.startsWith("profile.") && eq > 8 && !profiles.contains(l.substring(8, eq).trim())) {
				profiles.add(l.substring(8, eq).trim());
			}
		}
		// custom uniforms keep the file order (later ones may use earlier variables)
		if (text != null) {
			String active = ShaderPack.conditionals(text, new LinkedHashMap<String, String>(macros)).replace("\\\n", " ");
			for (String line : active.split("\n")) {
				String l = line.trim();
				if (l.startsWith("uniform.") || l.startsWith("variable.")) {
					int eq = l.indexOf('=');
					String[] key = eq > 0 ? l.substring(0, eq).trim().split("\\.") : new String[0];
					if (key.length == 3) {
						customUniforms.add(new String[] {key[0], key[1], key[2], l.substring(eq + 1).trim()});
					}
				}
			}
		}
	}

	private static Properties load(String text) {
		Properties p = new Properties();
		try {
			p.load(new StringReader(text.replace("\\\n", " "))); // keep backslashes in values literal, except line continuations
		} catch (IOException ignored) {
		}
		return p;
	}

	public String get(String key) {
		String v = props.getProperty(key);
		return v == null ? null : v.trim();
	}

	public boolean flag(String key, boolean def) {
		String v = get(key);
		return v == null ? def : v.equalsIgnoreCase("true");
	}

	/** Profile names in file order. */
	public List<String> profiles() {
		return profiles;
	}
}
