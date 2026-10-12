package dev.graft.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OptiFine shader-pack GLSL (1.10-1.50 compatibility profile) -> MSL, the way Graft Shaders' MetalGlsl does it: the
 * compatibility built-ins become inputs, varyings and members of one shared std140 block (mc_Uniforms, holding the pack's
 * loose uniforms too), samplers get explicit bindings, varyings get name-matched locations, and the result is
 * #version 460 -> SPIR-V (glslang) -> MSL (SPIRV-Cross) with the block's member offsets reflected. GL's fixed-function
 * alpha test is appended to fragment shaders; chunk offsets come from an offsets buffer indexed by gl_BaseInstance.
 */
public final class MetalGlsl {
	/** A member of mc_Uniforms. */
	public static final class Member {
		public final String name, type;
		final int array; // 0 = not an array
		int offset = -1, size;

		Member(String name, String type, int array) {
			this.name = name;
			this.type = type;
			this.array = array;
		}
	}

	public static final class Linked {
		public String error;
		public String vertexMsl, fragmentMsl, vertexEntry, fragmentEntry;
		public final List<Member> members = new ArrayList<Member>();
		final Map<String, Integer> memberIndex = new HashMap<String, Integer>();
		/** Sampler i is bound at texture(i)/sampler(i); names as the pack declares them. */
		public final List<String> samplers = new ArrayList<String>();
		final List<Boolean> shadowSamplers = new ArrayList<Boolean>();
		boolean vertexSamplers;
		int blockSize;
	}

	// Compatibility uniforms, filled by MGL for every draw (see MGL.fillBuiltins).
	static final String[][] BUILTIN_MEMBERS = {
		{"mat4", "mc_ModelViewMatrix"}, {"mat4", "mc_ProjectionMatrix"}, {"mat4", "mc_ModelViewProjectionMatrix"},
		{"mat4", "mc_ModelViewMatrixInverse"}, {"mat4", "mc_ProjectionMatrixInverse"}, {"mat4", "mc_ModelViewProjectionMatrixInverse"},
		{"mat3", "mc_NormalMatrix"}, {"mat4", "mc_TextureMatrix[4]"}, {"vec4", "mc_FogColor"}, {"vec4", "mc_FogParams"},
		{"int", "mc_AlphaFunc"}, {"float", "mc_AlphaRef"}};

	/** Samplers from this binding on use a constexpr sampler in the shader (Metal has 16 sampler slots; see shaders.c). */
	static final int BOUND_SAMPLERS = 15;

	private static final Map<String, String> VERTEX_BUILTINS = new HashMap<String, String>(), FRAGMENT_BUILTINS = new HashMap<String, String>(),
		SHARED_BUILTINS = new HashMap<String, String>();

