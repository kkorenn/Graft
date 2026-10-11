package dev.graft.v26_2;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;

public class MetalTexture extends GpuTexture {
	final long mtl;
	final long pixelFormat;
	final boolean cube;
	final int texelSize;
	private boolean closed;

	public MetalTexture(MetalDevice device, @GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.pixelFormat = Mtl.pixelFormat(format.name());
		this.cube = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
		this.texelSize = Mtl.metalTexelSize(format.name(), format.blockSize());
		this.mtl = device.newTexture(pixelFormat, cube, width, height, depthOrLayers, mipLevels);
		MetalDevice.setLabel(mtl, label);
	}

	/** API: the MTLTexture handle. */
	public long mtl() {
		return mtl;
	}

	/** API: the MTLPixelFormat. */
	public long pixelFormat() {
		return pixelFormat;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			MetalGpuDevice.current().commands().dropDeferredClear(mtl);
			ObjC.release(mtl);
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	public static class View extends GpuTextureView {
		final long mtl;
		private boolean closed;

		public View(MetalTexture texture, int baseMipLevel, int mipLevels) {
			super(texture, baseMipLevel, mipLevels);
			this.mtl = MetalDevice.newTextureView(texture.mtl, texture.pixelFormat, texture.cube, baseMipLevel, mipLevels);
		}

		/** API: the MTLTexture view handle. */
		public long mtl() {
			return mtl;
		}

		public MetalTexture metalTexture() {
			return (MetalTexture)texture();
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				ObjC.release(mtl);
			}
		}

		@Override
		public boolean isClosed() {
			return closed;
		}
	}
}
