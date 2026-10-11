package dev.graft.v1_21_5;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalRenderPass;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** 1.21.5 CommandEncoder on Metal. Work is submitted at present and whenever something waits for the GPU. */
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
		return ((MetalBuffer)buffer).mtl;
	}

	static float[] rgba(int argb) {
		return new float[]{(argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F};
	}

	void flush() {
		if (!commands.inRenderPass()) {
			commands.submit();
		}
	}

	@Override
	public RenderPass createRenderPass(GpuTexture color, OptionalInt clearColor) {
		return createRenderPass(color, clearColor, null, OptionalDouble.empty());
	}

	@Override
	public RenderPass createRenderPass(GpuTexture colorTexture, OptionalInt clearColor, @Nullable GpuTexture depthTexture, OptionalDouble clearDepth) {
		MetalTexture color = tex(colorTexture);
		MetalRenderPass.Attachment[] colors = {
			new MetalRenderPass.Attachment(color.mtl, color.pixelFormat, 0, clearColor.isPresent() ? rgba(clearColor.getAsInt()) : null, Double.NaN)
		};
		MetalRenderPass.Attachment depth = null;
		if (depthTexture != null) {
			MetalTexture d = tex(depthTexture);
			depth = new MetalRenderPass.Attachment(d.mtl, d.pixelFormat, 0, null, clearDepth.orElse(Double.NaN));
		}
		MetalRenderPass pass = commands.beginRenderPass(colors, depth, 0, 0, color.getWidth(0), color.getHeight(0), color.getLabel());
		return new MetalRenderPassImpl(device, pass);
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
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		commands.clear(0L, 0, null, tex(depthTexture).mtl, tex(depthTexture).pixelFormat, tex(depthTexture).metalMips, clearDepth);
	}

	@Override
	public void writeToBuffer(GpuBuffer destination, ByteBuffer data, int offset) {
		MetalCommands.Scratch s = commands.scratch(data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), s.address(), data.remaining());
		commands.copyBuffer(s.buffer(), s.offset(), mtl(destination), offset, data.remaining());
	}

	@Override
	public GpuBuffer.ReadView readBuffer(GpuBuffer buffer) {
		return readBuffer(buffer, 0, buffer.size());
	}

	/** Waits for all recorded work, then reads the shared buffer in place. */
	@Override
	public GpuBuffer.ReadView readBuffer(GpuBuffer buffer, int offset, int length) {
		flush();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		ByteBuffer data = MemoryUtil.memByteBuffer(MetalDevice.contents(mtl(buffer)) + offset, length);
		return new GpuBuffer.ReadView() {
			@Override
			public ByteBuffer data() {
				return data;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeToTexture(GpuTexture destination, NativeImage image) {
		writeToTexture(destination, image, 0, 0, 0, destination.getWidth(0), destination.getHeight(0), 0, 0);
	}

	@Override
	public void writeToTexture(GpuTexture destination, NativeImage image, int mipLevel, int destX, int destY, int width, int height, int srcX, int srcY) {
		int bpp = image.format().components();
		long rowBytes = (long)image.getWidth() * bpp;
		long first = image.getPointer() + srcY * rowBytes + (long)srcX * bpp;
		long bytes = (height - 1) * rowBytes + (long)width * bpp;
		upload(destination, first, bytes, rowBytes, bpp, mipLevel, destX, destY, width, height);
	}

	@Override
	public void writeToTexture(GpuTexture destination, IntBuffer source, NativeImage.Format format, int mipLevel, int destX, int destY, int width, int height) {
		int bpp = format.components();
		upload(destination, MemoryUtil.memAddress(source), (long)width * height * bpp, (long)width * bpp, bpp, mipLevel, destX, destY, width, height);
	}

	private void upload(GpuTexture destination, long source, long bytes, long rowBytes, int bpp, int mipLevel, int destX, int destY, int width, int height) {
		MetalTexture t = tex(destination);
		if (mipLevel >= t.metalMips || width <= 0 || height <= 0) {
			return;
		}
		if (bpp == t.texelSize) {
			MetalCommands.Scratch s = commands.scratch(bytes);
			MemoryUtil.memCopy(source, s.address(), bytes);
			commands.copyBufferToTexture(s.buffer(), s.offset(), rowBytes, width, height, t.mtl, 0, mipLevel, destX, destY);
			return;
		}
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
		commands.copyBufferToTexture(s.buffer(), s.offset(), dstRow, width, height, t.mtl, 0, mipLevel, destX, destY);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, int offset, Runnable callback, int mipLevel) {
		copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, int offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
		MetalTexture t = tex(source);
		commands.copyTextureToBuffer(t.mtl, mipLevel, x, y, width, height, mtl(destination), offset, (long)width * t.texelSize);
		com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		commands.copyTextureToTexture(tex(source).mtl, tex(destination).mtl, mipLevel, destX, destY, sourceX, sourceY, width, height);
	}

	@Override
	public void presentTexture(GpuTexture texture) {
		device.blitToSurface(tex(texture));
	}

	/** GpuFence (see GpuFenceMixin): the submit index work up to now will have. */
	long fenceIndex() {
		return commands.submitIndex();
	}

	boolean awaitFence(long index, long timeoutNs) {
		if (index >= commands.submitIndex()) {
			flush();
		}
		return commands.awaitCompletion(index, timeoutNs);
	}
}
