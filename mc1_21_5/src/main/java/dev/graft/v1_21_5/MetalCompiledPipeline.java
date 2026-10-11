package dev.graft.v1_21_5;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import dev.graft.metal.GlShaders;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.ShaderTranslator;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * names[i] / kinds[i] is bound at Metal slot i. 1.21.5 shaders use loose uniforms: they live in one std140 block at
 * uniformSlot whose bytes are kept here, like the uniform state of a GL program (values persist between passes).
 */
public final class MetalCompiledPipeline implements CompiledRenderPipeline {
	static final MetalCompiledPipeline INVALID = new MetalCompiledPipeline(null, List.of(), List.of(), -1, 0, Map.of());

	final @Nullable MetalPipeline pipeline;
	final List<String> names;
	final List<ShaderTranslator.Kind> kinds;
	final int uniformSlot;
	final Map<String, GlShaders.Member> uniforms;
	final ByteBuffer block;

	MetalCompiledPipeline(@Nullable MetalPipeline pipeline, List<String> names, List<ShaderTranslator.Kind> kinds, int uniformSlot, int uniformSize,
						  Map<String, GlShaders.Member> uniforms) {
		this.pipeline = pipeline;
		this.names = names;
		this.kinds = kinds;
		this.uniformSlot = uniformSlot;
		this.uniforms = uniforms;
		this.block = ByteBuffer.allocateDirect(Math.max(16, uniformSize)).order(ByteOrder.nativeOrder());
	}

	@Override
	public boolean isValid() {
		return pipeline != null;
	}

	@Override
	public boolean containsUniform(String name) {
		return uniforms.containsKey(name) || names.contains(name);
	}

	/** Writes a uniform value (floats or ints, matrices column-major) into the block; false if the shader lacks it. */
	boolean set(String name, float[] f, int[] i) {
		GlShaders.Member m = uniforms.get(name);
		if (m == null) {
			return false;
		}
		int n = f != null ? f.length : i.length;
		int columns = Math.max(1, m.columns()), vec = Math.max(1, m.vecSize());
		int perElement = columns * vec, elements = Math.max(1, m.arraySize());
		for (int k = 0; k < n && k < perElement * elements; k++) {
			int e = k / perElement, c = k % perElement / vec, r = k % vec;
			int at = m.offset() + e * m.arrayStride() + c * (columns > 1 ? m.matrixStride() : 0) + r * 4;
			if (at + 4 > block.capacity()) {
				break;
			}
			if (f != null) {
				block.putFloat(at, f[k]);
			} else {
				block.putInt(at, i[k]);
			}
		}
		return true;
	}

	void close() {
		if (pipeline != null) {
			pipeline.close();
		}
	}
}
