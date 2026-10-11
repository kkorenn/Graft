package dev.graft.v26_2;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;

import java.util.OptionalDouble;

public class MetalSampler extends GpuSampler {
	final long mtl;
	private final AddressMode u;
	private final AddressMode v;
	private final FilterMode min;
	private final FilterMode mag;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	public MetalSampler(MetalDevice device, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		this.u = u;
		this.v = v;
		this.min = min;
		this.mag = mag;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
		this.mtl = device.newSampler(
			Mtl.addressMode(u.name()), Mtl.addressMode(v.name()), Mtl.filter(min.name()), Mtl.filter(mag.name()), maxAnisotropy, maxLod.orElse(1000.0)
		);
	}

	/** API: the MTLSamplerState handle. */
	public long mtl() {
		return mtl;
	}

	@Override
	public AddressMode getAddressModeU() {
		return u;
	}

	@Override
	public AddressMode getAddressModeV() {
		return v;
	}

	@Override
	public FilterMode getMinFilter() {
		return min;
	}

	@Override
	public FilterMode getMagFilter() {
		return mag;
	}

	@Override
	public int getMaxAnisotropy() {
		return maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return maxLod;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			ObjC.release(mtl);
		}
	}
}
