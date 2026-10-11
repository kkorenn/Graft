package dev.graft.v26_2;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;
import org.lwjgl.system.MemoryUtil;

/** A shared-storage MTLBuffer. Metal command buffers retain what they use, so close() can release immediately. */
public class MetalBuffer extends GpuBuffer implements MtlBuffer {
	private final long mtl;
	private final long contents;
	private boolean closed;

	public MetalBuffer(MetalDevice device, String label, @GpuBuffer.Usage int usage, long size) {
		super(usage, size);
		this.mtl = device.newBuffer(size);
		this.contents = MetalDevice.contents(mtl);
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

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (offset < 0 || length < 0 || offset + length > size() || length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Bad mapping range " + offset + "+" + length + " of " + size());
		}
		return new GpuBufferSlice.MappedView(slice(offset, length), MemoryUtil.memByteBuffer(contents + offset, (int)length), () -> {});
	}
}
