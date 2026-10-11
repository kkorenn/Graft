package dev.graft.v1_21;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dev.graft.metal.MetalRenderPass;
import dev.graft.metal.Mtl;
import dev.graft.metal.ShaderTranslator;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Blaze3D (1.21.11) render pass on a Metal render pass. Resources are bound by name at draw time. */
public class MetalRenderPassBackend implements RenderPass {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Set<String> WARNED = new HashSet<>();

	private final MetalGpuDevice device;
	private final MetalRenderPass pass;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, MetalTexture.View> textures = new HashMap<>();
	static @Nullable DrawOverride drawOverride;
	private boolean overridden;
	private @Nullable RenderPipeline renderPipeline;
	private @Nullable MetalCompiledPipeline pipeline;
	private boolean pipelineDirty;
	private boolean dirty;
	/** While not dirty: the one uniform name set to a new value since the last bind (only its slots need rebinding). */
	private @Nullable String changedUniform;
	private int generation;
	private boolean closed;
	/**
	 * What each Metal slot of the current pipeline has bound: buffer+offset, texture+sampler or texel texture. MISSING
	 * marks a slot already warned about. Cleared whenever the pass's bindings can't be trusted (new pipeline/encoder).
	 */
	private long[] boundA = new long[16];
	private long[] boundB = new long[16];
	private static final long MISSING = -1L;

	MetalRenderPassBackend(MetalGpuDevice device, MetalRenderPass pass) {
		this.device = device;
		this.pass = pass;
	}

	/** API: the underlying Metal render pass. */
	public MetalRenderPass metal() {
		return pass;
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
		if (this.renderPipeline != renderPipeline) {
			this.renderPipeline = renderPipeline;
			this.pipelineDirty = true;
		}
	}

	/** API: the vanilla pipeline set on this pass. */
	public @Nullable RenderPipeline renderPipeline() {
		return renderPipeline;
	}

	/** API: vanilla uniform buffers set on this pass, by name. */
	public Map<String, GpuBufferSlice> uniforms() {
		return java.util.Collections.unmodifiableMap(uniforms);
	}

	/** API: texture bound under a sampler name, or null. */
	public @Nullable GpuTextureView textureView(String name) {
		return textures.get(name);
	}

	/** API: MTLSamplerState for the texture bound under a sampler name (its own sampling state), or 0. */
	public long mtlSampler(String name) {
		MetalTexture.View view = textures.get(name);
		return view != null ? device.sampler(view.metalTexture()) : 0L;
	}

