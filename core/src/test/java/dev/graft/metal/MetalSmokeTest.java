package dev.graft.metal;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;

/** Renders through the whole core stack on the real GPU and checks pixels. Run: ./gradlew :core:smokeTest */
public final class MetalSmokeTest {
	static final String VS = """
		#version 450
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec4 Color;
		layout(std140, set = 0, binding = 0) uniform Tint { vec4 tint; };
		layout(location = 0) out vec4 vColor;
		void main() { gl_Position = vec4(Position, 1.0); vColor = Color * tint; }
		""";
	static final String FS = """
		#version 450
		layout(location = 0) in vec4 vColor;
		layout(set = 0, binding = 1) uniform sampler2D Tex;
		layout(location = 0) out vec4 fragColor;
		void main() { fragColor = vColor * texture(Tex, vec2(0.5)); }
		""";

	public static void main(String[] args) {
		MetalDevice device = new MetalDevice();
		System.out.println("Metal device: " + device.name);
		// Samplers are packed (Metal has 16 slots): 16 sampled images after 2 buffers must still fit.
		List<ShaderTranslator.Kind> many = new java.util.ArrayList<>(List.of(ShaderTranslator.Kind.UNIFORM_BUFFER, ShaderTranslator.Kind.UNIFORM_BUFFER));
		for (int i = 0; i < 16; i++) {
			many.add(ShaderTranslator.Kind.SAMPLED_IMAGE);
		}
		assert ShaderTranslator.samplerSlot(many, 2) == 0 && ShaderTranslator.samplerSlot(many, 17) == 15 : "sampler packing";
		MetalCommands commands = new MetalCommands(device);
		List<ShaderTranslator.Kind> bindings = List.of(ShaderTranslator.Kind.UNIFORM_BUFFER, ShaderTranslator.Kind.SAMPLED_IMAGE);

		MetalPipeline pipeline = new MetalPipeline(
			device, "smoke",
			ShaderTranslator.toMsl("smoke.vsh", spirv(VS, 0), true, bindings),
			ShaderTranslator.toMsl("smoke.fsh", spirv(FS, 1), false, bindings),
			bindings,
			List.of(new MetalPipeline.VertexBuffer(0, 16, 0)),
			List.of(new MetalPipeline.Attribute(0, 0, 0, Mtl.vertexFormat("RGB32_FLOAT")), new MetalPipeline.Attribute(0, 1, 12, Mtl.vertexFormat("RGBA8_UNORM"))),
			new MetalPipeline.ColorTarget[]{MetalPipeline.ColorTarget.opaque(Mtl.pixelFormat("RGBA8_UNORM"), Mtl.writeMask(15))},
			new MetalPipeline.Raster(Mtl.PRIM_TRIANGLE, false, true, false, -1, false, 0, 0)
		);

		// Triangle covering the bottom half of NDC (y in [-1, 0]), counter-clockwise like GL front faces.
		long vb = device.newBuffer(48);
		MemorySegment v = MemorySegment.ofAddress(MetalDevice.contents(vb)).reinterpret(48);
		float[][] pos = {{-1, -1, 0}, {3, -1, 0}, {-1, 0, 0}};
		for (int i = 0; i < 3; i++) {
			v.set(JAVA_FLOAT, i * 16, pos[i][0]);
			v.set(JAVA_FLOAT, i * 16 + 4, pos[i][1]);
			v.set(JAVA_FLOAT, i * 16 + 8, pos[i][2]);
			v.set(JAVA_INT, i * 16 + 12, 0xFF0000FF); // RGBA bytes: FF 00 00 FF (little endian)
		}
		long ubo = device.newBuffer(256);
		MemorySegment u = MemorySegment.ofAddress(MetalDevice.contents(ubo)).reinterpret(16);
		for (int i = 0; i < 4; i++) {
			u.setAtIndex(JAVA_FLOAT, i, 1.0F);
		}

		long white = device.newTexture(Mtl.pixelFormat("RGBA8_UNORM"), false, 1, 1, 1, 1);
		long staging = device.newBuffer(16);
		MemorySegment.ofAddress(MetalDevice.contents(staging)).reinterpret(4).set(JAVA_INT, 0, -1);
		commands.copyBufferToTexture(staging, 0, 4, 1, 1, white, 0, 0, 0, 0);
		long sampler = device.newSampler(0, 0, 0, 0, 1, 0);

		long target = device.newTexture(Mtl.pixelFormat("RGBA8_UNORM"), false, 4, 4, 1, 1);
		MetalRenderPass pass = commands.beginRenderPass(
			new MetalRenderPass.Attachment[]{new MetalRenderPass.Attachment(target, Mtl.pixelFormat("RGBA8_UNORM"), 0, new float[]{0, 0, 0, 0}, Double.NaN)},
			null, 0, 0, 4, 4, "smoke"
		);
		pass.setPipeline(pipeline);
		pass.setBuffer(0, ubo, 0);
		pass.setTexture(1, white, sampler);
		pass.setVertexBuffer(0, vb, 0);
		pass.draw(3, 1, 0, 0);
		// GL-style mid-pass blit: suspends the render encoder; the next draw must resume with contents loaded.
		int generation = pass.generation();
		commands.copyBufferToTexture(staging, 0, 4, 1, 1, white, 0, 0, 0, 0);
		pass.setBuffer(0, ubo, 0); // recorded; the encoder reopens lazily at the next draw and replays bindings
		pass.setTexture(1, white, sampler);
		pass.draw(3, 1, 0, 0);
		assert pass.generation() != generation : "resume must bump the generation";
		commands.endRenderPass();

		long readback = device.newBuffer(64);
		commands.copyTextureToBuffer(target, 0, 0, 0, 4, 4, readback, 0, 16);
		boolean[] ran = {false};
		commands.afterGpu(() -> ran[0] = true);
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		assert ran[0] : "afterGpu callback did not run";

		MemorySegment px = MemorySegment.ofAddress(MetalDevice.contents(readback)).reinterpret(64);
		for (int row = 0; row < 4; row++) {
			StringBuilder sb = new StringBuilder("row " + row + ":");
			for (int col = 0; col < 4; col++) {
				sb.append(String.format(" %08x", px.getAtIndex(JAVA_INT, row * 4 + col)));
			}
			System.out.println(sb);
		}
		// GL/Vulkan memory layout: row 0 is the bottom of NDC, so the bottom-half triangle lands in rows 0-1.
		assert px.getAtIndex(JAVA_INT, 0) == 0xFF0000FF : "row 0 should be red (flip or culling broken)";
		assert px.getAtIndex(JAVA_INT, 4) == 0xFF0000FF : "row 1 should be red";
		assert px.getAtIndex(JAVA_INT, 12) == 0 : "row 3 should be clear";

		// Region clear: color + depth via fullscreen triangle under scissor.
		long depth = device.newTexture(Mtl.PIXEL_DEPTH32F, false, 4, 4, 1, 1);
		commands.clearRegion(target, Mtl.pixelFormat("RGBA8_UNORM"), new float[]{0, 1, 0, 1}, depth, Mtl.PIXEL_DEPTH32F, 1.0, 4, 4, 2, 2, 2, 2);
		commands.copyTextureToBuffer(target, 0, 0, 0, 4, 4, readback, 0, 16);
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		assert px.getAtIndex(JAVA_INT, 2 * 4 + 2) == 0xFF00FF00 : "region clear should be green, got " + Integer.toHexString(px.getAtIndex(JAVA_INT, 10));
		assert px.getAtIndex(JAVA_INT, 0) == 0xFF0000FF : "outside region must be untouched";

		// Compute: GLSL -> SPIR-V -> MSL, dispatch 2 groups of local_size 4 into a storage buffer.
		String cs = """
			#version 450
			layout(local_size_x = 4) in;
			layout(std430, binding = 0) buffer Out { uint v[]; };
			void main() { v[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x * 3u + 1u; }
			""";
		ShaderTranslator.Compute compute = ShaderTranslator.toMslCompute("smoke.csh", spirv(cs, 2 /* shaderc_glsl_compute_shader */), List.of(ShaderTranslator.Kind.STORAGE_BUFFER));
		assert compute.localX() == 4 && compute.localY() == 1 : "local size not reflected: " + compute;
		long computeState = device.newComputePipeline("smoke.csh", compute.msl());
		long ssbo = device.newBuffer(32);
		commands.setComputePipeline(computeState);
		commands.setComputeBuffer(0, ssbo, 0);
		commands.dispatch(2, 1, 1, compute.localX(), compute.localY(), compute.localZ());
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		MemorySegment out = MemorySegment.ofAddress(MetalDevice.contents(ssbo)).reinterpret(32);
		for (int i = 0; i < 8; i++) {
			assert out.getAtIndex(JAVA_INT, i) == i * 3 + 1 : "compute result " + i + " = " + out.getAtIndex(JAVA_INT, i);
		}

		// Deferred clears: folded into the next pass's load action (undrawn pixels green) and flushed before sampling
		// (the sampled texture reads its cleared value, halving the red).
		long target2 = device.newTexture(Mtl.pixelFormat("RGBA8_UNORM"), false, 4, 4, 1, 1);
		commands.deferClear(target2, new float[]{0, 1, 0, 1});
		commands.deferClear(white, new float[]{0.5F, 1, 1, 1});
		MetalRenderPass pass2 = commands.beginRenderPass(
			new MetalRenderPass.Attachment[]{new MetalRenderPass.Attachment(target2, Mtl.pixelFormat("RGBA8_UNORM"), 0, null, Double.NaN)},
			null, 0, 0, 4, 4, "deferred clear"
		);
		pass2.setPipeline(pipeline);
		pass2.setBuffer(0, ubo, 0);
		pass2.setTexture(1, white, sampler);
		pass2.setVertexBuffer(0, vb, 0);
		pass2.draw(3, 1, 0, 0);
		commands.endRenderPass();
		long readback2 = device.newBuffer(64);
		commands.copyTextureToBuffer(target2, 0, 0, 0, 4, 4, readback2, 0, 16);
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		MemorySegment px2 = MemorySegment.ofAddress(MetalDevice.contents(readback2)).reinterpret(64);
		int drawn = px2.getAtIndex(JAVA_INT, 0);
		System.out.printf("deferred clear: drawn %08x, undrawn %08x%n", drawn, px2.getAtIndex(JAVA_INT, 12));
		assert px2.getAtIndex(JAVA_INT, 12) == 0xFF00FF00 : "undrawn pixel should keep the deferred green clear";
		assert (drawn & 0xFF) >= 0x7E && (drawn & 0xFF) <= 0x81 && (drawn >>> 24) == 0xFF : "sampled texture must be cleared before sampling";

		// MetalFX spatial upscale (render scale): solid red 2x2 -> 4x4 stays red.
		if (commands.metalFxAvailable()) {
			long rgba8 = Mtl.pixelFormat("RGBA8_UNORM");
			long small = device.newTexture(rgba8, false, 2, 2, 1, 1);
			long big = device.newTexture(rgba8, false, 4, 4, 1, 1);
			commands.clear(small, 1, new float[]{1, 0, 0, 1}, 0L, 0L, 0, 0.0);
			assert commands.upscale(small, 2, 2, rgba8, big, 4, 4, rgba8) : "MetalFX upscale failed";
			long readback3 = device.newBuffer(64);
			commands.copyTextureToBuffer(big, 0, 0, 0, 4, 4, readback3, 0, 16);
			commands.submit();
			commands.awaitCompletion(commands.submitIndex() - 1, -1);
			MemorySegment px3 = MemorySegment.ofAddress(MetalDevice.contents(readback3)).reinterpret(64);
			System.out.printf("metalfx: %08x %08x%n", px3.getAtIndex(JAVA_INT, 0), px3.getAtIndex(JAVA_INT, 15));
			for (int i = 0; i < 16; i++) {
				int p = px3.getAtIndex(JAVA_INT, i);
				assert (p & 0xFF) > 0xF0 && ((p >> 8) & 0xFF) < 0x10 : "upscaled pixel " + i + " not red: " + Integer.toHexString(p);
			}
		} else {
			System.out.println("metalfx: not available on this system");
		}

		// GL-style program (Sodium 0.8 chunk shader shape): loose uniforms into one reflected block, bound attribute and
		// output locations, comments after declarations, integer attributes, sampler uniforms.
		GlShaders.Program sodium = GlShaders.link("sodium-like", """
			#version 330 core
			in uvec2 a_Position; // packed
			in vec4 a_Color;
			uniform mat4 u_ModelViewMatrix;
			uniform vec3 u_RegionOffset; // region
			uniform bool u_UseRGSS;
			layout(std140) uniform ChunkData { vec4 data[4]; };
			out vec4 v_Color;
			void main() { v_Color = a_Color * (u_UseRGSS ? 1.0 : 0.5) + data[1]; gl_Position = u_ModelViewMatrix * vec4(vec3(a_Position, 0.0) + u_RegionOffset, 1.0); }
			""", """
			#version 330 core
			uniform sampler2D u_BlockTex; // atlas
			uniform float u_FadePeriodInv;
			in vec4 v_Color;
			out vec4 fragColor;
			void main() { fragColor = texture(u_BlockTex, v_Color.xy) * u_FadePeriodInv; }
			""", java.util.Map.of("a_Position", 1, "a_Color", 2), java.util.Map.of("fragColor", 0));
		assert sodium.uniformSlot() >= 0 && sodium.uniforms().get("u_ModelViewMatrix").offset() == 0 && sodium.uniforms().get("u_RegionOffset").offset() == 64
			&& sodium.uniforms().containsKey("u_FadePeriodInv") && sodium.names().contains("ChunkData") && sodium.names().contains("u_BlockTex") : sodium.uniforms();
		new MetalPipeline(device, "sodium-like", sodium.vertexMsl(), sodium.fragmentMsl(), sodium.kinds(), java.util.List.of(), java.util.List.of(),
			new MetalPipeline.ColorTarget[]{MetalPipeline.ColorTarget.opaque(Mtl.pixelFormat("RGBA8_UNORM"), 15)},
			new MetalPipeline.Raster(Mtl.PRIM_TRIANGLE, false, false, false, -1, false, 0, 0)).close();
		System.out.println("gl program: uniforms " + sodium.uniforms().keySet() + " block " + sodium.uniformSize() + " bytes");

		// 3D clears compile and run for every channel type.
		for (String fmt : new String[]{"RGBA16_FLOAT", "R16_UINT", "R32_SINT"}) {
			long vol = device.newTexture3D(Mtl.pixelFormat(fmt), 9, 5, 3);
			commands.clearTexture3D(vol, 9, 5, 3, fmt.endsWith("_UINT") ? 'u' : fmt.endsWith("_SINT") ? 'i' : 'f');
			commands.afterGpu(() -> ObjC.release(vol));
		}
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);

		pipeline.close();
		commands.close();
		System.out.println("SMOKE TEST OK");
	}

	static ByteBuffer spirv(String src, int kind) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
		long result = Shaderc.shaderc_compile_into_spv(compiler, src, kind, "smoke", "main", options);
		if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
			throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
		}
		ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
		ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
		copy.put(bytes).flip();
		Shaderc.shaderc_result_release(result);
		return copy;
	}
}
