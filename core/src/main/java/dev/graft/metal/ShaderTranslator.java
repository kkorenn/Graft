package dev.graft.metal;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * SPIR-V -> Metal Shading Language through SPIRV-Cross (lwjgl-spvc, which Minecraft already ships).
 *
 * Input SPIR-V must already be linked the way Minecraft's Vulkan backend expects: descriptor set 0, binding i = uniform i,
 * vertex inputs at their attribute locations, vertex outputs/fragment inputs on matching locations.
 * Binding i becomes [[buffer(i)]] for uniform buffers and [[texture(i)]] (+ [[sampler(i)]]) for images and texel buffers.
 * Vertex buffers live at [[buffer(30 - slot)]] and push constants at [[buffer(PUSH_CONSTANTS)]].
 */
public final class ShaderTranslator {
	public static final int PUSH_CONSTANTS = 24;
	/** MSL 3.1 (macOS 14+) has native texture atomics (GLSL imageAtomic*); older systems get 2.3. */
	static final int MSL_VERSION = macOSMajor() >= 14 ? 30100 : 20300;

	private static int macOSMajor() {
		try {
			return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
	public static final int MAX_VERTEX_BUFFERS = 6;
	private static final int EXEC_VERTEX = 0;
	private static final int EXEC_TESS_EVAL = 2;
	private static final int EXEC_FRAGMENT = 4;
	private static final int EXEC_COMPUTE = 5;
	private static final int EXECUTION_MODE_LOCAL_SIZE = 17;

	public enum Kind {
		UNIFORM_BUFFER,
		SAMPLED_IMAGE,
		TEXEL_BUFFER,
		/** GLSL buffer block (SSBO): [[buffer(i)]] in device memory. */
		STORAGE_BUFFER,
		/** GLSL image (image load/store): [[texture(i)]] with read/write access. */
		STORAGE_IMAGE
	}

	private ShaderTranslator() {
	}

	/**
	 * Metal buffer index for binding i: buffers are numbered among buffers only, so they stay below the push-constant
	 * and vertex-buffer indices however many textures come first. Textures and samplers keep index i.
	 */
	public static int bufferSlot(List<Kind> bindings, int i) {
		if (bindings == null || i >= bindings.size()) {
			return i;
		}
		int slot = slots(bindings)[0][i];
		if (slot >= PUSH_CONSTANTS) {
			throw new IllegalStateException("Too many buffers for Metal: binding " + i + " would use buffer index " + slot);
		}
		return slot;
	}

	/** Metal sampler index for binding i: sampled images numbered among themselves (see bufferSlot). */
	public static int samplerSlot(List<Kind> bindings, int i) {
		if (bindings == null || i >= bindings.size()) {
			return i;
		}
		// Past 16 the MSL is invalid unless the caller rewrites it to a shared sampler array (Graft Shaders does); Metal's
		// compiler reports it otherwise.
		return slots(bindings)[1][i];
	}

	/** {bufferSlots, samplerSlots} per binding list, computed once (lists belong to long-lived pipelines/programs). */
	private static final java.util.Map<List<Kind>, int[][]> SLOTS = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

	static int[][] slots(List<Kind> bindings) {
		int[][] cached = SLOTS.get(bindings);
		if (cached != null && cached[0].length == bindings.size()) { // lists only grow (linker appends slots)
			return cached;
		}
		int[] buffers = new int[bindings.size()];
		int[] samplers = new int[bindings.size()];
		int b = 0;
		int s = 0;
		for (int k = 0; k < bindings.size(); k++) {
			buffers[k] = b;
			samplers[k] = s;
			if (bindings.get(k) == Kind.UNIFORM_BUFFER || bindings.get(k) == Kind.STORAGE_BUFFER) {
				b++;
			}
			if (bindings.get(k) == Kind.SAMPLED_IMAGE) {
				s++;
			}
		}
		int[][] result = {buffers, samplers};
		if (SLOTS.size() > 4096) {
			SLOTS.clear(); // pack reloads create new lists; keep the memo bounded
		}
		SLOTS.put(bindings, result);
		return result;
	}

	public static int vertexBufferIndex(int slot) {
		return 30 - slot;
	}

	/** Compute shader translation result: MSL ({@code main0}) and the GLSL local_size (threads per threadgroup). */
	public record Compute(String msl, int localX, int localY, int localZ) {
	}

	public static Compute toMslCompute(String name, ByteBuffer spirv, List<Kind> bindings) {
		int[] local = new int[3];
		String msl = translate(name, spirv, EXEC_COMPUTE, bindings, local);
		return new Compute(msl, Math.max(1, local[0]), Math.max(1, local[1]), Math.max(1, local[2]));
	}

	/** Tessellation evaluation shader as a Metal post-tessellation vertex function ({@code main0}). */
	public static String toMslTessEval(String name, ByteBuffer spirv, List<Kind> bindings) {
		return toMslTessEval(name, spirv, bindings, false);
	}

	/** glClipDepth: the shader outputs GL clip-space depth ([-w, w], Minecraft 1.21.x matrices); remap it to Metal's [0, w]. */
	public static String toMslTessEval(String name, ByteBuffer spirv, List<Kind> bindings, boolean glClipDepth) {
		return translate(name, spirv, EXEC_TESS_EVAL, bindings, null, glClipDepth);
	}

	/** Returns MSL source whose entry point is named {@code main0}. */
	public static String toMsl(String name, ByteBuffer spirv, boolean vertex, List<Kind> bindings) {
		return toMsl(name, spirv, vertex, bindings, false);
	}

	/** glClipDepth: see {@link #toMslTessEval(String, ByteBuffer, List, boolean)}. */
	public static String toMsl(String name, ByteBuffer spirv, boolean vertex, List<Kind> bindings, boolean glClipDepth) {
		return translate(name, spirv, vertex ? EXEC_VERTEX : EXEC_FRAGMENT, bindings, null, glClipDepth);
	}

	private static String translate(String name, ByteBuffer spirv, int stage, List<Kind> bindings, int[] localSize) {
		return translate(name, spirv, stage, bindings, localSize, false);
	}

	private static String translate(String name, ByteBuffer spirv, int stage, List<Kind> bindings, int[] localSize, boolean glClipDepth) {
		// The last stage before rasterization gets the Y flip.
		boolean vertex = stage == EXEC_VERTEX || stage == EXEC_TESS_EVAL;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer ptr = stack.callocPointer(1);
			check(Spvc.spvc_context_create(ptr), 0L, name, "create context");
			long context = ptr.get(0);
			try {
				check(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, ptr), context, name, "parse SPIR-V");
				long ir = ptr.get(0);
				check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), context, name, "create compiler");
				long compiler = ptr.get(0);

				check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), context, name, "create options");
				long options = ptr.get(0);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
				// GL 1D textures are stored as height-1 2D textures (Minecraft/Iris have no 1D texture type).
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_1D_AS_2D, true);
				// Metal's NDC Y points the opposite way from Vulkan's framebuffer; flipping makes render targets match the
				// Vulkan/GL memory layout Minecraft assumes (row 0 = bottom), with clockwise front faces like the Vulkan backend.
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, vertex);
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, vertex && glClipDepth);
				// GL lets a fragment shader write fewer components than the attachment has (Photon: vec3 into RGBA8);
				// Metal rejects such pipelines, so outputs are padded to 4 components.
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
				// Shader packs are GL: tessellation domain origin is lower-left.
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TESS_DOMAIN_ORIGIN_LOWER_LEFT, true);
				check(Spvc.spvc_compiler_install_compiler_options(compiler, options), context, name, "install options");

				SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
				for (int i = 0; i < bindings.size(); i++) {
					Spvc.spvc_msl_resource_binding_init(binding);
					binding.stage(stage).desc_set(0).binding(i);
					switch (bindings.get(i)) {
						case UNIFORM_BUFFER, STORAGE_BUFFER -> binding.msl_buffer(bufferSlot(bindings, i));
						case STORAGE_IMAGE -> binding.msl_texture(i);
						case SAMPLED_IMAGE -> binding.msl_texture(i).msl_sampler(samplerSlot(bindings, i));
						case TEXEL_BUFFER -> binding.msl_texture(i);
					}
					check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), context, name, "add binding " + i);
				}
				Spvc.spvc_msl_resource_binding_init(binding);
				binding.stage(stage).desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING).msl_buffer(PUSH_CONSTANTS);
				check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), context, name, "add push constants");

				if (localSize != null) {
					for (int i = 0; i < 3; i++) {
						localSize[i] = Spvc.spvc_compiler_get_execution_mode_argument_by_index(compiler, EXECUTION_MODE_LOCAL_SIZE, i);
					}
				}
				check(Spvc.spvc_compiler_compile(compiler, ptr), context, name, "compile");
				return padAlphaOne(MemoryUtil.memUTF8(ptr.get(0)));
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}

	private static final java.util.regex.Pattern PADDED_OUTPUT = java.util.regex.Pattern.compile("\\bout\\.(\\w+)\\.(?:x|xy|xyz) = ");

	/**
	 * Padded fragment outputs (see PAD_FRAGMENT_OUTPUT_COMPONENTS) only get .x/.xy/.xyz written into a zeroed struct, so
	 * alpha would be 0; GL drivers give 1 (Photon's final pass writes vec3 and came out fully transparent = black).
	 */
	static String padAlphaOne(String msl) {
		java.util.Set<String> padded = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher m = PADDED_OUTPUT.matcher(msl);
		while (m.find()) {
			// Only 4-component color attachments get padded; vertex outputs and full-width writes are left alone.
			if (java.util.regex.Pattern.compile("\\b\\w+4 " + m.group(1) + " \\[\\[color\\(").matcher(msl).find()) {
				padded.add(m.group(1));
			}
		}
		if (padded.isEmpty() || !msl.contains("main0_out out = {};")) {
			return msl;
		}
		StringBuilder init = new StringBuilder("main0_out out = {};");
		padded.forEach(n -> init.append("\n    out.").append(n).append(".w = 1;"));
		return msl.replace("main0_out out = {};", init);
	}

	private static void check(int result, long context, String name, String what) {
		if (result != 0) {
			String detail = context != 0L ? Spvc.spvc_context_get_last_error_string(context) : "";
			throw new IllegalStateException("SPIRV-Cross failed to " + what + " for " + name + " (" + result + "): " + detail);
		}
	}
}
