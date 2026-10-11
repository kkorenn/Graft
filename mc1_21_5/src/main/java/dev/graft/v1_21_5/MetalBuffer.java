package dev.graft.v1_21_5;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;

/** A shared-storage MTLBuffer. Metal command buffers retain what they use, so close() can release immediately. */
public class MetalBuffer extends GpuBuffer {
	final long mtl;
	private boolean closed;

	public MetalBuffer(MetalDevice device, String label, BufferType type, BufferUsage usage, int size) {
		super(type, usage, size);
		this.mtl = device.newBuffer(Math.max(1, size));
		MetalDevice.setLabel(mtl, label);
	}

	/** API: the MTLBuffer handle. */
	public long mtl() {
		return mtl;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			ObjC.release(mtl);
		}
	}
}
