package dev.graft.legacy.shaders;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Version-independent model of a pack's option screens (shaders.properties "screen" / "screen.NAME" layout, profiles,
 * labels from shaders/lang): the version modules only draw buttons for {@link #entries}.
 */
public final class OptionScreens {
	public static final class Entry {
		/** "option", "screen", "profile" or "empty". */
		public final String kind, name;

		Entry(String kind, String name) {
			this.kind = kind;
			this.name = name;
		}
	}

	public final String packName;
	private final ShaderPack pack;
	private final PackConfig config;
	private final Options.Values values;
	private final Map<String, String> lang = new LinkedHashMap<String, String>();
	private boolean changed;

	public OptionScreens(String packName) throws IOException {
		this.packName = packName;
		pack = ShaderPack.open(new File(ShaderPipeline.packsDir(), packName));
		if (pack == null) {
			throw new IOException("not a shader pack");
		}
		values = Options.Values.load(ShaderPipeline.optionsFile(packName));
		config = new PackConfig(pack, new ShaderPack.Environment().macros());
		for (String f : new String[] {"lang/en_US.lang", "lang/en_us.lang"}) {
			String text = pack.read(f);
			if (text != null) {
				Properties p = new Properties();
				p.load(new StringReader(text));
				for (String k : p.stringPropertyNames()) {
					lang.put(k, p.getProperty(k));
				}
				break;
			}
		}
	}

	/** Entries of a screen (null = main screen). Without a screen definition: every visible option. */
	public List<Entry> entries(String screen) {
		String def = config.raw.getProperty(screen == null ? "screen" : "screen." + screen);
		List<Entry> out = new ArrayList<Entry>();
		if (def == null) {
			if (screen == null && !config.profiles().isEmpty()) {
				out.add(new Entry("profile", ""));
			}
			for (Options.Option o : pack.options.all.values()) {
				if (o.visible()) {
					out.add(new Entry("option", o.name));
				}
			}
			return out;
		}
		for (String tok : def.trim().split("\\s+")) {
			if (tok.equals("<empty>")) {
				out.add(new Entry("empty", ""));
			} else if (tok.equals("<profile>")) {
				out.add(new Entry("profile", ""));
			} else if (tok.startsWith("[") && tok.endsWith("]")) {
				out.add(new Entry("screen", tok.substring(1, tok.length() - 1)));
			} else if (tok.equals("*")) {
				Set<String> placed = placedOptions();
				for (Options.Option o : pack.options.all.values()) {
					if (o.visible() && !placed.contains(o.name)) {
						out.add(new Entry("option", o.name));
					}
				}
			} else if (pack.options.all.containsKey(tok)) {
				out.add(new Entry("option", tok));
			}
		}
		return out;
	}

	private Set<String> placedOptions() {
		Set<String> s = new HashSet<String>();
		for (String k : config.raw.stringPropertyNames()) {
			if (k.equals("screen") || k.startsWith("screen.") && !k.endsWith(".columns")) {
				for (String tok : config.raw.getProperty(k).trim().split("\\s+")) {
					s.add(tok);
				}
			}
		}
		return s;
	}

	public int columns(String screen, int entries) {
		String c = config.raw.getProperty(screen == null ? "screen.columns" : "screen." + screen + ".columns");
		try {
			return c != null ? Math.max(1, Integer.parseInt(c.trim())) : entries > 18 ? 3 : 2;
		} catch (NumberFormatException e) {
			return 2;
		}
	}

	/** Button text of an entry. */
	public String label(Entry e) {
		if (e.kind.equals("empty")) {
			return "";
		}
		if (e.kind.equals("screen")) {
			return text("screen." + e.name, e.name) + "...";
		}
		if (e.kind.equals("profile")) {
			String p = currentProfile();
			return "Profile: " + (p == null ? "Custom" : text("profile." + p, p));
		}
		Options.Option o = pack.options.all.get(e.name);
		String v = pack.options.value(e.name, values);
		String shown = o.bool ? (v.equals("true") ? "ON" : "OFF") : text("value." + e.name + "." + v, v);
		String prefix = lang.containsKey("prefix." + e.name) ? lang.get("prefix." + e.name) : "", suffix = lang.containsKey("suffix." + e.name)
			? lang.get("suffix." + e.name) : "";
		return text("option." + e.name, e.name) + ": " + prefix + shown + suffix;
	}

	/** Tooltip of an entry, or "". */
	public String comment(Entry e) {
		if (e.kind.equals("option")) {
			String c = lang.get("option." + e.name + ".comment");
			return c != null ? c : pack.options.all.get(e.name).comment;
		}
		return e.kind.equals("screen") ? text("screen." + e.name + ".comment", "") : "";
	}

	private String text(String key, String def) {
		String s = lang.get(key);
		return s != null ? s.replaceAll("§.", "") : def;
	}

	/** Next (or previous) value of an option, or the next profile; shift resets an option to its default. */
	public void click(Entry e, boolean back, boolean reset) {
		if (e.kind.equals("profile")) {
			List<String> ps = config.profiles();
			if (ps.isEmpty()) {
				return;
			}
			int i = ps.indexOf(currentProfile());
			String next = ps.get(((back ? i - 1 : i + 1) % ps.size() + ps.size()) % ps.size());
			values.set.clear();
			values.disabledPrograms.clear();
			pack.options.applyProfile(next, config.raw, values);
			changed = true;
			return;
		}
		if (!e.kind.equals("option")) {
			return;
		}
		Options.Option o = pack.options.all.get(e.name);
		if (reset) {
			pack.options.set(e.name, o.def, values);
		} else {
			List<String> vs = o.values;
			int i = vs.indexOf(pack.options.value(e.name, values));
			pack.options.set(e.name, vs.get(((back ? i - 1 : i + 1) % vs.size() + vs.size()) % vs.size()), values);
		}
		changed = true;
	}

	/** The profile whose settings all match the current values, or null ("Custom"). */
	public String currentProfile() {
		for (String p : config.profiles()) {
			Options.Values v = new Options.Values();
			pack.options.applyProfile(p, config.raw, v);
			boolean match = true;
			for (Map.Entry<String, String> e : v.set.entrySet()) {
				match &= e.getValue().equals(pack.options.value(e.getKey(), values));
			}
			if (match) {
				return p;
			}
		}
		return null;
	}

	/** Writes the values and reloads the pack if anything changed. */
	public void save() {
		if (changed) {
			values.save(ShaderPipeline.optionsFile(packName));
			ShaderPipeline.reload();
			changed = false;
		}
	}

	public void close() {
		try {
			pack.close();
		} catch (IOException ignored) {
		}
	}
}
