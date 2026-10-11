package dev.graft.v26_2;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalRenderPass;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class MetalRenderPassBackend implements RenderPassBackend {
	private record Bound(MetalTexture.View view, MetalSampler sampler) {
	}

	private final MetalGpuDevice device;
	private final MetalRenderPass pass;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, Bound> textures = new HashMap<>();
	static @Nullable DrawOverride drawOverride;
	private @Nullable RenderPipeline renderPipeline;
	private @Nullable MetalCompiledPipeline pipeline;
	private boolean pipelineDirty;
	private boolean overridden;
	private int generation;
	private boolean dirty;
	/** While not dirty: the one uniform name set to a new value since the last bind (only its slots need rebinding). */
	private @Nullable String changedUniform;
	private int indexSize = 2;
	/** Per slot, what this backend last bound on the pass: {buffer, offset} or {texture, sampler}; 0 = unknown. */
	private long[] boundA = new long[16];
	private long[] boundB = new long[16];
	private final RenderPass.UniformUploader uniformUploader = this::setUniform;

	MetalRenderPassBackend(MetalGpuDevice device, MetalRenderPass pass) {
		this.device = device;
		this.pass = pass;
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
	public void setPipeline(RenderPipeline renderPipeline) {
		// Applied at draw time, so a DrawOverride can replace it.
		this.renderPipeline = renderPipeline;
		this.pipelineDirty = true;
	}

	/** API: the vanilla pipeline set on this pass. */
	public @Nullable RenderPipeline renderPipeline() {
		return renderPipeline;
	}

	/** API: vanilla uniform buffers set on this pass, by name (e.g. "DynamicTransforms", "Projection"). */
	public Map<String, GpuBufferSlice> uniforms() {
		return java.util.Collections.unmodifiableMap(uniforms);
	}

	/** API: vanilla texture bound under a sampler name (e.g. "Sampler0"), or null. */
	public @Nullable GpuTextureView textureView(String name) {
		Bound b = textures.get(name);
		return b != null ? b.view() : null;
	}

	/** API: vanilla sampler bound under a sampler name, or null. */
	public @Nullable GpuSampler sampler(String name) {
		Bound b = textures.get(name);
		return b != null ? b.sampler() : null;
	}

	/** API: names of all vanilla textures bound on this pass. */
	public java.util.Set<String> textureNames() {
		return java.util.Collections.unmodifiableSet(textures.keySet());
	}

	/** API: the underlying Metal render pass. */
	public MetalRenderPass metal() {
		return pass;
	}

	@Override
	public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		if (textureView != null && sampler != null) {
			Bound bound = new Bound((MetalTexture.View)textureView, (MetalSampler)sampler);
			if (!bound.equals(textures.put(name, bound))) {
				dirty = true;
			}
		} else if (textureView == null && sampler == null) {
			textures.remove(name);
		} else {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		setUniform(name, value.slice());
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		GpuBufferSlice old = uniforms.put(name, value);
		if (dirty || value.equals(old)) {
			return;
		}
		if (changedUniform == null || changedUniform.equals(name)) {
			changedUniform = name;
		} else {
			dirty = true;
		}
	}

	/** Push constants for mods that bypass Blaze3D (Sodium's chunk renderer); lands at the shader's push-constant slot. */
	public void pushConstants(long address, int size) {
		pass.setPushConstants(org.lwjgl.system.MemoryUtil.memByteBuffer(address, size));
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
		indexSize = indexType == IndexType.SHORT ? 2 : 4;
		pass.setIndexBuffer(MetalCommandEncoder.mtl(indexBuffer), indexType == IndexType.SHORT ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
	}

	private void forgetBindings() {
		java.util.Arrays.fill(boundA, 0L);
		dirty = true;
	}

	private void bind() {
		if (pass.generation() != generation) {
			// Encoder was reopened (retarget or resume after a mid-pass copy): bindings must be redone.
			generation = pass.generation();
			pipelineDirty = true;
			forgetBindings();
		}
		if (drawOverride != null && drawOverride.beforeDraw(this)) {
			overridden = true;
			return;
		}
		if (overridden) {
			// The override bound its own pipeline and resources directly on the pass.
			overridden = false;
			pipelineDirty = true;
			forgetBindings();
		}
		if (pipelineDirty) {
			if (renderPipeline == null) {
				throw new IllegalStateException("Pipeline is missing");
			}
			MetalCompiledPipeline compiled = device.getOrCompilePipeline(renderPipeline);
			if (!compiled.isValid()) {
				throw new IllegalStateException("Pipeline is not valid (may contain invalid shaders?)");
			}
			pipeline = compiled;
			if (pass.pipeline() != compiled.pipeline()) {
				pass.setPipeline(compiled.pipeline());
				forgetBindings(); // slot indices depend on the pipeline
			}
			pipelineDirty = false;
			dirty = true;
		}
		if (!dirty && changedUniform == null) {
			return;
		}
		List<VulkanBindGroupLayout.Entry> entries = pipeline.entries();
		int count = entries.size();
		if (boundA.length < count) {
			boundA = new long[count];
			boundB = new long[count];
		}
		for (int i = 0; i < count; i++) {
			VulkanBindGroupLayout.Entry entry = entries.get(i);
			if (!dirty && !entry.name().equals(changedUniform)) {
				continue;
			}
			long a;
			long b;
			switch (entry.type()) {
				case UNIFORM_BUFFER -> {
					GpuBufferSlice slice = uniform(entry);
					a = MetalCommandEncoder.mtl(slice.buffer());
					b = slice.offset();
					if (boundA[i] != a || boundB[i] != b) {
						pass.setBuffer(i, a, b);
					}
				}
				case SAMPLED_IMAGE -> {
					Bound bound = textures.get(entry.name());
					if (bound == null) {
						throw new IllegalStateException("Missing sampler " + entry.name());
					}
					a = bound.view().mtl;
					b = bound.sampler().mtl;
					if (boundA[i] != a || boundB[i] != b) {
						pass.setTexture(i, a, b);
					}
				}
				default -> {
					GpuBufferSlice slice = uniform(entry);
					var format = entry.texelBufferFormat();
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
		changedUniform = null;
	}

	private GpuBufferSlice uniform(VulkanBindGroupLayout.Entry entry) {
		GpuBufferSlice slice = uniforms.get(entry.name());
		if (slice == null) {
			throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
		}
		return slice;
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
	public <T> void drawMultipleIndexed(
		Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument
	) {
		for (RenderPass.Draw<T> draw : draws) {
			var uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, uniformUploader);
			}
			setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
			pass.setVertexBuffer(draw.slot(), MetalCommandEncoder.mtl(draw.vertexBuffer()), 0L);
			drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
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