	static {
		String[] shared = {"ModelViewMatrix", "ProjectionMatrix", "ModelViewProjectionMatrix", "ModelViewMatrixInverse", "ProjectionMatrixInverse",
			"ModelViewProjectionMatrixInverse", "NormalMatrix", "TextureMatrix"};
		for (String n : shared) {
			SHARED_BUILTINS.put("gl_" + n, "mc_" + n);
		}
		SHARED_BUILTINS.put("gl_ModelViewMatrixTranspose", "transpose(mc_ModelViewMatrix)");
		SHARED_BUILTINS.put("gl_ProjectionMatrixTranspose", "transpose(mc_ProjectionMatrix)");
		SHARED_BUILTINS.put("gl_TexCoord", "mc_TexCoord");
		SHARED_BUILTINS.put("gl_FogFragCoord", "mc_FogFragCoord");
		VERTEX_BUILTINS.put("gl_Vertex", "mc_Vertex");
		VERTEX_BUILTINS.put("gl_Color", "mc_Color");
		VERTEX_BUILTINS.put("gl_Normal", "mc_Normal");
		VERTEX_BUILTINS.put("gl_MultiTexCoord0", "mc_MultiTexCoord0");
		VERTEX_BUILTINS.put("gl_MultiTexCoord1", "mc_MultiTexCoord1");
		for (int i = 2; i < 8; i++) {
			VERTEX_BUILTINS.put("gl_MultiTexCoord" + i, "vec4(0.0, 0.0, 0.0, 1.0)");
		}
		VERTEX_BUILTINS.put("gl_SecondaryColor", "vec4(0.0)");
		VERTEX_BUILTINS.put("gl_FrontColor", "mc_FrontColor");
		VERTEX_BUILTINS.put("gl_BackColor", "mc_BackColor");
		VERTEX_BUILTINS.put("gl_FrontSecondaryColor", "mc_SecondaryColorOut");
		VERTEX_BUILTINS.put("gl_BackSecondaryColor", "mc_SecondaryColorOut");
		VERTEX_BUILTINS.put("gl_ClipVertex", "mc_ClipVertex");
		VERTEX_BUILTINS.put("gl_VertexID", "gl_VertexIndex");
		VERTEX_BUILTINS.put("gl_InstanceID", "gl_InstanceIndex");
		FRAGMENT_BUILTINS.put("gl_Color", "mc_FrontColor");
		FRAGMENT_BUILTINS.put("gl_SecondaryColor", "vec4(0.0)");
		FRAGMENT_BUILTINS.put("gl_FragColor", "mc_FragData[0]");
		FRAGMENT_BUILTINS.put("gl_FragData", "mc_FragData");
	}

	// Words that are keywords or built-in names in GLSL 4.60 but plain identifiers in the 1.x code packs are written in.
	private static final Pattern KEYWORDS = Pattern.compile("\\b(sample|patch|filter|input|output|common|partition|active|buffer|shared|"
		+ "subroutine|resource|precise|coherent|volatile|restrict|readonly|writeonly|superp|atomic_uint|demote)\\b");
	// From Graft Shaders: C++/MSL keywords and Metal library names SPIRV-Cross does not escape, used as pack identifiers.
	private static final Pattern CXX_KEYWORDS = Pattern.compile(
		"\\b(new|delete|this|operator|template|typename|namespace|private|public|protected|virtual|friend|explicit|try|catch|throw|"
			+ "typeid|decltype|nullptr|constexpr|alignas|alignof|auto|register|mutable|thread_local|static_assert|"
			+ "device|thread|threadgroup|constant|kernel|vertex|fragment|uchar|ushort|ulong|size_t|ptrdiff_t|"
			+ "sampler|samplerShadow|[iu]?texture(?:1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)|[iu]?subpassInput(?:MS)?)\\b(?!\\s*\\()");
	private static final Pattern METAL_LIBRARY_NAMES = Pattern.compile(
		"\\b(bias|level|gradient2d|gradient3d|gradientcube|min_lod_clamp|address|coord|access|mip_filter|mag_filter|min_filter|compare_func|border_color|"
			+ "saturate|length_squared|distance_squared|rsqrt|max3|min3|median3|fmax|fmin|fmax3|fmin3|fmedian3|fdim|fmod|rint|powr|"
			+ "cospi|sinpi|tanpi|acospi|asinpi|atanpi|atan2pi|divide|select|popcount|clz|ctz|rotate|absdiff|abs_diff|mad_sat|mul_hi|"
			+ "hadd|rhadd|addsat|subsat|extract_bits|insert_bits|reverse_bits|copysign|ilogb|nextafter|fast_normalize|precise_normalize|"
			+ "dfdx|dfdy|as_type|discard_fragment|sincos|exp10|log10)\\b");
	private static final Pattern TEXTURE_WORD = Pattern.compile("\\btexture\\b(?!\\s*\\()"); // calls of texture() (lenient 1.20 drivers allow them) stay
	// Built-in functions added after GLSL 1.20 that 1.x packs define themselves.
	private static final Pattern NEW_BUILTINS = Pattern.compile("\\b(round|trunc|roundEven|isnan|isinf|sinh|cosh|tanh|asinh|acosh|atanh|"
		+ "floatBitsToInt|floatBitsToUint|intBitsToFloat|uintBitsToFloat|inverse|determinant|textureSize|texelFetch|fma|frexp|ldexp|"
		+ "packUnorm2x16|unpackUnorm2x16|packHalf2x16|unpackHalf2x16|bitfieldExtract|bitfieldInsert|bitCount|findLSB|findMSB|"
		+ "textureLod|textureGrad|textureOffset|interpolateAtCentroid)\\b");
	private static final Pattern FUNCTION = Pattern.compile("^\\s*(?:\\w+\\s+)*\\w+\\s+(\\w+)\\s*\\(", Pattern.DOTALL);
	private static final Pattern DECL = Pattern.compile(
		"^\\s*((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp|const|uniform|attribute|varying|in|out|layout\\s*\\([^)]*\\))\\s+)*)(\\w+)\\s+(.+);\\s*$",
		Pattern.DOTALL);
	private static final Pattern DECLARATOR = Pattern.compile("^\\s*(\\w+)\\s*(?:\\[\\s*([^\\]]*)\\s*\\])?\\s*(?:=(.*))?$", Pattern.DOTALL);
	private static final Pattern TEXCOORD_INDEX = Pattern.compile("gl_TexCoord\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern BUILTIN = Pattern.compile("\\bgl_\\w+\\b");
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)");
	private static final Pattern VERSION = Pattern.compile("#version\\s+(\\d+)");

