package dev.graft.v26_3;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalRenderPass;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public class MetalCommandEncoder implements CommandEncoderBackend {
	private final MetalGpuDevice device;
	private final MetalCommands commands;
	private final MetalTransientMemory transientMemory;

	MetalCommandEncoder(MetalGpuDevice device) {
		this.device = device;
		this.commands = device.commands;
		this.transientMemory = new MetalTransientMemory(device.metal, commands);
	}

	static long mtl(GpuBuffer buffer) {
		return ((MtlBuffer)buffer).mtl();
	}

	static MetalTexture tex(GpuTexture texture) {
		return (MetalTexture)texture;
	}

	private static float[] rgba(Vector4fc c) {
		return new float[]{c.x(), c.y(), c.z(), c.w()};
	}

	void close() {
		transientMemory.endSubmit();
		commands.close();
		transientMemory.close();
	}

	@Override
	public void submit() {
		transientMemory.endSubmit();
		commands.submit();
	}

	@Override
	public TransientMemory transientMemory() {
		return transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		MetalRenderPass.Attachment[] colorAttachments = new MetalRenderPass.Attachment[colors.size()];
		int width = 0;
		int height = 0;
		for (int i = 0; i < colors.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
			if (a == null) {
				continue;
			}
			MetalTexture.View view = (MetalTexture.View)a.textureView();
			colorAttachments[i] = new MetalRenderPass.Attachment(view.mtl, view.metalTexture().pixelFormat, 0, a.clearValue().map(MetalCommandEncoder::rgba).orElse(null), Double.NaN);
			width = view.getWidth(0);
			height = view.getHeight(0);
		}
		MetalRenderPass.Attachment depth = null;
		RenderPassDescriptor.Attachment<OptionalDouble> d = descriptor.depthAttachment();
		if (d != null) {
			MetalTexture.View view = (MetalTexture.View)d.textureView();
			depth = new MetalRenderPass.Attachment(view.mtl, view.metalTexture().pixelFormat, 0, null, d.clearValue().orElse(Double.NaN));
			if (width == 0) {
				width = view.getWidth(0);
				height = view.getHeight(0);
			}
		}
		var area = descriptor.renderArea();
		MetalRenderPass pass = area != null
			? commands.beginRenderPass(colorAttachments, depth, area.x(), area.y(), area.width(), area.height(), descriptor.label().get())
			: commands.beginRenderPass(colorAttachments, depth, 0, 0, width, height, descriptor.label().get());
		return new MetalRenderPassBackend(device, pass);
	}

	@Override
	public void submitRenderPass() {
		commands.endRenderPass();
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		commands.clear(tex(colorTexture).mtl, colorTexture.getMipLevels(), rgba(clearColor), 0L, 0L, 0, 0.0);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		commands.clear(tex(colorTexture).mtl, colorTexture.getMipLevels(), rgba(clearColor), tex(depthTexture).mtl, tex(depthTexture).pixelFormat, depthTexture.getMipLevels(), clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(
		GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth, int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel
	) {
		// ponytail: clears mip 0 only; every caller in 26.3 passes 0. Render into a level view if that changes.
		commands.clearRegion(
			tex(colorTexture).mtl, tex(colorTexture).pixelFormat, rgba(clearColor), tex(depthTexture).mtl, tex(depthTexture).pixelFormat, clearDepth,
			colorTexture.getWidth(0), colorTexture.getHeight(0), regionX, regionY, regionWidth, regionHeight
		);
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		commands.clear(0L, 0, null, tex(depthTexture).mtl, tex(depthTexture).pixelFormat, depthTexture.getMipLevels(), clearDepth);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalCommands.Scratch s = commands.scratch(data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), s.address(), data.remaining());
		commands.copyBuffer(s.buffer(), s.offset(), mtl(destination.buffer()), destination.offset(), data.remaining());
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		commands.copyBuffer(mtl(source.buffer()), source.offset(), mtl(target.buffer()), target.offset(), source.length());
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
		MetalTexture t = tex(destination);
		MetalCommands.Scratch s = commands.scratch(source.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(source), s.address(), source.remaining());
		commands.copyBufferToTexture(s.buffer(), s.offset(), (long)width * t.texelSize, width, height, t.mtl, depthOrLayer, mipLevel, destX, destY);
	}

	@Override
	public void copyBufferToTexture(
		GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer
	) {
		MetalTexture t = tex(destination);
		long skip = ((long)sourceX + (long)sourceY * sourceWidth) * t.texelSize;
		commands.copyBufferToTexture(mtl(source.buffer()), source.offset() + skip, (long)sourceWidth * t.texelSize, copyWidth, copyHeight, t.mtl, arrayLayer, mipLevel, destinationX, destinationY);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
		MetalTexture t = tex(source);
		commands.copyTextureToBuffer(t.mtl, mipLevel, x, y, width, height, mtl(destination), offset, (long)width * t.texelSize);
		commands.afterGpu(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		commands.copyTextureToTexture(tex(source).mtl, tex(destination).mtl, mipLevel, destX, destY, sourceX, sourceY, width, height);
	}

	@Override
	public GpuFence createFence() {
		long index = commands.submitIndex();
		return new GpuFence() {
			private boolean done;

			@Override
			public boolean awaitCompletion(long timeoutNs) {
				if (!done) {
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

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		((MetalQueryPool)pool).write(index);
	}
}
