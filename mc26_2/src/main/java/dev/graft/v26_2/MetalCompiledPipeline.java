package dev.graft.v26_2;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import dev.graft.metal.MetalPipeline;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** entries[i] is the uniform/sampler bound at Metal slot i. */
public record MetalCompiledPipeline(@Nullable MetalPipeline pipeline, List<VulkanBindGroupLayout.Entry> entries) implements CompiledRenderPipeline {
	static final MetalCompiledPipeline INVALID = new MetalCompiledPipeline(null, List.of());

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
