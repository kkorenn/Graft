package dev.graft.metal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static dev.graft.metal.ObjC.*;

/**
 * The command stream. One MTLCommandBuffer per Minecraft submit; copies go through a lazily opened blit encoder, draws
 * through {@link MetalRenderPass}. At most two submits are in flight, like Minecraft's Vulkan backend.
 */
public final class MetalCommands implements AutoCloseable {
	public static final int MAX_IN_FLIGHT = 2;
	private static final long S_COMMAND_BUFFER = sel("commandBuffer");
	private static final long S_BLIT = sel("blitCommandEncoder");
	private static final long S_END = sel("endEncoding");
	private static final long S_COMMIT = sel("commit");
	private static final long S_STATUS = sel("status");
	private static final long S_WAIT = sel("waitUntilCompleted");
	private static final long S_COMPUTE_PIPELINE = sel("setComputePipelineState:");
	private static final long S_COMPUTE_BUFFER = sel("setBuffer:offset:atIndex:");
	private static final long S_COMPUTE_TEXTURE = sel("setTexture:atIndex:");
	private static final long S_COMPUTE_SAMPLER = sel("setSamplerState:atIndex:");
	private static final long S_COMPUTE_BYTES = sel("setBytes:length:atIndex:");
	private static final long S_PARENT_TEXTURE = sel("parentTexture");
	private static final long S_COMPUTE_ENCODER = sel("computeCommandEncoder");
	private static final long S_RENDER_ENCODER = sel("renderCommandEncoderWithDescriptor:");
	private static final long S_PASS_DESCRIPTOR = sel("renderPassDescriptor");
	private static final long S_COLOR_ATTACHMENTS = sel("colorAttachments");
	private static final long S_AT_INDEX = sel("objectAtIndexedSubscript:");
	private static final long S_SET_TEXTURE = sel("setTexture:");
	private static final long S_SET_LEVEL = sel("setLevel:");
	private static final long S_SET_LOAD = sel("setLoadAction:");
	private static final long S_SET_STORE = sel("setStoreAction:");

	private record Pending(long submitIndex, Runnable task) {
	}

	/** A CPU-writable slice of a shared buffer, valid until the current submit finishes on the GPU. */
	public record Scratch(long buffer, long offset, long address) {
	}

	private static final long SCRATCH_BLOCK = 1L << 20;
	private final ArrayDeque<Long> freeScratch = new ArrayDeque<>();
	private long scratchBlock;
	private long scratchAddress;
	private long scratchOffset = SCRATCH_BLOCK;

	private final MetalDevice device;
	private long commandBuffer;
	private long blit;
	private long compute;
	private MetalRenderPass renderPass;
	/** Index of the submit currently being recorded. Submit n is complete once inFlight[n % MAX] completes. */
	private long submitIndex = 1;
	private long completedIndex = 0;
	private final long[] inFlight = new long[MAX_IN_FLIGHT + 1];
	private final ArrayDeque<Pending> pending = new ArrayDeque<>();

	public MetalCommands(MetalDevice device) {
		this.device = device;
	}

	public MetalDevice device() {
		return device;
	}

	private double gpuSeconds;

	/** GPU execution time of retired command buffers since the last call (profiling). */
	public double takeGpuSeconds() {
		double s = gpuSeconds;
		gpuSeconds = 0;
		return s;
	}

	public long submitIndex() {
		return submitIndex;
	}

	long commandBuffer() {
		if (commandBuffer == 0L) {
			long pool = poolPush();
			try {
				commandBuffer = retain(send(device.queue, S_COMMAND_BUFFER));
			} finally {
				poolPop(pool);
			}
		}
		return commandBuffer;
	}

	public long blitEncoder() {
		flushClears(); // copies, readbacks and mip generation may touch any texture
		if (renderPass != null) {
			// GL-style code may copy mid-pass; pause the render encoder, it resumes on the next draw.
			renderPass.suspend();
		}
		endCompute();
		if (blit == 0L) {
			long pool = poolPush();
			try {
				blit = retain(send(commandBuffer(), S_BLIT));
				autoLabel(blit);
			} finally {
				poolPop(pool);
			}
		}
		return blit;
	}

