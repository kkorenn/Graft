package dev.graft.v1_21;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;

public class MetalTexture extends GpuTexture {
	final long mtl;
	final long pixelFormat;
	final boolean cube;
	final int texelSize;
	/** Levels the Metal texture really has: GL accepts more than the size allows (16x16 with 6), Metal does not. */
	final int metalMips;
	private boolean closed;

	/** Real format as a 26.x-style GpuFormat name ("RGBA16_FLOAT", "D32_FLOAT"...): 1.21's TextureFormat has only 4. */
	final String formatName;

	public MetalTexture(MetalDevice device, int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		this(device, usage, label, format, formatName(format), format.pixelSize(), width, height, depthOrLayers, mipLevels);
	}

	/**
	 * API: a texture in any format (Graft Shaders: pack render targets like RGBA16F). formatName is a 26.x GpuFormat name;
	 * Minecraft sees nominal (RGBA8, or DEPTH32 for depth formats).
	 */
	public MetalTexture(MetalDevice device, int usage, String label, TextureFormat nominal, String formatName, int blockSize, int width, int height, int depthOrLayers, int mipLevels) {
		super(usage, label, nominal, width, height, depthOrLayers, mipLevels);
		this.formatName = formatName;
		this.pixelFormat = Mtl.pixelFormat(formatName);
		this.cube = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
		this.texelSize = Mtl.metalTexelSize(formatName, blockSize);
		this.metalMips = Math.min(mipLevels, 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))));
		this.mtl = device.newTexture(pixelFormat, cube, width, height, depthOrLayers, metalMips);
		MetalDevice.setLabel(mtl, label);
	}

	static long pixelFormat(TextureFormat format) {
		return switch (format) {
			case RGBA8 -> Mtl.pixelFormat("RGBA8_UNORM");
			case RED8 -> Mtl.pixelFormat("R8_UNORM");
			case RED8I -> Mtl.pixelFormat("R8_SINT");
			case DEPTH32 -> Mtl.PIXEL_DEPTH32F;
		};
	}

	static String formatName(TextureFormat format) {
		return switch (format) {
			case RGBA8 -> "RGBA8_UNORM";
			case RED8 -> "R8_UNORM";
			case RED8I -> "R8_SINT";
			case DEPTH32 -> "D32_FLOAT";
		};
	}

	/** API: the real format (26.x GpuFormat name). */
	public String formatName() {
		return formatName;
	}

	/** API: give another texture this one's filtering (Graft Shaders: PBR normal/specular maps follow their albedo). */
	public void copyFilterTo(GpuTexture texture) {
		texture.setTextureFilter(minFilter, magFilter, useMipmaps);
	}

	/** API: bytes per texel in the Metal texture. */
	public int texelSize() {
		return texelSize;
	}

	/** Key of the sampler state set on this texture (1.21.6-1.21.10 keep sampling state on the texture). */
	int samplerKey() {
		return addressModeU.ordinal() | addressModeV.ordinal() << 2 | minFilter.ordinal() << 4 | magFilter.ordinal() << 6 | (useMipmaps ? 1 << 8 : 0);
	}

	long newSampler(MetalDevice device) {
		// GL: mipmapped min filters are *_MIPMAP_LINEAR.
		return device.newSampler(
			Mtl.addressMode(addressModeU.name()), Mtl.addressMode(addressModeV.name()), Mtl.filter(minFilter.name()), Mtl.filter(magFilter.name()),
			useMipmaps ? 2L : 0L, 1, useMipmaps ? 1000.0 : 0.25, -1L
		);
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
			int base = Math.min(baseMipLevel, texture.metalMips - 1);
			this.mtl = MetalDevice.newTextureView(texture.mtl, texture.pixelFormat, texture.cube, base, Math.max(1, Math.min(mipLevels, texture.metalMips - base)));
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
