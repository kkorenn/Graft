package dev.graft.v1_21;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalRenderPass;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * GL has no explicit submit: work is submitted when the frame is presented ({@link MetalGpuDevice#swap}) and whenever
 * something has to wait for the GPU (fences, read mappings).
 */
public class MetalCommandEncoder implements CommandEncoder {
	private final MetalGpuDevice device;
	private final MetalCommands commands;

	MetalCommandEncoder(MetalGpuDevice device) {
		this.device = device;
		this.commands = device.commands;
	}

	static MetalTexture tex(GpuTexture texture) {
		return (MetalTexture)texture;
	}

	static long mtl(GpuBuffer buffer) {
		return ((MetalBuffer)buffer).mtl();
	}

	/** ARGB int (Blaze3D 1.21 clear colors) to RGBA floats. */
	static float[] rgba(int argb) {
		return new float[]{(argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F};
	}

	/** Submits recorded work (outside a render pass) so the GPU can finish it. */
	void flush() {
		if (!commands.inRenderPass()) {
			commands.submit();
		}
	}

	@Override
	public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorView, OptionalInt clearColor) {
		return createRenderPass(label, colorView, clearColor, null, OptionalDouble.empty());
	}

	@Override
	public RenderPass createRenderPass(
		Supplier<String> label, GpuTextureView colorView, OptionalInt clearColor, @Nullable GpuTextureView depthView, OptionalDouble clearDepth
	) {
		MetalTexture.View color = (MetalTexture.View)colorView;
		MetalRenderPass.Attachment[] colors = {
			new MetalRenderPass.Attachment(color.mtl, color.metalTexture().pixelFormat, 0, clearColor.isPresent() ? rgba(clearColor.getAsInt()) : null, Double.NaN)
		};
		MetalRenderPass.Attachment depth = null;
		if (depthView != null) {
			MetalTexture.View d = (MetalTexture.View)depthView;
			depth = new MetalRenderPass.Attachment(d.mtl, d.metalTexture().pixelFormat, 0, null, clearDepth.orElse(Double.NaN));
		}
		MetalRenderPass pass = commands.beginRenderPass(colors, depth, 0, 0, color.getWidth(0), color.getHeight(0), label.get());
		return new MetalRenderPassBackend(device, pass);
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, int clearColor) {
		commands.clear(tex(colorTexture).mtl, tex(colorTexture).metalMips, rgba(clearColor), 0L, 0L, 0, 0.0);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
		commands.clear(tex(colorTexture).mtl, tex(colorTexture).metalMips, rgba(clearColor), tex(depthTexture).mtl, tex(depthTexture).pixelFormat, tex(depthTexture).metalMips, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int x, int y, int width, int height) {
		commands.clearRegion(
			tex(colorTexture).mtl, tex(colorTexture).pixelFormat, rgba(clearColor), tex(depthTexture).mtl, tex(depthTexture).pixelFormat, clearDepth,
			colorTexture.getWidth(0), colorTexture.getHeight(0), x, y, width, height
		);
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		commands.clear(0L, 0, null, tex(depthTexture).mtl, tex(depthTexture).pixelFormat, tex(depthTexture).metalMips, clearDepth);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalCommands.Scratch s = commands.scratch(data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), s.address(), data.remaining());
		commands.copyBuffer(s.buffer(), s.offset(), mtl(destination.buffer()), destination.offset(), data.remaining());
	}

	@Override
	public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
		return mapBuffer(buffer.slice(), read, write);
	}

	/**
	 * GL orders mapped writes with the commands around them. Writing the shared buffer directly could change data a
	 * frame still in flight is reading, so writes go to staging memory that a GPU copy (in command order) moves into the
	 * buffer on close. Reads first wait for all recorded work.
	 */
	@Override
	public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
		long target = mtl(slice.buffer());
		long address = MetalDevice.contents(target) + slice.offset();
		int length = (int)slice.length();
		if (read) {
			flush();
			commands.awaitCompletion(commands.submitIndex() - 1, -1);
		}
		if (!write) {
			ByteBuffer data = MemoryUtil.memByteBuffer(address, length);
			return new MappedView(data, () -> {});
		}
		MetalCommands.Scratch s = commands.scratch(length);
		MemoryUtil.memCopy(address, s.address(), length); // keep bytes the caller doesn't overwrite
		ByteBuffer data = MemoryUtil.memByteBuffer(s.address(), length);
		return new MappedView(data, () -> commands.copyBuffer(s.buffer(), s.offset(), target, slice.offset(), length));
	}

	private record MappedView(ByteBuffer data, Runnable onClose) implements GpuBuffer.MappedView {
		@Override
		public void close() {
			onClose.run();
		}
	}

	/** 1.21.7+ (no @Override: 1.21.6 lacks it). */
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		commands.copyBuffer(mtl(source.buffer()), source.offset(), mtl(target.buffer()), target.offset(), source.length());
	}

	@Override
	public void writeToTexture(GpuTexture destination, NativeImage image) {
		writeToTexture(destination, image, 0, 0, 0, 0, destination.getWidth(0), destination.getHeight(0), 0, 0);
	}

	@Override
	public void writeToTexture(GpuTexture destination, NativeImage image, int mipLevel, int layer, int destX, int destY, int width, int height, int srcX, int srcY) {
		int bpp = image.format().components();
		long rowBytes = (long)image.getWidth() * bpp;
		long first = image.getPointer() + srcY * rowBytes + (long)srcX * bpp;
		long bytes = (height - 1) * rowBytes + (long)width * bpp;
		upload(destination, first, bytes, rowBytes, bpp, mipLevel, layer, destX, destY, width, height);
	}

	/** 1.21.6 form (no @Override: later versions take a ByteBuffer). */
	public void writeToTexture(GpuTexture destination, IntBuffer source, NativeImage.Format format, int mipLevel, int layer, int destX, int destY, int width, int height) {
		int bpp = format.components();
		upload(destination, MemoryUtil.memAddress(source), (long)width * height * bpp, (long)width * bpp, bpp, mipLevel, layer, destX, destY, width, height);
	}

	/** API: tightly packed texels in the texture's own format (any format, e.g. RGBA16F pack targets). */
	public void writeRaw(GpuTexture destination, ByteBuffer source, int mipLevel, int layer, int destX, int destY, int width, int height) {
		MetalTexture t = tex(destination);
		upload(destination, MemoryUtil.memAddress(source), (long)width * height * t.texelSize, (long)width * t.texelSize, t.texelSize, mipLevel, layer, destX, destY, width, height);
	}

	/** 1.21.7+ form. */
	public void writeToTexture(GpuTexture destination, ByteBuffer source, NativeImage.Format format, int mipLevel, int layer, int destX, int destY, int width, int height) {
		int bpp = format.components();
		upload(destination, MemoryUtil.memAddress(source), (long)width * height * bpp, (long)width * bpp, bpp, mipLevel, layer, destX, destY, width, height);
	}

	private void upload(GpuTexture destination, long source, long bytes, long rowBytes, int bpp, int mipLevel, int layer, int destX, int destY, int width, int height) {
		MetalTexture t = tex(destination);
		if (mipLevel >= t.metalMips || width <= 0 || height <= 0) {
			return;
		}
		if (bpp == t.texelSize) {
			MetalCommands.Scratch s = commands.scratch(bytes);
			MemoryUtil.memCopy(source, s.address(), bytes);
			commands.copyBufferToTexture(s.buffer(), s.offset(), rowBytes, width, height, t.mtl, layer, mipLevel, destX, destY);
			return;
		}
		// Component count differs from the texture (e.g. RGB image into RGBA8): GL converts, so expand per pixel.
		long dstRow = (long)width * t.texelSize;
		MetalCommands.Scratch s = commands.scratch(dstRow * height);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				long src = source + y * rowBytes + (long)x * bpp;
				long dst = s.address() + y * dstRow + (long)x * t.texelSize;
				for (int c = 0; c < t.texelSize; c++) {
					MemoryUtil.memPutByte(dst + c, c < bpp ? MemoryUtil.memGetByte(src + c) : (byte)-1);
				}
			}
		}
		commands.copyBufferToTexture(s.buffer(), s.offset(), dstRow, width, height, t.mtl, layer, mipLevel, destX, destY);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, int offset, Runnable callback, int mipLevel) {
		copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, int offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
		MetalTexture t = tex(source);
		commands.copyTextureToBuffer(t.mtl, mipLevel, x, y, width, height, mtl(destination), offset, (long)width * t.texelSize);
		// Same as GL: the callback runs from RenderSystem.executePendingTasks once the GPU is done, never re-entrantly
		// from inside an unrelated wait.
		com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		commands.copyTextureToTexture(tex(source).mtl, tex(destination).mtl, mipLevel, destX, destY, sourceX, sourceY, width, height);
	}

	@Override
	public void presentTexture(GpuTextureView textureView) {
		device.blitToSurface((MetalTexture.View)textureView);
	}

	@Override
	public GpuFence createFence() {
		long index = commands.submitIndex();
		return new GpuFence() {
			private boolean done;

			@Override
			public boolean awaitCompletion(long timeoutNs) {
				if (!done) {
					if (index >= commands.submitIndex()) {
						flush(); // GL fences sit in the command stream; ours needs that work submitted
					}
					done = commands.awaitCompletion(index, timeoutNs);
				}
				return done;
			}

			@Override
			public void close() {
				done = true;
			}
		};
	}

}