	private static final String MACROS = "#define texture2D texture\n#define texture3D texture\n#define textureCube texture\n"
		+ "#define texture2DLod textureLod\n#define texture3DLod textureLod\n#define textureCubeLod textureLod\n#define texture2DProj textureProj\n"
		+ "#define texture2DGrad textureGrad\n#define texture2DGradARB textureGrad\n#define texture2DLodEXT textureLod\n#define texture2DGradEXT textureGrad\n"
		+ "#define shadow2D(s, c) vec4(texture(s, c))\n#define shadow2DLod(s, c, l) vec4(textureLod(s, c, l))\n#define shadow2DProj(s, c) vec4(textureProj(s, c))\n"
		+ "#define texelFetch2D texelFetch\n";

	/** A pack variable: type plus array size text, as declared. */
	private static final class Var {
		final String qualifiers, type, name, array, init;

		Var(String qualifiers, String type, String name, String array, String init) {
			this.qualifiers = qualifiers;
			this.type = type;
			this.name = name;
			this.array = array;
			this.init = init;
		}

		String decl() {
			return type + " " + name + (array != null ? "[" + array + "]" : "");
		}
	}

	/** One stage after the declaration scan. */
	private static final class Stage {
		final boolean fragment;
		int version = 110;
		final StringBuilder body = new StringBuilder();
		final LinkedHashMap<String, Var> uniforms = new LinkedHashMap<String, Var>(), samplers = new LinkedHashMap<String, Var>(),
			attributes = new LinkedHashMap<String, Var>(), varyings = new LinkedHashMap<String, Var>(), outputs = new LinkedHashMap<String, Var>();
		int texCoords;

		Stage(boolean fragment) {
			this.fragment = fragment;
		}
	}

