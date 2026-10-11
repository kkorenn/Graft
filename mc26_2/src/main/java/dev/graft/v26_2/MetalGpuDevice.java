package dev.graft.v26_2;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import com.mojang.logging.LogUtils;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.Mtl;
import dev.graft.metal.ShaderTranslator;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

public class MetalGpuDevice implements GpuDeviceBackend {
	/** DeviceInfo backend name; F3 shows it followed by the driver info: "Apple Metal (Graft)". */
	public static final String BACKEND_NAME = "Apple Metal";

	private static final Logger LOGGER = LogUtils.getLogger();
	final MetalDevice metal;
	final MetalCommands commands;
	private final MetalCommandEncoder encoder;
	private final ShaderSource defaultShaderSource;
	// Mojang's GLSL front end (shaderc + SPIR-V reflection/linking). Pure shader tooling: no Vulkan API is touched.
	private final GlslCompiler glsl = new GlslCompiler();
	private final Map<RenderPipeline, MetalCompiledPipeline> pipelines = new IdentityHashMap<>();
	private final Map<ShaderKey, IntermediaryShaderModule> shaders = new HashMap<>();
	private final DeviceInfo info;

	private static MetalGpuDevice current;

	/** API: the active Metal device, for mods that render with Metal directly (e.g. Graft Shaders). */
	public static MetalGpuDevice current() {
		return current;
	}

	public MetalDevice metal() {
		return metal;
	}

	public MetalCommands commands() {
		return commands;
	}

	private record ShaderKey(Identifier id, ShaderType type, ShaderDefines defines) {
	}

