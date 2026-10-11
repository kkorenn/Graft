package dev.graft.v26_3;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.graft.metal.MetalPipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** uniforms[i] is bound at Metal slot i; slots maps a uniform name to i. */
public record MetalRenderPipeline(MetalPipeline pipeline, List<BindGroupLayout.UniformDescription> uniforms, Map<String, Integer> slots) implements BackendRenderPipeline {
	public MetalRenderPipeline(MetalPipeline pipeline, List<BindGroupLayout.UniformDescription> uniforms) {
		this(pipeline, uniforms, slots(uniforms));
	}

	private static Map<String, Integer> slots(List<BindGroupLayout.UniformDescription> uniforms) {
		Map<String, Integer> slots = new HashMap<>();
		for (int i = 0; i < uniforms.size(); i++) {
			slots.putIfAbsent(uniforms.get(i).name(), i);
		}
		return Map.copyOf(slots);
	}

	@Override
	public boolean isClosed() {
		return pipeline.isClosed();
	}

	@Override
	public void close() {
		pipeline.close();
	}
}
