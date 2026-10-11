package dev.graft.metal;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 1.21.x core shaders (GL-style GLSL 330: named std140 blocks, sampler uniforms, attributes bound by name) to
 * MSL. Every uniform block and sampler gets an explicit binding (its index in {@link Program#names}), attributes their
 * vertex-format slot, varyings a location from the sorted union of both stages' names; then shaderc and SPIRV-Cross.
 * GL clip space has z in [-1, 1]; the vertex stage remaps it to Metal's [0, 1].
 */
public final class GlShaders {
	/**
	 * names.get(i) is bound at Metal slot i with kinds.get(i). Loose (non-block) uniforms live in one std140 block at
	 * uniformSlot (-1 if none) of uniformSize bytes; uniforms maps each to its place in it.
	 */
	public record Program(String vertexMsl, String fragmentMsl, List<String> names, List<ShaderTranslator.Kind> kinds,
						  int uniformSlot, int uniformSize, Map<String, Member> uniforms) {
	}

	/** A loose uniform's place in the uniform block. baseType: SPVC_BASETYPE_*. */
	public record Member(int offset, int baseType, int vecSize, int columns, int arraySize, int arrayStride, int matrixStride) {
	}

	private static final String LOOSE_BLOCK = "MetalLooseUniforms";
	private static final Pattern LOOSE = Pattern.compile("^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*(\\[\\s*\\d+\\s*\\])?\\s*;\\s*$");

	private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+\\d+.*$");
	private static final Pattern BLOCK = Pattern.compile("^\\s*(?:layout\\s*\\(\\s*std140\\s*\\)\\s*)?uniform\\s+(\\w+)\\s*(?:\\{.*)?$");
	private static final Pattern SAMPLER = Pattern.compile("^\\s*uniform\\s+((?:[iu])?sampler\\w+)\\s+(\\w+)\\s*;\\s*$");
	private static final Pattern INOUT = Pattern.compile(
		"^\\s*((?:(?:flat|noperspective|smooth|centroid)\\s+)*)(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[\\s*(\\d+)\\s*\\])?\\s*;\\s*$"
	);
	/**
	 * Names GL GLSL allows but Vulkan GLSL (shaderc) or MSL reserve, renamed wherever they are not a call: Vulkan type
	 * names (vanilla terrain: "sampler2D sampler") and C++/Metal keywords.
	 */
	private static final Pattern RESERVED = Pattern.compile(
		"\\b(sampler|samplerShadow|[iu]?texture(?:1D|2D|3D|Cube|2DArray|Buffer)|new|delete|this|template|typename|namespace|private|public|"
			+ "protected|virtual|friend|explicit|operator|device|thread|threadgroup|constant|kernel|vertex|fragment|bias|level)\\b(?!\\s*\\()"
	);
		private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");

	private GlShaders() {
	}

	/** attributes: vertex attribute names in vertex-format order (location = index). */
	public static Program translate(String name, String vertexSource, String fragmentSource, List<String> attributes) {
		Map<String, Integer> locations = new java.util.HashMap<>();
		for (int i = 0; i < attributes.size(); i++) {
			locations.put(attributes.get(i), i);
		}
		return link(name, vertexSource, fragmentSource, locations, Map.of());
	}

	/**
	 * GL-style program link. attributeLocations / outputLocations: glBindAttribLocation / glBindFragDataLocation
	 * (unlisted ones get the next free location).
	 */
	public static Program link(String name, String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations, Map<String, Integer> outputLocations) {
		vertexSource = RESERVED.matcher(stripComments(vertexSource)).replaceAll("mc_kw_$1");
		fragmentSource = RESERVED.matcher(stripComments(fragmentSource)).replaceAll("mc_kw_$1");
		Map<String, ShaderTranslator.Kind> resources = new LinkedHashMap<>();
		Map<String, Integer> varyingSlots = new TreeMap<>();
		Map<String, String> loose = new LinkedHashMap<>();
		scan(vertexSource, resources, varyingSlots, true);
		scan(fragmentSource, resources, varyingSlots, false);
		collectLoose(vertexSource, loose);
		collectLoose(fragmentSource, loose);
		int uniformSlot = -1;
		String block = "";
		if (!loose.isEmpty()) {
			uniformSlot = resources.size();
			resources.put(LOOSE_BLOCK, ShaderTranslator.Kind.UNIFORM_BUFFER);
			StringBuilder b = new StringBuilder("layout(std140, binding = ").append(uniformSlot).append(") uniform ").append(LOOSE_BLOCK).append(" {\n");
			loose.values().forEach(d -> b.append('\t').append(d).append('\n'));
			block = b.append("};\n").toString();
		}
		List<String> names = new ArrayList<>(resources.keySet());
		List<ShaderTranslator.Kind> kinds = new ArrayList<>(resources.values());
		Map<String, Integer> varyings = new LinkedHashMap<>();
		int next = 0;
		for (Map.Entry<String, Integer> v : varyingSlots.entrySet()) {
			varyings.put(v.getKey(), next);
			next += v.getValue();
		}
		String vs = patch(vertexSource, true, names, varyings, attributeLocations, outputLocations, block);
		String fs = patch(fragmentSource, false, names, varyings, attributeLocations, outputLocations, block);
		ByteBuffer vsSpirv = compile(name + ".vsh", vs, Shaderc.shaderc_glsl_vertex_shader);
		try {
			ByteBuffer fsSpirv = compile(name + ".fsh", fs, Shaderc.shaderc_glsl_fragment_shader);
			try {
				Map<String, Member> members = new java.util.HashMap<>();
				int size = 0;
				if (uniformSlot >= 0) {
					size = Math.max(reflect(vsSpirv, members), reflect(fsSpirv, members));
				}
				return new Program(
					ShaderTranslator.toMsl(name + ".vsh", vsSpirv, true, kinds), ShaderTranslator.toMsl(name + ".fsh", fsSpirv, false, kinds),
					names, kinds, uniformSlot, size, members
				);
			} finally {
				MemoryUtil.memFree(fsSpirv);
			}
		} finally {
			MemoryUtil.memFree(vsSpirv);
		}
	}

	private static void scan(String source, Map<String, ShaderTranslator.Kind> resources, Map<String, Integer> varyings, boolean vertex) {
		int depth = 0;
		for (String line : source.split("\n")) {
			if (depth == 0) {
				Matcher b = BLOCK.matcher(line);
				if (b.matches()) {
					resources.putIfAbsent(b.group(1), ShaderTranslator.Kind.UNIFORM_BUFFER);
				}
				Matcher s = SAMPLER.matcher(line);
				if (s.matches()) {
					resources.putIfAbsent(s.group(2), s.group(1).endsWith("Buffer") ? ShaderTranslator.Kind.TEXEL_BUFFER : ShaderTranslator.Kind.SAMPLED_IMAGE);
				}
				Matcher io = INOUT.matcher(line);
				if (io.matches() && io.group(2).equals(vertex ? "out" : "in")) {
					Matcher mat = Pattern.compile("d?mat(\\d)(?:x\\d)?").matcher(io.group(3));
					int slots = (mat.matches() ? Integer.parseInt(mat.group(1)) : 1) * (io.group(6) != null ? Integer.parseInt(io.group(6)) : 1);
					varyings.merge(io.group(4), slots, Math::max);
				}
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static String patch(String source, boolean vertex, List<String> names, Map<String, Integer> varyings,
								Map<String, Integer> attributes, Map<String, Integer> outputs, String block) {
		StringBuilder out = new StringBuilder();
		int depth = 0;
		int nextOutput = outputs.values().stream().mapToInt(i -> i + 1).max().orElse(0);
		int nextAttribute = attributes.values().stream().mapToInt(i -> i + 1).max().orElse(0);
		for (String line : source.split("\n", -1)) {
			String emit = line;
			if (depth == 0) {
				Matcher b = BLOCK.matcher(line);
				Matcher s = SAMPLER.matcher(line);
				Matcher io = INOUT.matcher(line);
				if (VERSION.matcher(line).matches()) {
					emit = "#version 450 core\n#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n" + block;
				} else if (LOOSE.matcher(line).matches() && !line.contains("sampler")) {
					emit = ""; // moved into the uniform block
				} else if (b.matches()) {
					emit = "layout(std140, binding = " + names.indexOf(b.group(1)) + ") " + line.replaceFirst("^\\s*layout\\s*\\(\\s*std140\\s*\\)\\s*", "").trim();
				} else if (s.matches()) {
					emit = "layout(binding = " + names.indexOf(s.group(2)) + ") " + line.trim();
				} else if (io.matches()) {
					String n = io.group(4);
					int location;
					if (vertex && io.group(2).equals("in")) {
						Integer index = attributes.get(n);
						location = index != null ? index : nextAttribute++;
					} else if (!vertex && io.group(2).equals("out")) {
						Integer index = outputs.get(n);
						location = index != null ? index : nextOutput++;
					} else {
						location = varyings.getOrDefault(n, 0);
					}
					emit = "layout(location = " + location + ") " + line.trim();
				}
			}
			out.append(emit).append('\n');
			depth += count(line, '{') - count(line, '}');
		}
		if (!vertex) {
			return out.toString();
		}
		// GL clip-space depth [-w, w] -> Metal [0, w].
		return MAIN.matcher(out).replaceFirst("void mc_glMain()")
			+ "\nvoid main() {\n\tmc_glMain();\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
	}

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", "");
	}

	private static void collectLoose(String source, Map<String, String> into) {
		int depth = 0;
		for (String line : source.split("\n")) {
			Matcher m = depth == 0 ? LOOSE.matcher(line) : null;
			if (m != null && m.matches() && !m.group(1).contains("sampler") && !m.group(1).contains("image")) {
				// std140 has no bool: GL bools are 4-byte ints there, set through glUniform1i.
				String type = m.group(1).equals("bool") ? "bool" : m.group(1);
				into.putIfAbsent(m.group(2), type + " " + m.group(2) + (m.group(3) != null ? m.group(3) : "") + ";");
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	/** Member offsets of the loose-uniform block in this stage; returns the block size (0 if the stage lacks it). */
	private static int reflect(ByteBuffer spirv, Map<String, Member> into) {
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			org.lwjgl.PointerBuffer ptr = stack.callocPointer(1);
			org.lwjgl.PointerBuffer count = stack.callocPointer(1);
			java.nio.IntBuffer value = stack.callocInt(1);
			org.lwjgl.util.spvc.Spvc.spvc_context_create(ptr);
			long context = ptr.get(0);
			try {
				org.lwjgl.util.spvc.Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, ptr);
				long ir = ptr.get(0);
				org.lwjgl.util.spvc.Spvc.spvc_context_create_compiler(context, org.lwjgl.util.spvc.Spvc.SPVC_BACKEND_NONE, ir, org.lwjgl.util.spvc.Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr);
				long compiler = ptr.get(0);
				org.lwjgl.util.spvc.Spvc.spvc_compiler_create_shader_resources(compiler, ptr);
				long resources = ptr.get(0);
				org.lwjgl.util.spvc.Spvc.spvc_resources_get_resource_list_for_type(resources, org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, ptr, count);
				for (org.lwjgl.util.spvc.SpvcReflectedResource r : org.lwjgl.util.spvc.SpvcReflectedResource.create(ptr.get(0), (int)count.get(0))) {
					if (!LOOSE_BLOCK.equals(org.lwjgl.util.spvc.Spvc.spvc_compiler_get_name(compiler, r.base_type_id()))) {
						continue;
					}
					long blockType = org.lwjgl.util.spvc.Spvc.spvc_compiler_get_type_handle(compiler, r.base_type_id());
					int members = org.lwjgl.util.spvc.Spvc.spvc_type_get_num_member_types(blockType);
					for (int i = 0; i < members; i++) {
						String member = org.lwjgl.util.spvc.Spvc.spvc_compiler_get_member_name(compiler, r.base_type_id(), i);
						long memberType = org.lwjgl.util.spvc.Spvc.spvc_compiler_get_type_handle(compiler, org.lwjgl.util.spvc.Spvc.spvc_type_get_member_type(blockType, i));
						org.lwjgl.util.spvc.Spvc.spvc_compiler_type_struct_member_offset(compiler, blockType, i, value);
						int offset = value.get(0);
						int arraySize = org.lwjgl.util.spvc.Spvc.spvc_type_get_num_array_dimensions(memberType) > 0 ? org.lwjgl.util.spvc.Spvc.spvc_type_get_array_dimension(memberType, 0) : 0;
						int arrayStride = 0;
						if (arraySize > 0) {
							org.lwjgl.util.spvc.Spvc.spvc_compiler_type_struct_member_array_stride(compiler, blockType, i, value);
							arrayStride = value.get(0);
						}
						int columns = org.lwjgl.util.spvc.Spvc.spvc_type_get_columns(memberType);
						int matrixStride = 0;
						if (columns > 1) {
							org.lwjgl.util.spvc.Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, blockType, i, value);
							matrixStride = value.get(0);
						}
						into.put(mc_kwRestore(member), new Member(offset, org.lwjgl.util.spvc.Spvc.spvc_type_get_basetype(memberType),
							org.lwjgl.util.spvc.Spvc.spvc_type_get_vector_size(memberType), columns, arraySize, arrayStride, matrixStride));
					}
					org.lwjgl.PointerBuffer size = stack.callocPointer(1);
					org.lwjgl.util.spvc.Spvc.spvc_compiler_get_declared_struct_size(compiler, blockType, size);
					return (int)size.get(0);
				}
				return 0;
			} finally {
				org.lwjgl.util.spvc.Spvc.spvc_context_destroy(context);
			}
		}
	}

	/** Uniform names renamed away from reserved words are looked up by their original name. */
	private static String mc_kwRestore(String name) {
		return name.startsWith("mc_kw_") ? name.substring(6) : name;
	}

	private static int count(String s, char c) {
		int n = 0;
		int comment = s.indexOf("//");
		int end = comment >= 0 ? comment : s.length();
		for (int i = 0; i < end; i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	/** GLSL to SPIR-V (Vulkan 1.2 rules); the caller frees the returned buffer. */
	public static ByteBuffer compile(String name, String source, int kind) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			ByteBuffer text = MemoryUtil.memUTF8(source, false);
			ByteBuffer file = MemoryUtil.memUTF8(name);
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, file, entry, options);
			} finally {
				MemoryUtil.memFree(text);
				MemoryUtil.memFree(file);
				MemoryUtil.memFree(entry);
			}
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
					throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result) + "\n--- patched source ---\n" + source);
				}
				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
				copy.put(spirv).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
