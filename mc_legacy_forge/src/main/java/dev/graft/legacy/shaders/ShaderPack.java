package dev.graft.legacy.shaders;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An OptiFine-format shader pack (folder or zip with a "shaders" directory): file access, #include expansion with
 * option values applied, the conditional pass used to read configuration (shaders.properties, directives), and the
 * standard macros injected after #version. Holds no GPU state; safe to use off the render thread.
 */
public final class ShaderPack implements Closeable {
	public final String name;
	private final ZipFile zip;
	private final File dir;
	/** Zip entry prefix of the "shaders/" directory ("" for a folder pack, whose dir already is shaders/). */
	private final String root;
	private final Map<String, String> cache = new HashMap<String, String>();
	private final List<String> files = new ArrayList<String>();
	public final Options options;

	private ShaderPack(String name, ZipFile zip, File dir, String root) throws IOException {
		this.name = name;
		this.zip = zip;
		this.dir = dir;
		this.root = root;
		if (zip != null) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry z = e.nextElement();
				if (!z.isDirectory() && z.getName().startsWith(root)) {
					files.add(z.getName().substring(root.length()));
				}
			}
		} else {
			list(dir, "");
		}
		Collections.sort(files);
		options = Options.discover(this);
	}

	/** Opens a pack folder or zip, or returns null if it has no shaders/ directory. */
	public static ShaderPack open(File f) throws IOException {
		if (f.isDirectory()) {
			File s = new File(f, "shaders");
			return s.isDirectory() ? new ShaderPack(f.getName(), null, s, "") : null;
		}
		if (!f.getName().toLowerCase().endsWith(".zip")) {
			return null;
		}
		ZipFile z = new ZipFile(f);
		String root = null;
		for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
			String n = e.nextElement().getName();
			int at = n.startsWith("shaders/") ? 0 : n.indexOf("/shaders/") + 1;
			if (at >= 0 && (n.startsWith("shaders/") || at > 0) && (root == null || at + 8 < root.length())) {
				root = n.substring(0, at) + "shaders/";
			}
		}
		if (root == null) {
			z.close();
			return null;
		}
		return new ShaderPack(f.getName(), z, null, root);
	}

	private void list(File d, String prefix) {
		File[] fs = d.listFiles();
		if (fs == null) {
			return;
		}
		for (File f : fs) {
			if (f.isDirectory()) {
				list(f, prefix + f.getName() + "/");
			} else {
				files.add(prefix + f.getName());
			}
		}
	}

	/** Paths relative to shaders/, without a leading slash. */
	public List<String> files() {
		return files;
	}

	public boolean exists(String path) {
		return Collections.binarySearch(files, normalize(path)) >= 0;
	}

	public boolean hasDirectory(String path) {
		String p = normalize(path) + "/";
		for (String f : files) {
			if (f.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	/** A text file (path relative to shaders/), or null. */
	public synchronized String read(String path) {
		String p = normalize(path);
		if (cache.containsKey(p)) {
			return cache.get(p);
		}
		byte[] b = bytes(p);
		String s = b == null ? null : new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
		if (s != null && s.startsWith("﻿")) {
			s = s.substring(1);
		}
		cache.put(p, s);
		return s;
	}

	/** Raw bytes of a pack file (textures), or null. */
	public synchronized byte[] bytes(String path) {
		String p = normalize(path);
		try {
			InputStream in;
			if (zip != null) {
				ZipEntry e = zip.getEntry(root + p);
				if (e == null) {
					return null;
				}
				in = zip.getInputStream(e);
			} else {
				File f = new File(dir, p);
				if (!f.isFile()) {
					return null;
				}
				in = new FileInputStream(f);
			}
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[16384];
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n);
				}
				return out.toByteArray();
			} finally {
				in.close();
			}
		} catch (IOException e) {
			return null;
		}
	}

	static String normalize(String path) {
		String p = path.replace('\\', '/');
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		// resolve "." and ".."
		List<String> parts = new ArrayList<String>();
		for (String s : p.split("/")) {
			if (s.isEmpty() || s.equals(".")) {
				continue;
			}
			if (s.equals("..")) {
				if (!parts.isEmpty()) {
					parts.remove(parts.size() - 1);
				}
			} else {
				parts.add(s);
			}
		}
		StringBuilder b = new StringBuilder();
		for (String s : parts) {
			if (b.length() > 0) {
				b.append('/');
			}
			b.append(s);
		}
		return b.toString();
	}

	@Override
	public void close() throws IOException {
		if (zip != null) {
			zip.close();
		}
	}

	// ---------------------------------------------------------------- #include

	private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]");

	/**
	 * A shader file with every #include inlined (relative to the including file, or to shaders/ with a leading slash) and
	 * the current option values written into option lines. Returns null if the file is missing.
	 */
	public String expand(String path, Options.Values values) {
		StringBuilder out = new StringBuilder();
		List<String> stack = new ArrayList<String>();
		return expand(normalize(path), values, out, stack) ? out.toString() : null;
	}

	private boolean expand(String path, Options.Values values, StringBuilder out, List<String> stack) {
		String text = read(path);
		if (text == null || stack.contains(path) || stack.size() > 32) {
			return false;
		}
		stack.add(path);
		String base = path.lastIndexOf('/') >= 0 ? path.substring(0, path.lastIndexOf('/') + 1) : "";
		for (String line : text.split("\n", -1)) {
			Matcher m = INCLUDE.matcher(line);
			if (m.find()) {
				String inc = m.group(1).trim();
				String target = normalize(inc.startsWith("/") ? inc : base + inc);
				if (!expand(target, values, out, stack)) {
					out.append("#error \"Graft: missing include ").append(inc).append("\"\n");
				}
				continue;
			}
			out.append(values != null ? options.apply(line, values) : line).append('\n');
		}
		stack.remove(stack.size() - 1);
		return true;
	}

	// ---------------------------------------------------------------- conditionals

	private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)\\s*(.*)$");

	/**
	 * Keeps only the active lines of #if/#ifdef/#else blocks (comments included, so directive comments survive), tracking
	 * #define/#undef from the given starting macros. The final macro set is left in {@code macros}.
	 */
	public static String conditionals(String src, Map<String, String> macros) {
		StringBuilder out = new StringBuilder(src.length());
		// stack entries: 0 = active, 1 = inactive (a branch may still be taken), 2 = inactive (done or parent inactive)
		int[] stack = new int[64];
		int depth = 0;
		for (String line : src.split("\n", -1)) {
			Matcher m = DIRECTIVE.matcher(line);
			boolean active = depth == 0 || stack[depth - 1] == 0;
			if (m.matches()) {
				String d = m.group(1), arg = m.group(2);
				if (d.equals("ifdef") || d.equals("ifndef") || d.equals("if")) {
					int state;
					if (!active) {
						state = 2;
					} else {
						boolean c = d.equals("if") ? Expr.condition(arg, macros) : macros.containsKey(firstWord(arg)) == d.equals("ifdef");
						state = c ? 0 : 1;
					}
					if (depth < stack.length) {
						stack[depth++] = state;
					}
					continue;
				}
				if (d.equals("elif") || d.equals("else")) {
					if (depth > 0) {
						int s = stack[depth - 1];
						boolean parent = depth == 1 || stack[depth - 2] == 0;
						if (s == 0) {
							stack[depth - 1] = 2;
						} else if (s == 1 && parent) {
							stack[depth - 1] = d.equals("else") || Expr.condition(arg, macros) ? 0 : 1;
						}
					}
					continue;
				}
				if (d.equals("endif")) {
					if (depth > 0) {
						depth--;
					}
					continue;
				}
				if (active && d.equals("define")) {
					String rest = Expr.stripComments(arg).trim();
					String n = firstWord(rest);
					if (!n.isEmpty() && rest.length() >= n.length() && (rest.length() == n.length() || rest.charAt(n.length()) != '(')) {
						macros.put(n, rest.substring(n.length()).trim());
					}
				} else if (active && d.equals("undef")) {
					macros.remove(firstWord(arg));
				}
			}
			if (active) {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	private static String firstWord(String s) {
		s = s.trim();
		int i = 0;
		while (i < s.length() && (Character.isJavaIdentifierPart(s.charAt(i)))) {
			i++;
		}
		return s.substring(0, i);
	}

	// ---------------------------------------------------------------- standard macros

	/** What the game reports to packs (OptiFine's documented "Standard Macros"). */
	public static final class Environment {
		public int mcVersion = 10809, glVersion = 320, glslVersion = 150;
		public String renderQuality = "1.0", shadowQuality = "1.0", handDepth = "0.125";
		public boolean normalMap = true, specularMap = true, oldLighting, oldHandLight;
		/** Extension macros that are reported as supported. */
		public String[] extensions = {"GL_ARB_shader_texture_lod", "GL_EXT_gpu_shader4", "GL_ARB_texture_rectangle", "GL_ARB_framebuffer_object",
			"GL_EXT_framebuffer_object", "GL_ARB_texture_float", "GL_ARB_half_float_pixel", "GL_EXT_texture_array", "GL_ARB_explicit_attrib_location"};
		public String world = "world0";

		/** Macro name -> value of the standard macros. */
		public Map<String, String> macros() {
			Map<String, String> m = new LinkedHashMap<String, String>();
			m.put("MC_VERSION", String.valueOf(mcVersion));
			m.put("MC_GL_VERSION", String.valueOf(glVersion));
			m.put("MC_GLSL_VERSION", String.valueOf(glslVersion));
			m.put("MC_OS_MAC", "");
			m.put("MC_GL_VENDOR_OTHER", "");
			m.put("MC_GL_RENDERER_OTHER", "");
			if (normalMap) {
				m.put("MC_NORMAL_MAP", "");
			}
			if (specularMap) {
				m.put("MC_SPECULAR_MAP", "");
			}
			m.put("MC_RENDER_QUALITY", renderQuality);
			m.put("MC_SHADOW_QUALITY", shadowQuality);
			m.put("MC_HAND_DEPTH", handDepth);
			if (oldLighting) {
				m.put("MC_OLD_LIGHTING", "");
			}
			if (oldHandLight) {
				m.put("MC_OLD_HAND_LIGHT", "");
			}
			for (String e : extensions) {
				m.put("MC_" + e, "");
			}
			String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
				"TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE",
				"PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
			for (int i = 0; i < stages.length; i++) {
				m.put("MC_RENDER_STAGE_" + stages[i], String.valueOf(i));
			}
			return m;
		}
	}

	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)(\\s+\\w+)?\\s*$", Pattern.MULTILINE);

	/**
	 * Final source for the GLSL compiler: #version first (120 if none), then the standard macros, then the rest of the
	 * expanded file. #extension lines stay where the pack put them (after our #defines is fine for
	 * glslang; they are dropped by the translator anyway).
	 */
	public static String withMacros(String expanded, Map<String, String> macros) {
		Matcher m = VERSION.matcher(expanded);
		String version = "#version 120";
		String body = expanded;
		if (m.find()) {
			version = "#version " + m.group(1) + (m.group(2) != null && !m.group(2).trim().equals("es") ? " " + m.group(2).trim() : "");
			body = expanded.substring(0, m.start()) + "//" + expanded.substring(m.start(), m.end()).trim() + expanded.substring(m.end());
		}
		StringBuilder b = new StringBuilder(version).append('\n');
		for (Map.Entry<String, String> e : macros.entrySet()) {
			b.append("#define ").append(e.getKey());
			if (!e.getValue().isEmpty()) {
				b.append(' ').append(e.getValue());
			}
			b.append('\n');
		}
		return b.append(body).toString();
	}
}