	public MetalGpuDevice(ShaderSource defaultShaderSource) {
		this.defaultShaderSource = defaultShaderSource;
		this.metal = new MetalDevice();
		this.commands = new MetalCommands(metal);
		this.encoder = new MetalCommandEncoder(this);
		current = this;
		this.info = new DeviceInfo(
			metal.name,
			"Apple",
			"(Graft)",
			true,
			BACKEND_NAME,
			1.0F,
			new DeviceLimits(16, 256, 16384, metal.maxBufferLength, Integer.MAX_VALUE, 8),
			new DeviceFeatures(true, true, true, true, true, true, true),
			Set.of(),
			new HintsAndWorkarounds(false, false),
			metal.unifiedMemory ? DeviceType.INTEGRATED : DeviceType.DISCRETE
		);
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle) {
		return new MetalSurfaceBackend(this, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return new MetalSampler(metal, u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new MetalTexture(metal, usage, label != null ? label.get() : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new MetalTexture(metal, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture) {
		return createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		return new MetalTexture.View((MetalTexture)texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		return new MetalBuffer(metal, label != null ? label.get() : null, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		MetalBuffer buffer = new MetalBuffer(metal, label != null ? label.get() : null, usage, data.remaining());
		// Brand-new buffer, the GPU cannot be using it yet: write straight into shared memory.
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), MetalDevice.contents(buffer.mtl()), data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
		ShaderSource source = shaderSource == null ? defaultShaderSource : shaderSource;
		return pipelines.computeIfAbsent(pipeline, p -> compilePipeline(p, source));
	}

	MetalCompiledPipeline getOrCompilePipeline(RenderPipeline pipeline) {
		return pipelines.computeIfAbsent(pipeline, p -> compilePipeline(p, defaultShaderSource));
	}

	@Override
	public void clearPipelineCache() {
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		pipelines.values().forEach(MetalCompiledPipeline::close);
		pipelines.clear();
		shaders.values().forEach(IntermediaryShaderModule::close);
		shaders.clear();
	}

	@Override
	public void close() {
		encoder.close();
		clearPipelineCache();
		glsl.close();
		metal.close();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalQueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		return System.nanoTime();
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return info;
	}

	// ---- pipelines ------------------------------------------------------------------------------------------------

	private IntermediaryShaderModule shader(Identifier id, ShaderType type, ShaderDefines defines, ShaderSource source) {
		return shaders.computeIfAbsent(new ShaderKey(id, type, defines), key -> {
			String text = source.get(id, type);
			if (text == null) {
				LOGGER.error("Couldn't find source for {} shader ({})", type, id);
				return IntermediaryShaderModule.INVALID;
			}
			try {
				return glsl.createIntermediary(id.toDebugFileName(), GlslPreprocessor.injectDefines(text, defines), type);
			} catch (ShaderCompileException e) {
				LOGGER.error("Couldn't compile {} shader {}: {}", type, id, e.getMessage());
				return IntermediaryShaderModule.INVALID;
			}
		});
	}

	private MetalCompiledPipeline compilePipeline(RenderPipeline pipeline, ShaderSource source) {
		IntermediaryShaderModule vs = shader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), source);
		IntermediaryShaderModule fs = shader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), source);
		if (vs == IntermediaryShaderModule.INVALID || fs == IntermediaryShaderModule.INVALID) {
			LOGGER.error("Couldn't compile pipeline {}: invalid shader", pipeline.getLocation());
			return MetalCompiledPipeline.INVALID;
		}
		try {
			List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
			addToBindGroup(entries, vs, pipeline);
			addToBindGroup(entries, fs, pipeline);
			List<String> vertexOutputs = vs.outputs().stream().map(o -> o.name()).toList();
			List<String> vertexInputs = new ArrayList<>();
			List<MetalPipeline.VertexBuffer> buffers = new ArrayList<>();
			List<MetalPipeline.Attribute> attributes = new ArrayList<>();
			VertexFormat[] bindings = pipeline.getVertexFormatBindings();
			int location = 0;
			for (int slot = 0; slot < bindings.length; slot++) {
				VertexFormat format = bindings[slot];
				if (format == null) {
					continue;
				}
				buffers.add(new MetalPipeline.VertexBuffer(slot, format.getVertexSize(), format.getStepRate()));
				for (VertexFormatElement element : format.getElements()) {
					vertexInputs.add(element.name());
					attributes.add(new MetalPipeline.Attribute(slot, location++, element.offset(), Mtl.vertexFormat(element.format().name())));
				}
			}
			// Rebinding patches the shared cached SPIR-V in place, so translate right after (same as Mojang's Vulkan backend).
			vs.rebind(vertexInputs, entries);
			fs.rebind(vertexOutputs, entries);
			List<ShaderTranslator.Kind> kinds = entries.stream().map(e -> switch (e.type()) {
				case UNIFORM_BUFFER -> ShaderTranslator.Kind.UNIFORM_BUFFER;
				case SAMPLED_IMAGE -> ShaderTranslator.Kind.SAMPLED_IMAGE;
				case TEXEL_BUFFER -> ShaderTranslator.Kind.TEXEL_BUFFER;
			}).toList();
			String name = pipeline.getLocation().toString();
			String vertexMsl = ShaderTranslator.toMsl(vs.name(), vs.spirv().duplicate(), true, kinds);
			String fragmentMsl = ShaderTranslator.toMsl(fs.name(), fs.spirv().duplicate(), false, kinds);
			MetalPipeline metalPipeline = new MetalPipeline(
				metal, name, vertexMsl, fragmentMsl, kinds, buffers, attributes, colorTargets(pipeline.getColorTargetStates()), raster(pipeline)
			);
			return new MetalCompiledPipeline(metalPipeline, entries);
		} catch (ShaderCompileException | RuntimeException e) {
			LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return MetalCompiledPipeline.INVALID;
		}
	}

	private static MetalPipeline.ColorTarget[] colorTargets(ColorTargetState[] states) {
		MetalPipeline.ColorTarget[] targets = new MetalPipeline.ColorTarget[states.length];
		for (int i = 0; i < states.length; i++) {
			ColorTargetState s = states[i];
			if (s == null) {
				continue;
			}
			long format = Mtl.pixelFormat(s.format().name());
			long mask = Mtl.writeMask(s.writeMask());
			targets[i] = s.blendFunction().map(b -> new MetalPipeline.ColorTarget(
				format,
				mask,
				Mtl.blendFactor(b.color().sourceFactor().name()),
				Mtl.blendFactor(b.color().destFactor().name()),
				Mtl.blendOp(b.color().op().name()),
				Mtl.blendFactor(b.alpha().sourceFactor().name()),
				Mtl.blendFactor(b.alpha().destFactor().name()),
				Mtl.blendOp(b.alpha().op().name())
			)).orElseGet(() -> MetalPipeline.ColorTarget.opaque(format, mask));
		}
		return targets;
	}

	private static MetalPipeline.Raster raster(RenderPipeline pipeline) {
		DepthStencilState depth = pipeline.getDepthStencilState();
		PrimitiveTopology topology = pipeline.getPrimitiveTopology();
		return new MetalPipeline.Raster(
			Mtl.primitive(topology.name()),
			topology == PrimitiveTopology.TRIANGLE_FAN,
			pipeline.isCull(),
			pipeline.getPolygonMode() == PolygonMode.WIREFRAME,
			depth != null ? Mtl.compare(depth.depthTest().name()) : -1,
			depth != null && depth.writeDepth(),
			depth != null ? depth.depthBiasConstant() : 0.0F,
			depth != null ? depth.depthBiasScaleFactor() : 0.0F
		);
	}

	/** Same resource discovery as Mojang's GlslCompiler.addToBindGroup (which is private). */
	private static void addToBindGroup(List<VulkanBindGroupLayout.Entry> entries, IntermediaryShaderModule shader, RenderPipeline pipeline) throws ShaderCompileException {
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
		for (var buffer : shader.uniformBuffers()) {
			String name = buffer.name();
			if (uniforms.stream().noneMatch(d -> d.name().equals(name))) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
			}
			addEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
		}
		for (var sampler : shader.samplers()) {
			String name = sampler.name();
			var texelBuffer = uniforms.stream().filter(d -> d.name().equals(name)).findFirst();
			if (texelBuffer.isPresent()) {
				addEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER, name, texelBuffer.get().gpuFormat());
			} else if (samplers.contains(name)) {
				addEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
			} else {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
			}
		}
	}

	private static void addEntry(List<VulkanBindGroupLayout.Entry> entries, VulkanBindGroupLayout.VulkanBindGroupEntryType type, String name, @Nullable GpuFormat format) {
		if (entries.stream().noneMatch(e -> e.type() == type && e.name().equals(name))) {
			entries.add(new VulkanBindGroupLayout.Entry(type, name, format));
		}
	}
}
