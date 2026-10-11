package dev.graft.v26_3;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.Mtl;
import dev.graft.metal.ShaderTranslator;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class MetalGpuDevice implements GpuDeviceBackend {
	/** DeviceInfo backend name; F3 shows it followed by the driver info: "Apple Metal (Graft)". */
	public static final String BACKEND_NAME = "Apple Metal";

	private static final Logger LOGGER = LogUtils.getLogger();
	final MetalDevice metal;
	final MetalCommands commands;
	private final MetalCommandEncoder encoder;
	private final DeviceInfo info;
	private static MetalGpuDevice current;

	/** API: the active Metal device, for mods that render with Metal directly. */
	public static MetalGpuDevice current() {
		return current;
	}

	public MetalDevice metal() {
		return metal;
	}

	public MetalCommands commands() {
		return commands;
	}

	public MetalGpuDevice() {
		current = this;
		this.metal = new MetalDevice();
		this.commands = new MetalCommands(metal);
		this.encoder = new MetalCommandEncoder(this);
		this.info = new DeviceInfo(
			metal.name,
			"Apple",
			"(Graft)",
			true,
			BACKEND_NAME,
			1.0F,
			new DeviceLimits(16, 256, 16384, metal.maxBufferLength, Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
			new DeviceFeatures(true, true, true, true, true, true, true, true),
			Set.of(),
			// Same hint Mojang's Vulkan backend sets on Apple Silicon, where MoltenVK runs on this very Metal driver.
			new HintsAndWorkarounds(false, false, metal.unifiedMemory, false),
			metal.unifiedMemory ? DeviceType.INTEGRATED : DeviceType.DISCRETE
		);
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
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
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new MetalTexture(metal, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
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

	/** Runs on the frontend's compile executor: SPIR-V -> MSL -> MTLLibrary. MTLDevice is thread-safe. */
	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
		try {
			List<ShaderTranslator.Kind> kinds = info.uniforms().stream().map(u -> switch (u.type()) {
				case UNIFORM_BUFFER -> ShaderTranslator.Kind.UNIFORM_BUFFER;
				case COMBINED_IMAGE_SAMPLER -> ShaderTranslator.Kind.SAMPLED_IMAGE;
				case TEXEL_BUFFER -> ShaderTranslator.Kind.TEXEL_BUFFER;
			}).toList();
			String vertexMsl = null;
			String fragmentMsl = null;
			for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
				boolean vertex = shader.module().type() == ShaderType.VERTEX;
				String msl = ShaderTranslator.toMsl(shader.name(), shader.module().spv().duplicate(), vertex, kinds);
				if (vertex) {
					vertexMsl = msl;
				} else {
					fragmentMsl = msl;
				}
			}
			if (vertexMsl == null || fragmentMsl == null) {
				throw new IllegalStateException("pipeline needs a vertex and a fragment shader");
			}
			List<MetalPipeline.VertexBuffer> buffers = info.vertexBuffers().stream()
				.map(b -> new MetalPipeline.VertexBuffer(b.bufferSlot(), b.stride(), b.stepRate()))
				.toList();
			List<MetalPipeline.Attribute> attributes = info.attribBindings().stream()
				.map(a -> new MetalPipeline.Attribute(a.bufferSlot(), a.location(), a.offset(), Mtl.vertexFormat(a.format().name())))
				.toList();
			MetalPipeline pipeline = new MetalPipeline(
				metal, info.name(), vertexMsl, fragmentMsl, kinds, buffers, attributes, colorTargets(info.colorTargetStates()), raster(info)
			);
			MetalRenderPipeline result = new MetalRenderPipeline(pipeline, info.uniforms());
			return () -> result;
		} catch (RuntimeException e) {
			LOGGER.error("Couldn't compile pipeline {}: {}", info.name(), e.getMessage());
			return BackendRenderPipeline.Pending.NULL;
		}
	}

	private static MetalPipeline.ColorTarget[] colorTargets(List<@Nullable ColorTargetState> states) {
		MetalPipeline.ColorTarget[] targets = new MetalPipeline.ColorTarget[states.size()];
		for (int i = 0; i < states.size(); i++) {
			ColorTargetState s = states.get(i);
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

	private static MetalPipeline.Raster raster(BackendRenderPipeline.CreateInfo info) {
		DepthStencilState depth = info.depthStencilState();
		PrimitiveTopology topology = info.primitiveTopology();
		return new MetalPipeline.Raster(
			Mtl.primitive(topology.name()),
			topology == PrimitiveTopology.TRIANGLE_FAN,
			info.cull(),
			info.polygonMode() == PolygonMode.WIREFRAME,
			depth != null ? Mtl.compare(depth.depthTest().name()) : -1,
			depth != null && depth.writeDepth(),
			depth != null ? depth.depthBiasConstant() : 0.0F,
			depth != null ? depth.depthBiasScaleFactor() : 0.0F
		);
	}

	@Override
	public void close() {
		encoder.close();
		metal.close();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalQueryPool(size);
	}

	/** Query values are already host nanoTime, so there is nothing to calibrate. */
	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return info;
	}
}
