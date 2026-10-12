package dev.graft.v1_21_5;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import dev.graft.metal.GlShaders;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalConfig;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.MetalSurface;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Minecraft 1.21.5's GpuDevice on Metal (replaces GlDevice, see RenderSystemMixin). */
public class MetalGpuDevice implements GpuDevice {
	/** Backend name; F3 shows it followed by the version: "Apple Metal (Graft)". */
	public static final String BACKEND_NAME = "Apple Metal";
	/** Stay on OpenGL: -Dgraft.disable=true, or Sodium installed (its renderer calls OpenGL directly). */
	public static final boolean DISABLED = disabled();

	private static boolean disabled() {
		dev.graft.metal.MetalConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("graft.properties"));
		if (Boolean.getBoolean("graft.disable") || Boolean.getBoolean("graft.disabled")) {
			return true;
		}
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
			LogUtils.getLogger().warn("Graft: Sodium renders with OpenGL directly and isn't supported on Metal for 1.21.5; staying on OpenGL.");
			return true;
		}
		return false;
	}

	private static final Logger LOGGER = LogUtils.getLogger();
	private static MetalGpuDevice current;
	/** Set from Window.updateVsync (glfwSwapInterval is GL-only). */
	public static boolean vsync = true;

	final MetalDevice metal;
	final MetalCommands commands;
	private final MetalCommandEncoder encoder;
	private final BiFunction<ResourceLocation, ShaderType, String> defaultShaderSource;
	private final Map<Integer, Long> samplers = new HashMap<>();
	private final Map<RenderPipeline, MetalCompiledPipeline> pipelines = new IdentityHashMap<>();
	private final long window;
	private MetalSurface surface;
	private boolean drawableReady;

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

	public MetalGpuDevice(long window, BiFunction<ResourceLocation, ShaderType, String> defaultShaderSource) {
		this.window = window;
		this.defaultShaderSource = defaultShaderSource;
		this.metal = new MetalDevice();
		this.commands = new MetalCommands(metal);
		this.encoder = new MetalCommandEncoder(this);
		current = this;
	}

	// ---- presentation --------------------------------------------------------------------------------------------

	/** presentTexture: draws the frame into the window's next drawable (presented by {@link #swap}). */
	void blitToSurface(MetalTexture texture) {
		if (surface == null) {
			long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(window);
			surface = MetalSurface.forNSView(metal, ObjC.send(nsWindow, ObjC.sel("contentView")), MetalSurface.backingScale(nsWindow));
		}
		surface.configure(texture.getWidth(0), texture.getHeight(0), vsync);
		if (surface.acquire()) {
			surface.blit(commands, texture.mtl, texture.getWidth(0), texture.getHeight(0));
			drawableReady = true;
		}
	}

	/** GpuFence support (GpuFenceMixin). */
	public long fenceIndex() {
		return encoder.fenceIndex();
	}

	public boolean awaitFence(long index, long timeoutNs) {
		return encoder.awaitFence(index, timeoutNs);
	}

	/** Replaces glfwSwapBuffers: submits the frame (with its present) to the GPU. */
	public void swap() {
		if (drawableReady) {
			surface.present();
			drawableReady = false;
		}
		encoder.flush();
	}

	// ---- resources -----------------------------------------------------------------------------------------------

	@Override
	public CommandEncoder createCommandEncoder() {
		return encoder;
	}

	/** Metal sampler state matching the texture's current sampling state (cached per distinct state). */
	public long sampler(MetalTexture texture) {
		return samplers.computeIfAbsent(texture.samplerKey(), k -> texture.newSampler(metal));
	}

	@Override
	public GpuTexture createTexture(@Nullable Supplier<String> label, TextureFormat format, int width, int height, int mipLevels) {
		return new MetalTexture(metal, label != null ? label.get() : "", format, width, height, mipLevels);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, TextureFormat format, int width, int height, int mipLevels) {
		return new MetalTexture(metal, label != null ? label : "", format, width, height, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, BufferType type, BufferUsage usage, int size) {
		return new MetalBuffer(metal, label != null ? label.get() : null, type, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, BufferType type, BufferUsage usage, ByteBuffer data) {
		MetalBuffer buffer = new MetalBuffer(metal, label != null ? label.get() : null, type, usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), MetalDevice.contents(buffer.mtl()), data.remaining());
		return buffer;
	}

	// ---- info ----------------------------------------------------------------------------------------------------

	@Override
	public String getImplementationInformation() {
		return "Metal (Graft) on " + metal.name;
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
	public String getVendor() {
		return "Apple";
	}

	@Override
	public String getBackendName() {
		return BACKEND_NAME;
	}

	@Override
	public String getVersion() {
		return "(Graft)";
	}

	@Override
	public String getRenderer() {
		return metal.name;
	}

	@Override
	public int getMaxTextureSize() {
		return 16384;
	}

	@Override
	public List<String> getEnabledExtensions() {
		return List.of();
	}

	@Override
	public void close() {
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		clearPipelineCache();
		if (surface != null) {
			surface.close();
		}
		commands.close();
		metal.close();
	}

	// ---- pipelines -----------------------------------------------------------------------------------------------

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSource) {
		return cached(pipeline, shaderSource == null ? defaultShaderSource : shaderSource);
	}

	MetalCompiledPipeline getOrCompilePipeline(RenderPipeline pipeline) {
		return cached(pipeline, defaultShaderSource);
	}

	/** Failures are not cached: before resources load, shader sources are missing (GL retries too). */
	private MetalCompiledPipeline cached(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> source) {
		MetalCompiledPipeline compiled = pipelines.get(pipeline);
		if (compiled == null) {
			compiled = compile(pipeline, source);
			if (compiled.isValid()) {
				pipelines.put(pipeline, compiled);
			}
		}
		return compiled;
	}

	@Override
	public void clearPipelineCache() {
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		pipelines.values().forEach(MetalCompiledPipeline::close);
		pipelines.clear();
		samplers.values().forEach(dev.graft.metal.ObjC::release);
		samplers.clear();
	}

	private MetalCompiledPipeline compile(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> source) {
		String vsText = source.apply(pipeline.getVertexShader(), ShaderType.VERTEX);
		String fsText = source.apply(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
		if (vsText == null || fsText == null) {
			LOGGER.error("Couldn't find shader sources for pipeline {}", pipeline.getLocation());
			return MetalCompiledPipeline.INVALID;
		}
		try {
			VertexFormat format = pipeline.getVertexFormat();
			GlShaders.Program program = GlShaders.translate(
				pipeline.getLocation().toString(),
				GlslPreprocessor.injectDefines(vsText, pipeline.getShaderDefines()),
				GlslPreprocessor.injectDefines(fsText, pipeline.getShaderDefines()),
				format.getElementAttributeNames()
			);
			List<MetalPipeline.VertexBuffer> buffers = new ArrayList<>();
			List<MetalPipeline.Attribute> attributes = new ArrayList<>();
			if (!format.getElements().isEmpty()) {
				buffers.add(new MetalPipeline.VertexBuffer(0, format.getVertexSize(), 0));
				List<VertexFormatElement> elements = format.getElements();
				for (int i = 0; i < elements.size(); i++) {
					VertexFormatElement e = elements.get(i);
					attributes.add(new MetalPipeline.Attribute(0, i, format.getOffset(e), Mtl.vertexFormat(vertexFormatName(e))));
				}
			}
			MetalPipeline metalPipeline = new MetalPipeline(
				metal, pipeline.getLocation().toString(), program.vertexMsl(), program.fragmentMsl(), program.kinds(), buffers, attributes,
				new MetalPipeline.ColorTarget[]{colorTarget(pipeline)}, raster(pipeline)
			);
			return new MetalCompiledPipeline(metalPipeline, program.names(), program.kinds(), program.uniformSlot(), program.uniformSize(), program.uniforms());
		} catch (RuntimeException e) {
			LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return MetalCompiledPipeline.INVALID;
		}
	}

	/** GL attribute setup (VertexArrayCache): floats as is, normals/colors normalized, other integers stay integers. */
	public static String vertexFormatName(VertexFormatElement e) {
		String channels = switch (e.count()) {
			case 1 -> "R";
			case 2 -> "RG";
			case 3 -> "RGB";
			default -> "RGBA";
		};
		boolean signed = switch (e.type()) {
			case BYTE, SHORT, INT -> true;
			default -> false;
		};
		String bits = switch (e.type()) {
			case UBYTE, BYTE -> "8";
			case USHORT, SHORT -> "16";
			default -> "32";
		};
		if (e.type() == VertexFormatElement.Type.FLOAT) {
			return channels + "32_FLOAT";
		}
		boolean normalized = e.usage() == VertexFormatElement.Usage.NORMAL || e.usage() == VertexFormatElement.Usage.COLOR;
		return channels + bits + "_" + (normalized ? (signed ? "SNORM" : "UNORM") : (signed ? "SINT" : "UINT"));
	}

	public static MetalPipeline.ColorTarget colorTarget(RenderPipeline pipeline) {
		long mask = (pipeline.isWriteColor() ? 14L : 0L) | (pipeline.isWriteAlpha() ? 1L : 0L);
		// ponytail: LogicOp.OR_REVERSE (GL color logic) has no Metal equivalent and is ignored.
		return pipeline.getBlendFunction().map(b -> blended(b, mask)).orElseGet(() -> MetalPipeline.ColorTarget.opaque(0, mask));
	}

	private static MetalPipeline.ColorTarget blended(BlendFunction b, long mask) {
		return new MetalPipeline.ColorTarget(
			0, mask,
			Mtl.blendFactor(b.sourceColor().name()), Mtl.blendFactor(b.destColor().name()), Mtl.blendOp("ADD"),
			Mtl.blendFactor(b.sourceAlpha().name()), Mtl.blendFactor(b.destAlpha().name()), Mtl.blendOp("ADD")
		);
	}

	public static MetalPipeline.Raster raster(RenderPipeline pipeline) {
		VertexFormat.Mode mode = pipeline.getVertexFormatMode();
		long compare = switch (pipeline.getDepthTestFunction()) {
			case NO_DEPTH_TEST -> -1;
			case EQUAL_DEPTH_TEST -> 2;
			case LEQUAL_DEPTH_TEST -> 3;
			case LESS_DEPTH_TEST -> 1;
			case GREATER_DEPTH_TEST -> 4;
		};
		return new MetalPipeline.Raster(
			Mtl.primitive(mode.name()),
			mode == VertexFormat.Mode.TRIANGLE_FAN,
			pipeline.isCull(),
			pipeline.getPolygonMode() == PolygonMode.WIREFRAME,
			compare,
			compare >= 0 && pipeline.isWriteDepth(),
			pipeline.getDepthBiasConstant(),
			pipeline.getDepthBiasScaleFactor()
		);
	}
}