	public static Linked link(String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations) {
		Linked l = new Linked();
		Stage v = scan(vertexSource, false, l), f = scan(fragmentSource, true, l);
		if (l.error != null) {
			return l;
		}
		// Shared block: pack uniforms of both stages (first declaration wins), then the compatibility members.
		LinkedHashMap<String, Var> uniforms = new LinkedHashMap<String, Var>(v.uniforms);
		for (Var u : f.uniforms.values()) {
			if (!uniforms.containsKey(u.name)) {
				uniforms.put(u.name, u);
			}
		}
		StringBuilder block = new StringBuilder("layout(std140, set = 0, binding = 0) uniform mc_Uniforms {\n");
		for (Var u : uniforms.values()) {
			block.append('\t').append(u.decl()).append(";\n");
			l.members.add(new Member(u.name, u.type, u.array == null ? 0 : Math.max(1, intOr(u.array, 1))));
		}
		for (String[] b : BUILTIN_MEMBERS) {
			block.append('\t').append(b[0]).append(' ').append(b[1]).append(";\n");
			int br = b[1].indexOf('[');
			l.members.add(new Member(br < 0 ? b[1] : b[1].substring(0, br), b[0], br < 0 ? 0 : Integer.parseInt(b[1].substring(br + 1, b[1].length() - 1))));
		}
		block.append("};\n");
		for (int i = 0; i < l.members.size(); i++) {
			l.memberIndex.put(l.members.get(i).name, i);
		}
		// Samplers of both stages share one binding table.
		LinkedHashMap<String, Var> samplers = new LinkedHashMap<String, Var>(f.samplers);
		for (Var s : v.samplers.values()) {
			if (!samplers.containsKey(s.name)) {
				samplers.put(s.name, s);
			}
		}
		StringBuilder samplerDecls = new StringBuilder();
		List<Var> ordered = new ArrayList<Var>();
		for (int pass = 0; pass < 2; pass++) { // shadow samplers first: they keep bound (compare) samplers, see BOUND_SAMPLERS
			for (Var s : samplers.values()) {
				if (s.type.contains("Shadow") == (pass == 0)) {
					ordered.add(s);
				}
			}
		}
		for (Var s : ordered) {
			int binding = l.samplers.size();
			String type = s.type.equals("sampler1D") || s.type.equals("sampler2DRect") ? "sampler2D" : s.type.equals("sampler1DShadow") ? "sampler2DShadow" : s.type;
			samplerDecls.append("layout(set = 0, binding = ").append(2 + binding).append(") uniform ").append(type).append(' ').append(s.name).append(";\n");
			l.samplers.add(s.name);
			l.shadowSamplers.add(type.endsWith("Shadow"));
		}
		l.vertexSamplers = !v.samplers.isEmpty();
		// Name-matched varying locations over both stages (sorted, so both sides agree).
		TreeMap<String, Var> varyings = new TreeMap<String, Var>(v.varyings);
		for (Var x : f.varyings.values()) {
			if (!varyings.containsKey(x.name)) {
				varyings.put(x.name, x);
			}
		}
		int texCoords = Math.max(v.texCoords, f.texCoords);
		if (texCoords > 0) {
			varyings.put("mc_TexCoord", new Var("", "vec4", "mc_TexCoord", String.valueOf(texCoords), null));
		}
		varyings.put("mc_FrontColor", new Var("", "vec4", "mc_FrontColor", null, null));
		varyings.put("mc_FogFragCoord", new Var("", "float", "mc_FogFragCoord", null, null));
		Map<String, Integer> slots = new HashMap<String, Integer>();
		int slot = 0;
		for (Var x : varyings.values()) {
			slots.put(x.name, slot);
			slot += slotCount(x.type) * (x.array == null ? 1 : Math.max(1, intOr(x.array, 1)));
		}
		String header = "#version 460 core\n" + MACROS + "struct mc_unused { int x; };\n" + block + samplerDecls;
		String vs = assemble(v, header, varyings, slots, attributeLocations), fs = assemble(f, header, varyings, slots, attributeLocations);
		String vr = Objc.glslToMsl(vs, false);
		if (vr.startsWith("ERR")) {
			l.error = "vertex: " + vr.substring(4) + "\n" + numbered(vs);
			return l;
		}
		String fr = Objc.glslToMsl(fs, true);
		if (fr.startsWith("ERR")) {
			l.error = "fragment: " + fr.substring(4) + "\n" + numbered(fs);
			return l;
		}
		l.vertexMsl = msl(vr, l, false);
		l.fragmentMsl = msl(fr, l, true);
		return l;
	}

