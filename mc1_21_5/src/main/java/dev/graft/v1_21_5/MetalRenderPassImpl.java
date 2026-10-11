package dev.graft.v1_21_5;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Vector3f;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dev.graft.metal.MetalRenderPass;
import dev.graft.metal.ShaderTranslator;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.21.5 render pass on a Metal render pass. Uniforms are set by name (floats, ints, matrices) and written into the
 * pipeline's loose-uniform block at draw time; samplers are bound by name.
 */
public class MetalRenderPassImpl implements RenderPass {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Set<String> WARNED = new HashSet<>();
	private final float[] m16 = new float[16];

	private final MetalGpuDevice device;
	private final MetalRenderPass pass;
	private final Map<String, Object> uniforms = new HashMap<>();
	private final Map<String, MetalTexture> samplers = new HashMap<>();
	private @Nullable RenderPipeline renderPipeline;
	private @Nullable MetalCompiledPipeline pipeline;
	private @Nullable VertexFormat.IndexType indexType;
	private boolean pipelineDirty;
	private int generation;
	private boolean closed;

	MetalRenderPassImpl(MetalGpuDevice device, MetalRenderPass pass) {
		this.device = device;
		this.pass = pass;
	}

	/** API: the underlying Metal render pass. */
	public MetalRenderPass metal() {
		return pass;
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		if (this.renderPipeline != renderPipeline) {
			this.renderPipeline = renderPipeline;
			this.pipelineDirty = true;
		}
	}

	@Override
	public void bindSampler(String name, @Nullable GpuTexture texture) {
		if (texture != null) {
			samplers.put(name, (MetalTexture)texture);
		} else {
			samplers.remove(name);
		}
	}

	@Override
	public void setUniform(String name, int... values) {
		uniforms.put(name, values.clone());
	}

	@Override
	public void setUniform(String name, float... values) {
		uniforms.put(name, values.clone());
	}

	@Override
	public void setUniform(String name, Matrix4f value) {
		uniforms.put(name, value.get(new float[16]));
	}

	@Override
	public void enableScissor(ScissorState state) {
		if (state.isEnabled()) {
			enableScissor(state.getX(), state.getY(), state.getWidth(), state.getHeight());
		} else {
			disableScissor();
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
		pass.setVertexBuffer(slot, ((MetalBuffer)vertexBuffer).mtl, 0L);
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
		this.indexType = indexType;
		pass.setIndexBuffer(((MetalBuffer)indexBuffer).mtl, indexType == VertexFormat.IndexType.SHORT ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
	}

	/** False when the pipeline is invalid (e.g. shaders not loaded yet): GL skips such draws. */
	private boolean bind() {
		if (pass.generation() != generation) {
			generation = pass.generation();
			pipelineDirty = true;
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
			pass.setPipeline(compiled.pipeline);
			pipelineDirty = false;
		}
		MetalCompiledPipeline p = pipeline;
		setDefaultUniforms(p);
		for (Map.Entry<String, Object> u : uniforms.entrySet()) {
			Object v = u.getValue();
			p.set(u.getKey(), v instanceof float[] ? (float[])v : null, v instanceof int[] ? (int[])v : null);
		}
		List<String> names = p.names;
		List<ShaderTranslator.Kind> kinds = p.kinds;
		for (int i = 0; i < names.size(); i++) {
			if (i == p.uniformSlot) {
				p.block.clear();
				pass.setBytes(i, p.block);
				continue;
			}
			if (kinds.get(i) == ShaderTranslator.Kind.SAMPLED_IMAGE) {
				MetalTexture t = samplers.get(names.get(i));
				if (t == null && names.get(i).startsWith("Sampler")) { // GL: RenderSystem's shader textures (setDefaultUniforms)
					try {
						GpuTexture shaderTexture = RenderSystem.getShaderTexture(Integer.parseInt(names.get(i).substring(7)));
						t = shaderTexture != null ? (MetalTexture)shaderTexture : null;
					} catch (NumberFormatException | IndexOutOfBoundsException ignored) {
					}
				}
				if (t != null) {
					pass.setTexture(i, t.mtl, device.sampler(t));
				} else {
					warnMissing(names.get(i));
				}
			} else {
				warnMissing(names.get(i));
			}
		}
		return true;
	}

	/** GlProgram.setDefaultUniforms: global RenderSystem state, before the values this pass set. */
	private void setDefaultUniforms(MetalCompiledPipeline p) {
		p.set("ModelViewMat", RenderSystem.getModelViewMatrix().get(m16), null);
		p.set("ProjMat", RenderSystem.getProjectionMatrix().get(m16), null);
		p.set("TextureMat", RenderSystem.getTextureMatrix().get(m16), null);
		p.set("ColorModulator", RenderSystem.getShaderColor(), null);
		p.set("GlintAlpha", new float[]{RenderSystem.getShaderGlintAlpha()}, null);
		FogParameters fog = RenderSystem.getShaderFog();
		p.set("FogStart", new float[]{fog.start()}, null);
		p.set("FogEnd", new float[]{fog.end()}, null);
		p.set("FogColor", new float[]{fog.red(), fog.green(), fog.blue(), fog.alpha()}, null);
		p.set("FogShape", null, new int[]{fog.shape().getIndex()});
		p.set("GameTime", new float[]{RenderSystem.getShaderGameTime()}, null);
		Vector3f offset = RenderSystem.getModelOffset();
		p.set("ModelOffset", new float[]{offset.x, offset.y, offset.z}, null);
		Window window = Minecraft.getInstance().getWindow();
		p.set("ScreenSize", new float[]{window.getWidth(), window.getHeight()}, null);
		p.set("LineWidth", new float[]{RenderSystem.getShaderLineWidth()}, null);
		Vector3f[] lights = RenderSystem.getShaderLights();
		p.set("Light0_Direction", new float[]{lights[0].x, lights[0].y, lights[0].z}, null);
		p.set("Light1_Direction", new float[]{lights[1].x, lights[1].y, lights[1].z}, null);
	}

	private void warnMissing(String name) {
		if (WARNED.add(renderPipeline.getLocation() + "/" + name)) {
			LOGGER.warn("Missing uniform or sampler {} for pipeline {}", name, renderPipeline.getLocation());
		}
	}

	@Override
	public void drawIndexed(int firstIndex, int indexCount) {
		if (bind()) {
			pass.drawIndexed(indexCount, 1, firstIndex, 0, 0);
		}
	}

	@Override
	public void drawMultipleIndexed(Collection<RenderPass.Draw> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable VertexFormat.IndexType defaultIndexType) {
		for (RenderPass.Draw draw : draws) {
			if (draw.uniformUploaderConsumer() != null) {
				draw.uniformUploaderConsumer().accept(this::setUniform);
			}
			GpuBuffer indexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();
			VertexFormat.IndexType type = draw.indexType() == null ? defaultIndexType : draw.indexType();
			if (indexBuffer != null && type != null) {
				setIndexBuffer(indexBuffer, type);
			}
			setVertexBuffer(draw.slot(), draw.vertexBuffer());
			drawIndexed(draw.firstIndex(), draw.indexCount());
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