	/** Compute encoder; like blits, may run mid render pass (the pass is suspended and resumes on its next draw). */
	public long computeEncoder() {
		if (renderPass != null) {
			renderPass.suspend();
		}
		endBlit();
		if (compute == 0L) {
			long pool = poolPush();
			try {
				compute = retain(send(commandBuffer(), S_COMPUTE_ENCODER));
				autoLabel(compute);
			} finally {
				poolPop(pool);
			}
		}
		return compute;
	}

	private static final boolean AUTO_LABELS = Boolean.getBoolean("graft.labels");

	/** -Dgraft.labels=true: names encoders after the first caller outside Graft, for GPU traces. */
	private static void autoLabel(long encoder) {
		if (!AUTO_LABELS) {
			return;
		}
		for (StackTraceElement e : new Throwable().getStackTrace()) {
			if (!e.getClassName().startsWith("dev.graft")) {
				String c = e.getClassName();
				send(encoder, sel("setLabel:"), nsString("@" + c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName()));
				return;
			}
		}
	}

	private void endCompute() {
		if (compute != 0L) {
			send(compute, S_END);
			release(compute);
			compute = 0L;
		}
	}

	public void setComputePipeline(long state) {
		send(computeEncoder(), S_COMPUTE_PIPELINE, state);
	}

	public void setComputeBuffer(int index, long buffer, long offset) {
		send(computeEncoder(), S_COMPUTE_BUFFER, buffer, offset, index);
	}

	public void setComputeTexture(int index, long texture, int samplerIndex, long sampler) {
		flushClear(texture);
		send(computeEncoder(), S_COMPUTE_TEXTURE, texture, index);
		if (sampler != 0L) {
			send(computeEncoder(), S_COMPUTE_SAMPLER, sampler, samplerIndex);
		}
	}

	public void setComputeSampler(int index, long sampler) {
		send(computeEncoder(), S_COMPUTE_SAMPLER, sampler, index);
	}

	public void setComputeBytes(int index, java.nio.ByteBuffer data) {
		long e = computeEncoder();
		send(e, S_COMPUTE_BYTES, stage(data), data.remaining(), index);
	}

	/** Native memory for setXxxBytes arguments: Metal copies them during the call, so one reused area is enough. */
	private java.lang.foreign.MemorySegment staging = java.lang.foreign.MemorySegment.NULL;

	private java.lang.foreign.MemorySegment staging(long size) {
		if (staging.byteSize() < size) {
			staging = java.lang.foreign.Arena.ofAuto().allocate(Math.max(size, 4096L), 16);
		}
		return staging;
	}