	/** Bumped whenever the translation changes, so disk-cached results from older builds are not reused. */
	private static final String CACHE_VERSION = "1";

	/** {@link #link} with results cached on disk by source hash (the GLSL -> MSL work, not Metal's own compile). */
	public static Linked linkCached(String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations, java.io.File dir) {
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
			md.update(CACHE_VERSION.getBytes("UTF-8"));
			md.update(vertexSource.getBytes("UTF-8"));
			md.update((byte)0);
			md.update(fragmentSource.getBytes("UTF-8"));
			md.update(new TreeMap<String, Integer>(attributeLocations).toString().getBytes("UTF-8"));
			key = String.format("%040x", new java.math.BigInteger(1, md.digest()));
		} catch (Exception e) {
			return link(vertexSource, fragmentSource, attributeLocations);
		}
		java.io.File f = new java.io.File(dir, key + ".msl");
		if (f.isFile()) {
			try {
				Linked l = read(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
				if (l != null) {
					return l;
				}
			} catch (java.io.IOException ignored) {
			}
		}
		Linked l = link(vertexSource, fragmentSource, attributeLocations);
		if (l.error == null) {
			try {
				dir.mkdirs();
				java.io.File tmp = new java.io.File(dir, key + ".tmp");
				java.nio.file.Files.write(tmp.toPath(), write(l).getBytes("UTF-8"));
				tmp.renameTo(f);
			} catch (java.io.IOException ignored) {
			}
		}
		return l;
	}

	private static final String SEP = "\n%%GRAFT%%\n";

	private static String write(Linked l) {
		StringBuilder b = new StringBuilder();
		b.append(l.vertexEntry).append(' ').append(l.fragmentEntry).append(' ').append(l.blockSize).append(' ').append(l.vertexSamplers).append('\n');
		for (Member m : l.members) {
			b.append("M ").append(m.name).append(' ').append(m.type).append(' ').append(m.array).append(' ').append(m.offset).append(' ').append(m.size).append('\n');
		}
		for (int i = 0; i < l.samplers.size(); i++) {
			b.append("S ").append(l.samplers.get(i)).append(' ').append(l.shadowSamplers.get(i)).append('\n');
		}
		return b.append(SEP).append(l.vertexMsl).append(SEP).append(l.fragmentMsl).toString();
	}

	private static Linked read(String s) {
		String[] parts = s.split(java.util.regex.Pattern.quote(SEP), -1);
		if (parts.length != 3) {
			return null;
		}
		Linked l = new Linked();
		String[] lines = parts[0].split("\n");
		String[] h = lines[0].split(" ");
		l.vertexEntry = h[0];
		l.fragmentEntry = h[1];
		l.blockSize = Integer.parseInt(h[2]);
		l.vertexSamplers = Boolean.parseBoolean(h[3]);
		for (int i = 1; i < lines.length; i++) {
			String[] p = lines[i].split(" ");
			if (p[0].equals("M")) {
				Member m = new Member(p[1], p[2], Integer.parseInt(p[3]));
				m.offset = Integer.parseInt(p[4]);
				m.size = Integer.parseInt(p[5]);
				l.memberIndex.put(m.name, l.members.size());
				l.members.add(m);
			} else if (p[0].equals("S")) {
				l.samplers.add(p[1]);
				l.shadowSamplers.add(Boolean.parseBoolean(p[2]));
			}
		}
		l.vertexMsl = parts[1];
		l.fragmentMsl = parts[2];
		return l;
	}

	private static long checkDevice;