	@Override
	public void bindSampler(String name, @Nullable GpuTextureView textureView) {
		Object old = textureView != null ? textures.put(name, (MetalTexture.View)textureView) : textures.remove(name);
		if (old != textureView) {
			dirty = true;
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

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		pass.setScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		pass.disableScissor();
	}

	@Override
	public void setVertexBuffer(int slot, GpuBuffer vertexBuffer) {
		pass.setVertexBuffer(slot, ((MetalBuffer)vertexBuffer).mtl(), 0L);
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
		pass.setIndexBuffer(((MetalBuffer)indexBuffer).mtl(), indexType == VertexFormat.IndexType.SHORT ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
	}

	/** False when the pipeline is invalid (e.g. shaders not loaded yet during startup): GL skips such draws. */
	private boolean bind() {
		if (pass.generation() != generation) {
			// Encoder was reopened (resume after a mid-pass copy): bindings must be redone.
			generation = pass.generation();
			pipelineDirty = true;
			dirty = true;
		}
		if (drawOverride != null && drawOverride.beforeDraw(this)) {
			overridden = true;
			return true;
		}
		if (overridden) {
			overridden = false;
			pipelineDirty = true;
			dirty = true;
		}
		if (pipelineDirty) {
			if (renderPipeline == null) {
				throw new IllegalStateException("Pipeline is missing");
			}
			MetalCompiledPipeline compiled = device.getOrCompilePipeline(renderPipeline);
			if (!compiled.isValid()) {
				return false;
			}
			pipeline = compiled;
			pass.setPipeline(compiled.pipeline());
			pipelineDirty = false;
			dirty = true;
			int n = compiled.names().size();
			if (boundA.length < n) {
				boundA = new long[n];
				boundB = new long[n];
			} else {
				java.util.Arrays.fill(boundA, 0L);
				java.util.Arrays.fill(boundB, 0L);
			}
		}
		if (!dirty && changedUniform == null && textures.isEmpty()) {
			return true;
		}
		List<String> names = pipeline.names();
		List<ShaderTranslator.Kind> kinds = pipeline.kinds();
		for (int i = 0; i < names.size(); i++) {
			ShaderTranslator.Kind kind = kinds.get(i);
			String name = names.get(i);
			if (!dirty && kind != ShaderTranslator.Kind.SAMPLED_IMAGE && !name.equals(changedUniform)) {
				continue;
			}
			switch (kind) {
				case UNIFORM_BUFFER -> {
					GpuBufferSlice slice = uniforms.get(name);
					if (slice != null) {
						long buffer = ((MetalBuffer)slice.buffer()).mtl();
						long offset = slice.offset();
						if (boundA[i] != buffer || boundB[i] != offset) {
							pass.setBuffer(i, buffer, offset);
							boundA[i] = buffer;
							boundB[i] = offset;
						}
					} else {
						warnMissing(i, name);
					}
				}
				case SAMPLED_IMAGE -> {
					MetalTexture.View view = textures.get(name);
					if (view != null) {
						// Sampling state lives on the texture and can change between draws: check it every draw.
						bindTexture(i, view.mtl, device.sampler(view.metalTexture()));
					} else {
						warnMissing(i, name);
					}
				}
				case TEXEL_BUFFER -> {
					GpuBufferSlice slice = uniforms.get(name);
					TextureFormat format = pipeline.texelFormats()[i];
					if (slice != null && format != null) {
						long texture = device.commands.texelBufferTexture(
							((MetalBuffer)slice.buffer()).mtl(), slice.offset(), slice.length(), MetalTexture.pixelFormat(format), format.pixelSize()
						);
						bindTexture(i, texture, 0L);
					} else {
						warnMissing(i, name);
					}
				}
				default -> throw new IllegalStateException("Unexpected binding kind " + kind);
			}
		}
		dirty = false;
		changedUniform = null;
		return true;
	}

	private void bindTexture(int slot, long texture, long sampler) {
		if (boundA[slot] != texture || boundB[slot] != sampler) {
			pass.setTexture(slot, texture, sampler);
			boundA[slot] = texture;
			boundB[slot] = sampler;
		} else {
			device.commands.flushClear(texture); // as setTexture would
		}
	}

	private void warnMissing(int slot, String name) {
		if (boundA[slot] == MISSING) {
			return;
		}
		boundA[slot] = MISSING;
		// GL leaves an unset uniform unbound; Metal reads whatever is in the slot. Say so once per name.
		if (WARNED.add(renderPipeline.getLocation() + "/" + name)) {
			LOGGER.warn("Missing uniform or sampler {} for pipeline {}", name, renderPipeline.getLocation());
		}
	}

	@Override
	public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
		if (bind()) {
			pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, 0);
		}
	}

	@Override
	public <T> void drawMultipleIndexed(
		Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable VertexFormat.IndexType defaultIndexType,
		Collection<String> dynamicUniforms, T uniformArgument
	) {
		for (RenderPass.Draw<T> draw : draws) {
			var uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, this::setUniform);
			}
			GpuBuffer indexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();
			VertexFormat.IndexType indexType = draw.indexType() == null ? defaultIndexType : draw.indexType();
			if (indexBuffer != null && indexType != null) {
				setIndexBuffer(indexBuffer, indexType);
			}
			setVertexBuffer(draw.slot(), draw.vertexBuffer());
			drawIndexed(0, draw.firstIndex(), draw.indexCount(), 1);
		}
	}

	@Override
	public void draw(int firstVertex, int vertexCount) {
		if (bind()) {
			pass.draw(vertexCount, 1, firstVertex, 0);
		}
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			device.commands.endRenderPass();
		}
	}
}