	/** Copies data (position to limit) into the staging area and returns its address. */
	long stage(java.nio.ByteBuffer data) {
		int n = data.remaining();
		java.lang.foreign.MemorySegment s = staging(n);
		java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofBuffer(data), 0L, s, 0L, n);
		return s.address();
	}

	long stage(byte[] data) {
		java.lang.foreign.MemorySegment s = staging(data.length);
		java.lang.foreign.MemorySegment.copy(data, 0, s, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, data.length);
		return s.address();
	}

	public void dispatch(int groupsX, int groupsY, int groupsZ, int threadsX, int threadsY, int threadsZ) {
		Calls.dispatch(computeEncoder(), groupsX, groupsY, groupsZ, threadsX, threadsY, threadsZ);
	}

	/** Indirect dispatch: buffer holds three uint32 group counts (same layout as GL's DispatchIndirectCommand). */
	public void dispatchIndirect(long buffer, long offset, int threadsX, int threadsY, int threadsZ) {
		Calls.dispatchIndirect(computeEncoder(), buffer, offset, threadsX, threadsY, threadsZ);
	}

	private void endBlit() {
		if (blit != 0L) {
			send(blit, S_END);
			release(blit);
			blit = 0L;
		}
	}

	/** Opens a render encoder for a filled MTLRenderPassDescriptor (ownership of the descriptor stays with the caller). */
	long openRenderEncoder(long passDescriptor) {
		endBlit();
		endCompute();
		long pool = poolPush();
		try {
			long encoder = retain(send(commandBuffer(), S_RENDER_ENCODER, passDescriptor));
			autoLabel(encoder);
			return encoder;
		} finally {
			poolPop(pool);
		}
	}

	public MetalRenderPass beginRenderPass(MetalRenderPass.Attachment[] colors, MetalRenderPass.Attachment depth, int areaX, int areaY, int areaW, int areaH, String label) {
		if (renderPass != null) {
			throw new IllegalStateException("A render pass is already open");
		}
		renderPass = new MetalRenderPass(this, colors, depth, areaX, areaY, areaW, areaH, label);
		return renderPass;
	}

	public void endRenderPass() {
		if (renderPass == null) {
			throw new IllegalStateException("No render pass is open");
		}
		renderPass.end();
		renderPass = null;
	}

	public boolean inRenderPass() {
		return renderPass != null;
	}

	/** Runs task once the GPU has finished everything recorded so far. */
	public void afterGpu(Runnable task) {
		pending.add(new Pending(submitIndex, task));
	}

	public Scratch scratch(long size) {
		size = (size + 15) & ~15L;
		if (size > SCRATCH_BLOCK) {
			long big = device.newBuffer(size);
			afterGpu(() -> release(big));
			return new Scratch(big, 0, MetalDevice.contents(big));
		}
		if (scratchOffset + size > SCRATCH_BLOCK) {
			long block = freeScratch.isEmpty() ? device.newBuffer(SCRATCH_BLOCK) : freeScratch.poll();
			afterGpu(() -> freeScratch.add(block));
			scratchBlock = block;
			scratchAddress = MetalDevice.contents(block);
			scratchOffset = 0;
		}
		Scratch s = new Scratch(scratchBlock, scratchOffset, scratchAddress + scratchOffset);
		scratchOffset += size;
		return s;
	}

	// ---- MetalFX upscaling ---------------------------------------------------------------------------------------

	private static final boolean METALFX = loadMetalFX();
	private long scaler;
	private long[] scalerKey;

	private static boolean loadMetalFX() {
		try {
			java.lang.foreign.SymbolLookup.libraryLookup("/System/Library/Frameworks/MetalFX.framework/MetalFX", java.lang.foreign.Arena.global());
			return cls("MTLFXSpatialScalerDescriptor") != 0L;
		} catch (Throwable t) {
			return false;
		}
	}

	/** True if the MetalFX spatial upscaler exists on this system and supports the device. */
	public boolean metalFxAvailable() {
		return METALFX && send(cls("MTLFXSpatialScalerDescriptor"), sel("supportsDevice:"), device.device) != 0L;
	}

	/**
	 * Upscales srcW x srcH of src into the whole of dst (dstW x dstH) with the MetalFX spatial scaler (perceptual color
	 * mode, for display-referred 8-bit color). Must be called outside render passes. Returns false if unavailable.
	 */
	public boolean upscale(long src, int srcW, int srcH, long srcFormat, long dst, int dstW, int dstH, long dstFormat) {
		if (!METALFX || renderPass != null) {
			return false;
		}
		flushClears();
		endBlit();
		endCompute();
		long[] key = {srcW, srcH, srcFormat, dstW, dstH, dstFormat};
		if (scaler == 0L || !java.util.Arrays.equals(key, scalerKey)) {
			if (scaler != 0L) {
				long old = scaler;
				afterGpu(() -> release(old));
			}
			long desc = alloc("MTLFXSpatialScalerDescriptor");
			try {
				send(desc, sel("setInputWidth:"), (long)srcW);
				send(desc, sel("setInputHeight:"), (long)srcH);
				send(desc, sel("setOutputWidth:"), (long)dstW);
				send(desc, sel("setOutputHeight:"), (long)dstH);
				send(desc, sel("setColorTextureFormat:"), srcFormat);
				send(desc, sel("setOutputTextureFormat:"), dstFormat);
				send(desc, sel("setColorProcessingMode:"), 0L); // perceptual
				scaler = send(desc, sel("newSpatialScalerWithDevice:"), device.device);
			} finally {
				release(desc);
			}
			scalerKey = key;
			if (scaler == 0L) {
				return false;
			}
		}
		send(scaler, sel("setColorTexture:"), src);
		send(scaler, sel("setOutputTexture:"), dst);
		send(scaler, sel("setInputContentWidth:"), (long)srcW);
		send(scaler, sel("setInputContentHeight:"), (long)srcH);
		send(scaler, sel("encodeToCommandBuffer:"), commandBuffer());
		return true;
	}

	// ---- texel buffer views --------------------------------------------------------------------------------------

	private record TexelKey(long buffer, long offset, long length, long format) {
	}

	private final java.util.HashMap<TexelKey, Long> texelViews = new java.util.HashMap<>();

	/**
	 * Texel-buffer texture view of a buffer range, cached (owned by the cache: do not release). A view retains its
	 * buffer, so a cached key can't come to mean a different buffer. Bounded: past 512 views the cache is released
	 * once the GPU is done with them.
	 */
	public long texelBufferTexture(long buffer, long offset, long length, long pixelFormat, int texelSize) {
		TexelKey key = new TexelKey(buffer, offset, length, pixelFormat);
		Long cached = texelViews.get(key);
		if (cached != null) {
			return cached;
		}
		if (texelViews.size() >= 512) {
			long[] old = texelViews.values().stream().mapToLong(Long::longValue).toArray();
			texelViews.clear();
			afterGpu(() -> {
				for (long t : old) {
					release(t);
				}
			});
		}
		long view = device.newTexelBufferTexture(buffer, offset, length, pixelFormat, texelSize);
		texelViews.put(key, view);
		return view;
	}

	// ---- deferred clears ------------------------------------------------------------------------------------------

	/** Pending level-0 color clears by MTLTexture (insertion order). */
	private final java.util.LinkedHashMap<Long, float[]> deferredClears = new java.util.LinkedHashMap<>();

	/**
	 * Level-0 color clear that becomes the load action of the next render pass attaching the texture (no separate
	 * full-texture write), or runs for real before any other use (sampling, compute, blits, submit). Clearing a texture
	 * the open pass renders into happens immediately.
	 */
	private static final boolean IMMEDIATE_CLEARS = Boolean.getBoolean("graft.immediateClears");

	public void deferClear(long texture, float[] color) {
		if (IMMEDIATE_CLEARS || renderPass != null && renderPass.attaches(texture)) {
			clear(texture, 1, color, 0L, 0L, 0, 0.0);
			return;
		}
		deferredClears.put(texture, color.clone());
	}

	/** Removes and returns the pending clear of texture (for use as a load action), or null. */
	float[] takeDeferredClear(long texture) {
		return deferredClears.isEmpty() ? null : deferredClears.remove(texture);
	}

	/** True if texture (or the texture it is a view of) has a pending deferred clear. */
	boolean hasDeferredClear(long texture) {
		if (deferredClears.isEmpty()) {
			return false;
		}
		if (deferredClears.containsKey(texture)) {
			return true;
		}
		long parent = send(texture, S_PARENT_TEXTURE);
		return parent != 0L && deferredClears.containsKey(parent);
	}

	/** Runs texture's pending clear now, if any (also when given a view of a texture with one). */
	public void flushClear(long texture) {
		if (deferredClears.isEmpty() || texture == 0L) {
			return;
		}
		float[] c = deferredClears.remove(texture);
		if (c == null) {
			long parent = send(texture, S_PARENT_TEXTURE);
			c = parent != 0L ? deferredClears.remove(parent) : null;
			texture = parent;
		}
		if (c != null) {
			clear(texture, 1, c, 0L, 0L, 0, 0.0);
		}
	}

	public void flushClears() {
		while (!deferredClears.isEmpty()) {
			var first = deferredClears.entrySet().iterator().next();
			deferredClears.remove(first.getKey());
			clear(first.getKey(), 1, first.getValue(), 0L, 0L, 0, 0.0);
		}
	}

	/** The texture is being released: forget its pending clear. */
	public void dropDeferredClear(long texture) {
		deferredClears.remove(texture);
	}

	public void submit() {
		if (renderPass == null) {
			flushClears();
		}
		if (renderPass != null) {
			throw new IllegalStateException("Cannot submit while a render pass is open");
		}
		endBlit();
		endCompute();
		long cb = commandBuffer();
		send(cb, S_COMMIT);
		inFlight[(int)(submitIndex % inFlight.length)] = cb;
		commandBuffer = 0L;
		submitIndex++;
		scratchOffset = SCRATCH_BLOCK;
		awaitCompletion(submitIndex - MAX_IN_FLIGHT, -1);
	}

	/** Waits up to timeoutNs (negative or Long.MAX_VALUE = forever) for the given submit to complete. */
	public boolean awaitCompletion(long index, long timeoutNs) {
		if (index <= completedIndex) {
			return true;
		}
		if (index >= submitIndex) {
			// Still recording: can never complete without a submit.
			return false;
		}
		long cb = inFlight[(int)(index % inFlight.length)];
		if (timeoutNs < 0 || timeoutNs == Long.MAX_VALUE) {
			send(cb, S_WAIT);
		} else {
			long deadline = System.nanoTime() + timeoutNs;
			while (!finished(cb)) {
				if (System.nanoTime() >= deadline) {
					return false;
				}
				Thread.onSpinWait();
			}
		}
		String failure = null;
		for (long i = completedIndex + 1; i <= index; i++) {
			int slot = (int)(i % inFlight.length);
			// Earlier buffers completed before this one (same queue); check each, not only the awaited one.
			if (failure == null && send(inFlight[slot], S_STATUS) == Mtl.CB_STATUS_ERROR) {
				long pool = poolPush();
				try {
					failure = "Metal command buffer " + i + " failed: " + errorString(send(inFlight[slot], sel("error")));
				} finally {
					poolPop(pool);
				}
			}
			gpuSeconds += Calls.getDouble(inFlight[slot], "GPUEndTime") - Calls.getDouble(inFlight[slot], "GPUStartTime");
			release(inFlight[slot]);
			inFlight[slot] = 0L;
		}
		if (failure != null) {
			completedIndex = index;
			throw new IllegalStateException(failure);
		}
		completedIndex = index;
		runCompleted();
		return true;
	}

	private static boolean finished(long cb) {
		long status = send(cb, S_STATUS);
		return status == Mtl.CB_STATUS_COMPLETED || status == Mtl.CB_STATUS_ERROR;
	}

	private void runCompleted() {
		List<Runnable> ready = new ArrayList<>();
		while (!pending.isEmpty() && pending.peek().submitIndex <= completedIndex) {
			ready.add(pending.poll().task);
		}
		ready.forEach(Runnable::run);
	}

	// ---- copies ---------------------------------------------------------------------------------------------------

	public void copyBuffer(long src, long srcOffset, long dst, long dstOffset, long size) {
		send(blitEncoder(), sel("copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:"), src, srcOffset, dst, dstOffset, size);
	}

	public void copyBufferToTexture(long buffer, long offset, long bytesPerRow, int w, int h, long texture, int slice, int level, int x, int y) {
		Calls.copyBufferToTexture(blitEncoder(), buffer, offset, bytesPerRow, bytesPerRow * h, w, h, texture, slice, level, x, y);
	}

	public void copyTextureToBuffer(long texture, int level, int x, int y, int w, int h, long buffer, long offset, long bytesPerRow) {
		Calls.copyTextureToBuffer(blitEncoder(), texture, 0, level, x, y, w, h, buffer, offset, bytesPerRow, bytesPerRow * h);
	}

	/** Uploads a whole 3D texture from a buffer (tightly packed rows and slices). */
	public void copyBufferToTexture3D(long buffer, long offset, long bytesPerRow, int w, int h, int d, long texture) {
		Calls.copyBufferToTexture3D(blitEncoder(), buffer, offset, bytesPerRow, bytesPerRow * h, w, h, d, texture);
	}

	private final long[] clear3d = new long[3];

	/**
	 * Zeroes a whole 3D texture with a compute kernel (write-only, no source read), much cheaper than copying from a
	 * zero buffer for large volumes. kind: 'f' float/normalized, 'u' unsigned int, 'i' signed int formats. The texture
	 * needs shader-write usage.
	 */
	public void clearTexture3D(long texture, int w, int h, int d, char kind) {
		int k = kind == 'u' ? 1 : kind == 'i' ? 2 : 0;
		if (clear3d[k] == 0L) {
			String type = k == 1 ? "uint" : k == 2 ? "int" : "float";
			clear3d[k] = device.newComputePipeline("graft clear3d " + type, """
				#include <metal_stdlib>
				using namespace metal;
				kernel void main0(texture3d<%s, access::write> t [[texture(0)]], uint3 g [[thread_position_in_grid]]) {
					if (g.x < t.get_width() && g.y < t.get_height() && g.z < t.get_depth()) {
						t.write(%s4(0), g);
					}
				}
				""".formatted(type, type));
		}
		setComputePipeline(clear3d[k]);
		send(computeEncoder(), S_COMPUTE_TEXTURE, texture, 0L);
		dispatch((w + 7) / 8, (h + 7) / 8, (d + 7) / 8, 8, 8, 8);
	}

	/** Fills mip levels 1..n from level 0. The texture must have been created with a mip chain. */
	public void generateMipmaps(long texture) {
		send(blitEncoder(), sel("generateMipmapsForTexture:"), texture);
	}

	public void copyTextureToTexture(long src, long dst, int level, int dstX, int dstY, int srcX, int srcY, int w, int h) {
		Calls.copyTextureToTexture(blitEncoder(), src, level, srcX, srcY, w, h, dst, dstX, dstY);
	}

	// ---- clears ---------------------------------------------------------------------------------------------------

	/** Clears every mip level of slice 0 of a color texture (pass null color) and/or depth texture. */
	public void clear(long colorTexture, int colorMips, float[] color, long depthTexture, long depthFormat, int depthMips, double depth) {
		if (renderPass != null) {
			if (renderPass.absorbClear(colorTexture != 0L && colorMips > 0 ? colorTexture : 0L, colorMips, color, depthTexture != 0L && depthMips > 0 ? depthTexture : 0L, depthFormat, depthMips, depth)) {
				return; // becomes the load action of the pass's next encoder
			}
			renderPass.suspend();
		}
		endBlit();
		int levels = Math.max(colorTexture != 0L ? colorMips : 0, depthTexture != 0L ? depthMips : 0);
		for (int level = 0; level < levels; level++) {
			long pool = poolPush();
			try {
				long desc = send(cls("MTLRenderPassDescriptor"), S_PASS_DESCRIPTOR);
				if (colorTexture != 0L && level < colorMips) {
					long ca = send(send(desc, S_COLOR_ATTACHMENTS), S_AT_INDEX, 0L);
					send(ca, S_SET_TEXTURE, colorTexture);
					send(ca, S_SET_LEVEL, level);
					send(ca, S_SET_LOAD, Mtl.LOAD_CLEAR);
					send(ca, S_SET_STORE, Mtl.STORE_STORE);
					Calls.setClearColor(ca, color[0], color[1], color[2], color[3]);
				}
				if (depthTexture != 0L && level < depthMips) {
					MetalRenderPass.configureDepth(desc, depthTexture, depthFormat, level, Mtl.LOAD_CLEAR, depth);
				}
				long encoder = openRenderEncoder(desc);
				send(encoder, S_END);
				release(encoder);
			} finally {
				poolPop(pool);
			}
		}
	}

	/** Clears a rectangle of color + depth attachments by drawing a fullscreen triangle under a scissor. */
	public void clearRegion(long colorTexture, long colorFormat, float[] color, long depthTexture, long depthFormat, double depth, int w, int h, int x, int y, int rw, int rh) {
		MetalRenderPass.Attachment[] colors = {new MetalRenderPass.Attachment(colorTexture, colorFormat, 0, null, Double.NaN)};
		MetalRenderPass.Attachment depthAttachment = new MetalRenderPass.Attachment(depthTexture, depthFormat, 0, null, Double.NaN);
		MetalRenderPass pass = beginRenderPass(colors, depthAttachment, 0, 0, w, h, "graft clear region");
		pass.drawInternalClear(device.internalPipeline("mc_clear", colorFormat, depthFormat), color, (float)depth, x, y, rw, rh);
		endRenderPass();
	}

	@Override
	public void close() {
		if (renderPass != null) {
			endRenderPass();
		}
		submit();
		awaitCompletion(submitIndex - 1, -1);
	}
}
