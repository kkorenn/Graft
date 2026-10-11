package dev.graft.v1_21_5;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;

/** 1.21.5 textures are sampled directly (no views) and keep their sampling state on the texture. */
public class MetalTexture extends GpuTexture {
	final long mtl;
	final long pixelFormat;
	final int texelSize;
	final int metalMips;
	private boolean closed;

	public MetalTexture(MetalDevice device, String label, TextureFormat format, int width, int height, int mipLevels) {
		super(label, format, width, height, mipLevels);
		this.pixelFormat = switch (format) {
			case RGBA8 -> Mtl.pixelFormat("RGBA8_UNORM");
			case RED8 -> Mtl.pixelFormat("R8_UNORM");
			case DEPTH32 -> Mtl.PIXEL_DEPTH32F;
		};
		this.texelSize = format.pixelSize();
		this.metalMips = Math.min(mipLevels, 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))));
		this.mtl = device.newTexture(pixelFormat, false, width, height, 1, metalMips);
		MetalDevice.setLabel(mtl, label);
	}

	int samplerKey() {
		return addressModeU.ordinal() | addressModeV.ordinal() << 2 | minFilter.ordinal() << 4 | magFilter.ordinal() << 6 | (useMipmaps ? 1 << 8 : 0);
	}

	long newSampler(MetalDevice device) {
		return device.newSampler(
			Mtl.addressMode(addressModeU.name()), Mtl.addressMode(addressModeV.name()), Mtl.filter(minFilter.name()), Mtl.filter(magFilter.name()),
			useMipmaps ? 2L : 0L, 1, useMipmaps ? 1000.0 : 0.25, -1L
		);
	}

	/** API: the MTLTexture handle. */
	/** API: the MTLPixelFormat. */
	public long pixelFormat() {
		return pixelFormat;
	}

	public long mtl() {
		return mtl;
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
}
