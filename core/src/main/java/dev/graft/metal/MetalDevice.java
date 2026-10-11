package dev.graft.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;

import static dev.graft.metal.ObjC.*;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Owns the MTLDevice and command queue, and creates every Metal object. All handles are retained (+1). */
public final class MetalDevice implements AutoCloseable {
	private static final MethodHandle CREATE_DEVICE = fn(METAL, "MTLCreateSystemDefaultDevice", FunctionDescriptor.of(JAVA_LONG));
	private static final long S_NEW_BUFFER = sel("newBufferWithLength:options:");
	private static final long S_CONTENTS = sel("contents");

	private static final String INTERNAL_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct VOut { float4 pos [[position]]; };
		vertex VOut mc_fullscreen(uint vid [[vertex_id]], constant float& depth [[buffer(0)]]) {
			float2 uv = float2((vid << 1) & 2, vid & 2);
			VOut o;
			o.pos = float4(uv * 2.0 - 1.0, depth, 1.0);
			return o;
		}
		fragment float4 mc_present(VOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant uint& height [[buffer(0)]]) {
			uint2 p = uint2(in.pos.xy);
			return src.read(uint2(p.x, height - 1 - p.y));
		}
		fragment float4 mc_clear(VOut in [[stage_in]], constant float4& color [[buffer(0)]]) {
			return color;
		}
		""";

	public final long device;
	public final long queue;
	public final String name;
	public final boolean unifiedMemory;
	public final long maxBufferLength;
	private final long internalLibrary;
	private final Map<Long, Long> depthStates = new HashMap<>();
	private final Map<String, Long> internalPipelines = new HashMap<>();

	public MetalDevice() {
		long pool = poolPush();
		try {
			long dev = (long)CREATE_DEVICE.invokeExact();
			if (dev == 0L) {
				throw new IllegalStateException("No Metal device available");
			}
			this.device = dev;
			this.queue = send(dev, sel("newCommandQueue"));
			this.name = toJava(send(dev, sel("name")));
			this.unifiedMemory = sendBool(dev, sel("hasUnifiedMemory"));
			this.maxBufferLength = send(dev, sel("maxBufferLength"));
			this.internalLibrary = compileLibrary("graft-internal", INTERNAL_MSL);
		} catch (Throwable t) {
			throw rethrow(t);
		} finally {
			poolPop(pool);
		}
	}

	@Override
	public void close() {
		depthStates.values().forEach(ObjC::release);
		internalPipelines.values().forEach(ObjC::release);
		release(internalLibrary);
		release(queue);
		release(device);
	}

	// ---- buffers --------------------------------------------------------------------------------------------------

	/** Shared-storage buffer: CPU-visible, so mapping is just a pointer. */
	public long newBuffer(long size) {
		long buffer = send(device, S_NEW_BUFFER, Math.max(16L, size), Mtl.STORAGE_SHARED_OPTIONS);
		if (buffer == 0L) {
			throw new IllegalStateException("Metal failed to allocate a " + size + " byte buffer");
		}
		return buffer;
	}

	public static long contents(long buffer) {
		return send(buffer, S_CONTENTS);
	}

	public static void setLabel(long obj, String label) {
		if (label != null && !label.isEmpty()) {
			long pool = poolPush();
			try {
				send(obj, sel("setLabel:"), nsString(label));
			} finally {
				poolPop(pool);
			}
		}
	}

	// ---- textures -------------------------------------------------------------------------------------------------

	public long newTexture(long pixelFormat, boolean cube, int width, int height, int layers, int mipLevels) {
		long desc = alloc("MTLTextureDescriptor");
		try {
			send(desc, sel("setTextureType:"), cube ? Mtl.TEX_CUBE : layers > 1 ? Mtl.TEX_2D_ARRAY : Mtl.TEX_2D);
			send(desc, sel("setPixelFormat:"), pixelFormat);
			send(desc, sel("setWidth:"), width);
			send(desc, sel("setHeight:"), height);
			send(desc, sel("setDepth:"), 1L);
			send(desc, sel("setArrayLength:"), cube ? 1L : Math.max(1, layers));
			send(desc, sel("setMipmapLevelCount:"), Math.max(1, mipLevels));
			send(desc, sel("setStorageMode:"), Mtl.STORAGE_PRIVATE);
			// Render target usage is always needed: clears of non-attachment textures go through render passes.
			// Color textures are also shader-writable (GL image load/store binds any texture as an image).
			long usage = Mtl.USAGE_SHADER_READ | Mtl.USAGE_RENDER_TARGET | Mtl.USAGE_PIXEL_FORMAT_VIEW;
			if (!Mtl.hasDepth(pixelFormat) && !Mtl.hasStencil(pixelFormat)) {
				usage |= Mtl.USAGE_SHADER_WRITE;
			}
			send(desc, sel("setUsage:"), usage);
			long texture = send(device, sel("newTextureWithDescriptor:"), desc);
			if (texture == 0L) {
				throw new IllegalStateException("Metal failed to create " + width + "x" + height + " texture (format " + pixelFormat + ")");
			}
			return texture;
		} finally {
			release(desc);
		}
	}

	/** 3D texture (pack custom textures). Shader-readable and copy target; no mips. */
	public long newTexture3D(long pixelFormat, int width, int height, int depth) {
		long desc = alloc("MTLTextureDescriptor");
		try {
			send(desc, sel("setTextureType:"), 7L); // MTLTextureType3D
			send(desc, sel("setPixelFormat:"), pixelFormat);
			send(desc, sel("setWidth:"), width);
			send(desc, sel("setHeight:"), height);
			send(desc, sel("setDepth:"), depth);
			send(desc, sel("setStorageMode:"), Mtl.STORAGE_PRIVATE);
			send(desc, sel("setUsage:"), Mtl.USAGE_SHADER_READ | Mtl.USAGE_SHADER_WRITE);
			long texture = send(device, sel("newTextureWithDescriptor:"), desc);
			if (texture == 0L) {
				throw new IllegalStateException("Metal failed to create " + width + "x" + height + "x" + depth + " 3D texture");
			}
			return texture;
		} finally {
			release(desc);
		}
	}

	public static long newTextureView(long texture, long pixelFormat, boolean cube, int baseLevel, int levels) {
		return Calls.newTextureView(texture, pixelFormat, cube ? Mtl.TEX_CUBE : Mtl.TEX_2D, baseLevel, levels, 0, cube ? 6 : 1);
	}

	/** Texture view over a buffer for GLSL samplerBuffer / MSL texture_buffer. */
	public long newTexelBufferTexture(long buffer, long offset, long length, long pixelFormat, int texelSize) {
		long pool = poolPush();
		try {
			long width = length / texelSize;
			long desc = send(
				cls("MTLTextureDescriptor"), sel("textureBufferDescriptorWithPixelFormat:width:resourceOptions:usage:"), pixelFormat, width, 0L, Mtl.USAGE_SHADER_READ
			);
			return send(buffer, sel("newTextureWithDescriptor:offset:bytesPerRow:"), desc, offset, width * texelSize);
		} finally {
			poolPop(pool);
		}
	}

	// ---- samplers -------------------------------------------------------------------------------------------------

	public long newSampler(long addressU, long addressV, long minFilter, long magFilter, int maxAnisotropy, double maxLod) {
		// Same rule as Minecraft's Vulkan backend: tiny maxLod means "no mipmapping".
		return newSampler(addressU, addressV, minFilter, magFilter, maxLod > 0.25 ? 2L : 0L, maxAnisotropy, Math.max(0.25, maxLod), -1L);
	}

	/**
	 * mipFilter: MTLSamplerMipFilter (0 none, 1 nearest, 2 linear). compareFunction: MTLCompareFunction for depth
	 * comparison sampling (sampler2DShadow), or -1 for none.
	 */
	public long newSampler(long addressU, long addressV, long minFilter, long magFilter, long mipFilter, int maxAnisotropy, double maxLod, long compareFunction) {
		long desc = alloc("MTLSamplerDescriptor");
		try {
			send(desc, sel("setSAddressMode:"), addressU);
			send(desc, sel("setTAddressMode:"), addressV);
			send(desc, sel("setRAddressMode:"), addressU);
			send(desc, sel("setMinFilter:"), minFilter);
			send(desc, sel("setMagFilter:"), magFilter);
			send(desc, sel("setMipFilter:"), mipFilter);
			sendD(desc, sel("setLodMaxClamp:"), maxLod);
			send(desc, sel("setMaxAnisotropy:"), Math.max(1, maxAnisotropy));
			if (compareFunction >= 0) {
				send(desc, sel("setCompareFunction:"), compareFunction);
			}
			return send(device, sel("newSamplerStateWithDescriptor:"), desc);
		} finally {
			release(desc);
		}
	}

	// ---- shaders & pipelines ---------------------------------------------------------------------------------------

	public long compileLibrary(String name, String msl) {
		long pool = poolPush();
		try (Arena arena = Arena.ofConfined()) {
			long options = alloc("MTLCompileOptions");
			// GL/Vulkan shaders (vanilla, shader packs) expect IEEE inf/NaN behavior, e.g. max(NaN, 0.0) == 0.0 as a NaN
			// guard; Metal's default fast math assumes neither exists and drops such guards. Relaxed keeps the speed-ups
			// that are safe for inf/NaN.
			if (send(options, sel("respondsToSelector:"), sel("setMathMode:")) != 0L) {
				send(options, sel("setMathMode:"), Long.getLong("graft.mathMode", 2L)); // 2 = MTLMathModeFast (Metal default), 1 = relaxed
			} else {
				send(options, sel("setFastMathEnabled:"), 0L);
			}
			try {
				MemorySegment error = arena.allocate(JAVA_LONG);
				long library = send(device, sel("newLibraryWithSource:options:error:"), nsString(msl), options, error.address());
				if (library == 0L) {
					throw new IllegalStateException("Metal rejected translated shader " + name + ": " + errorString(error.get(JAVA_LONG, 0)) + "\n" + msl);
				}
				return library;
			} finally {
				release(options);
			}
		} finally {
			poolPop(pool);
		}
	}

	public static long function(long library, String name) {
		long pool = poolPush();
		try {
			long fn = send(library, sel("newFunctionWithName:"), nsString(name));
			if (fn == 0L) {
				throw new IllegalStateException("Shader function " + name + " not found");
			}
			return fn;
		} finally {
			poolPop(pool);
		}
	}

	/** Builds an MTLRenderPipelineState from a filled MTLRenderPipelineDescriptor. */
	public long newPipelineState(long descriptor, String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(JAVA_LONG);
			long state = send(device, sel("newRenderPipelineStateWithDescriptor:error:"), descriptor, error.address());
			if (state == 0L) {
				long pool = poolPush();
				try {
					throw new IllegalStateException("Metal failed to build pipeline " + name + ": " + errorString(error.get(JAVA_LONG, 0)));
				} finally {
					poolPop(pool);
				}
			}
			return state;
		}
	}

	/** MTLComputePipelineState for an MSL compute shader with entry point main0 (+1 retained). */
	public long newComputePipeline(String name, String msl) {
		long library = compileLibrary(name, msl);
		long fn = function(library, "main0");
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(JAVA_LONG);
			long state = send(device, sel("newComputePipelineStateWithFunction:error:"), fn, error.address());
			if (state == 0L) {
				long pool = poolPush();
				try {
					throw new IllegalStateException("Metal failed to build compute pipeline " + name + ": " + errorString(error.get(JAVA_LONG, 0)));
				} finally {
					poolPop(pool);
				}
			}
			return state;
		} finally {
			release(fn);
			release(library);
		}
	}

	/** Cached MTLDepthStencilState. compare is an MTLCompareFunction. */
	public long depthState(long compare, boolean write) {
		long key = compare << 1 | (write ? 1 : 0);
		return depthStates.computeIfAbsent(key, k -> {
			long desc = alloc("MTLDepthStencilDescriptor");
			try {
				send(desc, sel("setDepthCompareFunction:"), compare);
				send(desc, sel("setDepthWriteEnabled:"), write ? 1L : 0L);
				return send(device, sel("newDepthStencilStateWithDescriptor:"), desc);
			} finally {
				release(desc);
			}
		});
	}

	/** Internal fullscreen pipelines: "present" (blit with Y flip) and "clear". */
	long internalPipeline(String fragment, long colorFormat, long depthFormat) {
		String key = fragment + ":" + colorFormat + ":" + depthFormat;
		return internalPipelines.computeIfAbsent(key, k -> {
			long vs = function(internalLibrary, "mc_fullscreen");
			long fs = function(internalLibrary, fragment);
			long desc = alloc("MTLRenderPipelineDescriptor");
			try {
				send(desc, sel("setVertexFunction:"), vs);
				send(desc, sel("setFragmentFunction:"), fs);
				if (colorFormat != Mtl.PIXEL_INVALID) {
					long attachment = send(send(desc, sel("colorAttachments")), sel("objectAtIndexedSubscript:"), 0L);
					send(attachment, sel("setPixelFormat:"), colorFormat);
				}
				if (depthFormat != Mtl.PIXEL_INVALID) {
					send(desc, sel("setDepthAttachmentPixelFormat:"), depthFormat);
					if (Mtl.hasStencil(depthFormat)) {
						send(desc, sel("setStencilAttachmentPixelFormat:"), depthFormat);
					}
				}
				return newPipelineState(desc, "graft:" + key);
			} finally {
				release(desc);
				release(vs);
				release(fs);
			}
		});
	}
}
