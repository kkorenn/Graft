package dev.graft.v1_21;

import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;

/** A shared-storage MTLBuffer. Metal command buffers retain what they use, so close() can release immediately. */
public class MetalBuffer extends GpuBuffer implements MtlBuffer {
	private final long mtl;
	private boolean closed;

	public MetalBuffer(MetalDevice device, String label, int usage, int size) {
		super(usage, size);
		this.mtl = device.newBuffer(Math.max(1, size));
		MetalDevice.setLabel(mtl, label);
	}

	@Override
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
