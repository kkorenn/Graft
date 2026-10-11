package dev.graft.v26_3;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.graft.metal.MetalRenderPass;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class MetalRenderPassBackend implements RenderPassBackend {
	private final MetalGpuDevice device;
	private final MetalRenderPass pass;
	private final List<@Nullable Object> uniforms = new ArrayList<>();
	private @Nullable MetalRenderPipeline pipeline;
	private boolean dirty;
	/** While not dirty: the one uniform index set to a new value since the last bind (only it needs rebinding), or -1. */
	private int changedSlot = -1;
	static @Nullable DrawOverride drawOverride;
	private boolean overridden;
	private int indexSize = 2;
	private int generation;
	/** Per slot, what this backend last bound on the pass: {buffer, offset} or {texture, sampler}; 0 = unknown. */
	private long[] boundA = new long[16];
	private long[] boundB = new long[16];

	MetalRenderPassBackend(MetalGpuDevice device, MetalRenderPass pass) {
		this.device = device;
		this.pass = pass;
	}

	/** API: the backend pipeline set on this pass (Graft Shaders maps it back to the vanilla RenderPipeline). */
	public @Nullable MetalRenderPipeline backendPipeline() {
		return pipeline;
	}

	/** API: the underlying Metal render pass. */
	public MetalRenderPass metal() {
		return pass;
	}

	private @Nullable Object uniform(String name) {
		if (pipeline == null) {
			return null;
		}
		Integer slot = pipeline.slots().get(name);
		return slot != null && slot < uniforms.size() ? uniforms.get(slot) : null;
	}

	/** Live view: buffer uniforms (uniform and texel buffers) set on this pass, by name. */
	private final java.util.Map<String, GpuBufferSlice> uniformView = new java.util.AbstractMap<>() {
		@Override
		public @Nullable GpuBufferSlice get(Object name) {
			return name instanceof String s && uniform(s) instanceof GpuBufferSlice slice ? slice : null;
		}

		@Override
		public boolean containsKey(Object name) {
			return get(name) != null;
		}

		@Override
		public java.util.Set<Entry<String, GpuBufferSlice>> entrySet() {
			java.util.Map<String, GpuBufferSlice> map = new java.util.HashMap<>();
			if (pipeline != null) {
				List<BindGroupLayout.UniformDescription> descriptions = pipeline.uniforms();
				for (int i = 0; i < descriptions.size() && i < uniforms.size(); i++) {
					if (uniforms.get(i) instanceof GpuBufferSlice slice) {
						map.putIfAbsent(descriptions.get(i).name(), slice);
					}
				}
			}
			return java.util.Collections.unmodifiableMap(map).entrySet();
		}
	};

	/** API: buffer uniforms (uniform and texel buffers) set on this pass, by name. A live read-only view. */
	public java.util.Map<String, GpuBufferSlice> uniforms() {
		return uniformView;
	}

	/** API: texture bound under a sampler name, or null. */
	public @Nullable GpuTextureView textureView(String name) {
		return uniform(name) instanceof TextureViewAndSampler bound ? bound.view() : null;
	}

	/** API: sampler bound under a sampler name, or null. */
	public @Nullable GpuSampler sampler(String name) {
		return uniform(name) instanceof TextureViewAndSampler bound ? bound.sampler() : null;
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		pass.pushDebugGroup(label.get());
	}

	@Override
	public void popDebugGroup() {
		pass.popDebugGroup();
	}

	@Override
	public void setPipeline(BackendRenderPipeline backendPipeline) {
		pipeline = (MetalRenderPipeline)backendPipeline;
		applyPipeline();
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		while (uniforms.size() <= index) {
			uniforms.add(null);
		}
		Object old = uniforms.set(index, value);
		if (dirty || java.util.Objects.equals(old, value)) {
			return;
		}
		if (changedSlot < 0 || changedSlot == index) {
			changedSlot = index;
		} else {
			dirty = true;
		}
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		pass.setPushConstants(value);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		pass.setScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		pass.disableScissor();
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer != null) {
			pass.setVertexBuffer(slot, MetalCommandEncoder.mtl(vertexBuffer.buffer()), vertexBuffer.offset());
		}
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		pass.setIndexBuffer(MetalCommandEncoder.mtl(indexBuffer), indexType == IndexType.SHORT ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
		indexSize = indexType == IndexType.SHORT ? 2 : 4;
	}

	/** Sets the pipeline on the pass unless already set there. Slot indices depend on the pipeline, so a change forgets bindings. */
	private void applyPipeline() {
		dirty = true;
		if (pass.pipeline() != pipeline.pipeline()) {
			pass.setPipeline(pipeline.pipeline());
			forgetBindings();
		}
	}

	private void forgetBindings() {
		java.util.Arrays.fill(boundA, 0L);
		dirty = true;
	}

	private void bind() {
		if (pipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}
		if (pass.generation() != generation) {
			generation = pass.generation();
			forgetBindings();
		}
		if (drawOverride != null && drawOverride.beforeDraw(this)) {
			overridden = true;
			return;
		}
		if (overridden) {
			// The override bound its own pipeline and resources directly on the pass.
			overridden = false;
			pass.setPipeline(pipeline.pipeline());
			forgetBindings();
		}
		if (!dirty && changedSlot < 0) {
			return;
		}
		List<BindGroupLayout.UniformDescription> descriptions = pipeline.uniforms();
		int count = descriptions.size();
		if (boundA.length < count) {
			boundA = new long[count];
			boundB = new long[count];
		}
		int from = dirty ? 0 : changedSlot;
		int to = dirty ? count : Math.min(count, changedSlot + 1);
		for (int i = from; i < to; i++) {
			BindGroupLayout.UniformDescription description = descriptions.get(i);
			Object value = i < uniforms.size() ? uniforms.get(i) : null;
			if (value == null) {
				throw new IllegalStateException("Missing uniform " + description.name() + " (should be " + description.type() + ")");
			}
			long a;
			long b;
			switch (description.type()) {
				case UNIFORM_BUFFER -> {
					GpuBufferSlice slice = (GpuBufferSlice)value;
					a = MetalCommandEncoder.mtl(slice.buffer());
					b = slice.offset();
					if (boundA[i] != a || boundB[i] != b) {
						pass.setBuffer(i, a, b);
					}
				}
				case COMBINED_IMAGE_SAMPLER -> {
					TextureViewAndSampler bound = (TextureViewAndSampler)value;
					a = ((MetalTexture.View)bound.view()).mtl;
					b = ((MetalSampler)bound.sampler()).mtl;
					if (boundA[i] != a || boundB[i] != b) {
						pass.setTexture(i, a, b);
					}
				}
				default -> {
					GpuBufferSlice slice = (GpuBufferSlice)value;
					GpuFormat format = description.gpuFormat();
					a = device.commands.texelBufferTexture(
						MetalCommandEncoder.mtl(slice.buffer()), slice.offset(), slice.length(), Mtl.pixelFormat(format.name()), format.blockSize()
					);
					b = 0L;
					if (boundA[i] != a || boundB[i] != b) {
						pass.setTexture(i, a, b);
					}
				}
			}
			boundA[i] = a;
			boundB[i] = b;
		}
		dirty = false;
		changedSlot = -1;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		bind();
		pass.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		bind();
		// VkMultiDrawIndexedInfoEXT: firstIndex, indexCount, vertexOffset
		int p = drawParameters.position();
		for (int i = 0; i < drawCount; i++, p += 3) {
			pass.drawIndexed(drawParameters.get(p + 1), instanceCount, drawParameters.get(p), drawParameters.get(p + 2), firstInstance);
		}
	}

	@Override
	/** GL-style multi-draw (byte offsets into the index buffer). Metal has no multi-draw call, so it is a loop of draws. */
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		bind();
		for (int i = 0; i < drawCount; i++) {
			pass.drawIndexed(indexCounts.get(indexCounts.position() + i), 1, (int)(firstIndexOffsets.get(firstIndexOffsets.position() + i) / indexSize), vertexOffsets.get(vertexOffsets.position() + i), 0);
		}
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		bind();
		pass.drawIndexedIndirect(MetalCommandEncoder.mtl(commands.buffer()), commands.offset(), drawCount);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		bind();
		pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		bind();
		// VkMultiDrawInfoEXT: firstVertex, vertexCount
		int p = drawParameters.position();
		for (int i = 0; i < drawCount; i++, p += 2) {
			pass.draw(drawParameters.get(p + 1), instanceCount, drawParameters.get(p), firstInstance);
		}
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		bind();
		for (int i = 0; i < drawCount; i++) {
			pass.draw(vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
		}
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		bind();
		pass.drawIndirect(MetalCommandEncoder.mtl(commands.buffer()), commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		((MetalQueryPool)pool).write(index);
	}
}
