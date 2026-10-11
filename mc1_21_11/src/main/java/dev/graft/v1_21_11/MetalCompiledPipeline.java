package dev.graft.v1_21_11;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.ShaderTranslator;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** names[i] / kinds[i] is the uniform block or sampler bound at Metal slot i; texelFormats[i] for texel buffers. */
public record MetalCompiledPipeline(@Nullable MetalPipeline pipeline, List<String> names, List<ShaderTranslator.Kind> kinds, TextureFormat[] texelFormats)
	implements CompiledRenderPipeline {
	static final MetalCompiledPipeline INVALID = new MetalCompiledPipeline(null, List.of(), List.of(), new TextureFormat[0]);

	@Override
	public boolean isValid() {
		return pipeline != null;
	}

	void close() {
		if (pipeline != null) {
			pipeline.close();
		}
	}
}