	/** Compiles MSL with Metal (headless checks); returns null if it compiles, else Metal's error. */
	public static synchronized String checkMsl(String msl) {
		if (checkDevice == 0L) {
			checkDevice = Objc.createDevice();
		}
		java.nio.ByteBuffer err = java.nio.ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.nativeOrder());
		long pool = Objc.objc_autoreleasePoolPush();
		try {
			long lib = Objc.objc_msgSend(checkDevice, Objc.sel("newLibraryWithSource:options:error:"), Objc.nsString(msl), 0L, Objc.address(err));
			if (lib == 0L) {
				return Objc.string(Objc.send(err.getLong(0), "localizedDescription"));
			}
			Objc.send(lib, "release");
			return null;
		} finally {
			Objc.objc_autoreleasePoolPop(pool);
		}
	}

	/** Splits a native result into reflection (block offsets) and MSL. */
	private static String msl(String result, Linked l, boolean fragment) {
		int at = result.indexOf("%%MSL%%\n");
		for (String line : result.substring(3, at).split("\n")) {
			String[] p = line.split(" ");
			if (p[0].equals("U")) {
				Integer i = l.memberIndex.get(p[1]);
				if (i != null && l.members.get(i).offset < 0) {
					Member m = l.members.get(i);
					m.offset = Integer.parseInt(p[2]);
					m.size = Integer.parseInt(p[3]);
					l.blockSize = Math.max(l.blockSize, m.offset + m.size);
				}
			} else if (p[0].equals("E")) {
				if (fragment) {
					l.fragmentEntry = p[1];
				} else {
					l.vertexEntry = p[1];
				}
			}
		}
		l.blockSize = (l.blockSize + 15) & ~15;
		return result.substring(at + 8);
	}

	private static int slotCount(String type) {
		return type.startsWith("mat4") ? 4 : type.startsWith("mat3") ? 3 : type.startsWith("mat2") ? 2 : 1;
	}

	private static int intOr(String s, int def) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static String numbered(String src) {
		StringBuilder b = new StringBuilder();
		String[] lines = src.split("\n");
		for (int i = 0; i < lines.length; i++) {
			b.append(i + 1).append(": ").append(lines[i]).append('\n');
		}
		return b.toString();
	}

	/** Preprocesses, then splits the stage into top-level statements and pulls out its interface declarations. */
	private static Stage scan(String source, boolean fragment, Linked l) {
		Stage s = new Stage(fragment);
		Matcher vm = VERSION.matcher(source);
		if (vm.find()) {
			s.version = Integer.parseInt(vm.group(1));
		}
		String pre = Objc.glslPreprocess(source, fragment);
		if (pre.startsWith("ERR")) {
			l.error = (fragment ? "fragment" : "vertex") + " preprocess: " + pre.substring(4);
			return s;
		}
		StringBuilder code = new StringBuilder();
		for (String line : pre.substring(3).split("\n")) {
			if (!line.trim().startsWith("#")) {
				code.append(line).append('\n');
			}
		}
		String text = code.toString();
		// Pack functions named like newer built-ins, and identifiers that are newer keywords, get renamed everywhere.
		for (String chunk : statements(text)) {
			if (chunk.indexOf('{') >= 0) {
				Matcher fm = FUNCTION.matcher(chunk);
				if (fm.find() && NEW_BUILTINS.matcher(fm.group(1)).matches()) {
					text = text.replaceAll("\\b" + fm.group(1) + "\\b", "mc_fn_" + fm.group(1));
				}
			}
		}
		text = KEYWORDS.matcher(text).replaceAll("mc_kw_$1");
		text = CXX_KEYWORDS.matcher(text).replaceAll("mc_kw_$1");
		text = METAL_LIBRARY_NAMES.matcher(text).replaceAll("mc_kw_$1");
		if (s.version < 130) { // "texture" is the usual name of the block atlas sampler in 1.x packs, but a function in 4.60
			text = TEXTURE_WORD.matcher(text).replaceAll("mc_kw_texture");
		}
		Matcher tc = TEXCOORD_INDEX.matcher(text);
		while (tc.find()) {
			s.texCoords = Math.max(s.texCoords, Integer.parseInt(tc.group(1)) + 1);
		}
		if (text.contains("gl_TexCoord") && s.texCoords == 0) {
			s.texCoords = 8;
		}
		boolean core = s.version >= 130;
		for (String chunk : statements(text)) {
			Matcher m = chunk.indexOf('{') < 0 ? DECL.matcher(chunk) : null;
			String q = m != null && m.matches() ? " " + m.group(1).replaceAll("layout\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ") + " " : null;
			boolean uniform = q != null && q.contains(" uniform "), attribute = q != null && (q.contains(" attribute ") || core && !fragment && q.contains(" in ")),
				varying = q != null && (q.contains(" varying ") || core && (fragment ? q.contains(" in ") : q.contains(" out "))),
				output = q != null && core && fragment && q.contains(" out ");
			if (!uniform && !attribute && !varying && !output) {
				s.body.append(chunk);
				continue;
			}
			String type = m.group(2);
			String interp = q.contains(" flat ") ? "flat " : q.contains(" noperspective ") ? "noperspective " : "";
			for (String d : declarators(m.group(3))) {
				Matcher dm = DECLARATOR.matcher(d);
				if (!dm.matches()) {
					continue;
				}
				Var var = new Var(interp, type, dm.group(1), dm.group(2), dm.group(3));
				if (uniform) {
					(type.startsWith("sampler") ? s.samplers : s.uniforms).put(var.name, var);
				} else if (attribute) {
					s.attributes.put(var.name, var);
				} else if (varying) {
					s.varyings.put(var.name, var);
				} else {
					s.outputs.put(var.name, var);
				}
			}
		}
		return s;
	}

	private static String assemble(Stage s, String header, TreeMap<String, Var> varyings, Map<String, Integer> slots, Map<String, Integer> attributeLocations) {
		StringBuilder out = new StringBuilder(header);
		String dir = s.fragment ? "in" : "out";
		for (Var x : varyings.values()) {
			String interp = x.qualifiers;
			if (x.type.startsWith("int") || x.type.startsWith("ivec") || x.type.startsWith("uint") || x.type.startsWith("uvec")) {
				interp = "flat ";
			}
			out.append("layout(location = ").append(slots.get(x.name)).append(") ").append(interp).append(dir).append(' ').append(x.decl()).append(";\n");
		}
		String firstOutput = "mc_FragData[0]";
		if (s.fragment) {
			if (s.outputs.isEmpty()) {
				out.append("layout(location = 0) out vec4 mc_FragData[8];\n");
			}
			int loc = 0;
			for (Var o : s.outputs.values()) { // GLSL 1.30+ style outputs (rare in 1.8.9 packs)
				if (loc == 0) {
					firstOutput = o.name;
				}
				out.append("layout(location = ").append(loc++).append(") out ").append(o.decl()).append(";\n");
			}
		} else {
			out.append("layout(std430, set = 0, binding = 1) readonly buffer mc_OffsetBlock { vec4 mc_Offsets[]; };\n");
			out.append("layout(location = 0) in vec4 mc_VertexIn;\nlayout(location = 1) in vec4 mc_Color;\nlayout(location = 2) in vec4 mc_MultiTexCoord0;\n");
			out.append("layout(location = 3) in vec4 mc_MultiTexCoord1;\nlayout(location = 4) in vec3 mc_Normal;\n");
			out.append("vec4 mc_Vertex;\nvec4 mc_BackColor;\nvec4 mc_SecondaryColorOut;\nvec4 mc_ClipVertex;\n");
			int next = 13;
			for (Var a : s.attributes.values()) {
				Integer loc = attributeLocations.get(a.name);
				out.append("layout(location = ").append(loc != null ? loc : next++).append(") in ").append(a.decl()).append(";\n");
			}
		}
		String body = s.body.toString().replaceAll("\\bftransform\\s*\\(\\s*\\)", "(mc_ModelViewProjectionMatrix * mc_Vertex)");
		final Map<String, String> stage = s.fragment ? FRAGMENT_BUILTINS : VERTEX_BUILTINS;
		Matcher m = BUILTIN.matcher(body);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String r = stage.get(m.group());
			if (r == null) {
				r = SHARED_BUILTINS.get(m.group());
			}
			if (m.group().equals("gl_Fog")) {
				r = "mc_Fog";
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(r != null ? r : m.group()));
		}
		m.appendTail(sb);
		body = sb.toString();
		// gl_Fog.* as the two vec4s in the block.
		body = body.replace("mc_Fog.color", "mc_FogColor").replace("mc_Fog.density", "mc_FogParams.x").replace("mc_Fog.start", "mc_FogParams.y")
			.replace("mc_Fog.end", "mc_FogParams.z").replace("mc_Fog.scale", "mc_FogParams.w");
		body = MAIN.matcher(body).replaceFirst("void mc_main()");
		out.append(body).append('\n');
		if (s.fragment) {
			out.append("bool mc_alphaPass(float a) {\n\tswitch (mc_AlphaFunc) {\n\t\tcase 512: return false;\n\t\tcase 513: return a < mc_AlphaRef;\n")
				.append("\t\tcase 514: return a == mc_AlphaRef;\n\t\tcase 515: return a <= mc_AlphaRef;\n\t\tcase 516: return a > mc_AlphaRef;\n")
				.append("\t\tcase 517: return a != mc_AlphaRef;\n\t\tcase 518: return a >= mc_AlphaRef;\n\t\tdefault: return true;\n\t}\n}\n")
				.append("void main() {\n\tmc_main();\n\tif (!mc_alphaPass(").append(firstOutput).append(".a)) discard;\n}\n");
		} else {
			// Same clip-space convention as Graft's fixed-function shader: y flipped (render targets keep GL row order), z to [0, w].
			out.append("void main() {\n\tmc_Vertex = mc_VertexIn + vec4(mc_Offsets[gl_BaseInstance].xyz, 0.0);\n\tmc_FrontColor = vec4(1.0);\n\tmc_main();\n")
				.append("\tgl_Position.y = -gl_Position.y;\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n");
		}
		return out.toString();
	}

	/** Top-level statements: declarations up to ';' at depth 0, and whole function definitions. */
	private static List<String> statements(String text) {
		List<String> out = new ArrayList<String>();
		int depth = 0, start = 0;
		boolean body = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '{' || c == '(' || c == '[') {
				if (c == '{' && depth == 0) {
					body = true;
				}
				depth++;
			} else if (c == '}' || c == ')' || c == ']') {
				depth--;
				if (c == '}' && depth == 0 && body) {
					int end = i + 1;
					// a struct declaration ends with "} name;" or "};"
					int j = end;
					while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
						j++;
					}
					if (j < text.length() && (text.charAt(j) == ';' || Character.isJavaIdentifierStart(text.charAt(j))) && text.substring(start, i).trim().startsWith("struct")) {
						end = text.indexOf(';', j) + 1;
						i = end - 1;
					}
					out.add(text.substring(start, end) + "\n");
					start = end;
					body = false;
				}
			} else if (c == ';' && depth == 0) {
				out.add(text.substring(start, i + 1) + "\n");
				start = i + 1;
			}
		}
		if (start < text.length() && !text.substring(start).trim().isEmpty()) {
			out.add(text.substring(start));
		}
		return out;
	}

	/** Splits "a, b[2] = x, c" at top-level commas. */
	private static List<String> declarators(String list) {
		List<String> out = new ArrayList<String>();
		int depth = 0, start = 0;
		for (int i = 0; i < list.length(); i++) {
			char c = list.charAt(i);
			if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(list.substring(start, i));
				start = i + 1;
			}
		}
		out.add(list.substring(start));
		return out;
	}

	private MetalGlsl() {
	}
}
