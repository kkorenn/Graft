package dev.graft.v26_3;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-submit scratch memory. Every kind (CPU, staging, GPU) is the same shared MTLBuffer memory, so one allocator serves
 * all of them; used blocks are recycled once the GPU has finished the submit that used them.
 */
public class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 524288L;

	/** contents is the CPU address of the shared MTLBuffer, which never moves. */
	record Block(long mtl, long size, long contents) implements TransientBlockAllocator.Allocator.Block {
		@Override
		public boolean suboptimal() {
			return false;
		}
	}

	private final MetalCommands commands;
	private final TransientBlockAllocator<Block> allocator;
	private long submitIndex;

	public MetalTransientMemory(MetalDevice device, MetalCommands commands) {
		this.commands = commands;
		this.allocator = new TransientBlockAllocator<>(
			BLOCK_SIZE, Long.highestOneBit(Long.MAX_VALUE), TransientBlockAllocator.Allocator.create(size -> {
				long mtl = device.newBuffer(size);
				return new Block(mtl, size, MetalDevice.contents(mtl));
			}, b -> ObjC.release(b.mtl()))
		);
	}

	/** Call right before the command stream is submitted. */
	public void endSubmit() {
		commands.afterGpu(allocator.rotate());
		submitIndex++;
	}

	public void close() {
		allocator.close();
	}

	private TransientBlockAllocator.Allocation<Block> alloc(long size, long alignment, long minimumAllocation, long elementSize) {
		return allocator.allocate(size, Math.max(1L, alignment), minimumAllocation, elementSize);
	}

	private static ByteBuffer cpu(TransientBlockAllocator.Allocation<Block> a) {
		return MemoryUtil.memByteBuffer(a.block().contents() + a.offset(), (int)a.size());
	}

	private GpuBufferSlice slice(TransientBlockAllocator.Allocation<Block> a, @GpuBuffer.Usage int usage) {
		return new GpuBufferSlice(new TransientBuffer(a.block().mtl(), usage, a.block().size(), submitIndex), a.offset(), a.size());
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		return cpu(alloc(size, alignment, minimumAllocation, elementSize));
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<Block> a = alloc(size, alignment, minimumAllocation, elementSize);
		return new GpuBufferSlice.MappedView(slice(a, usage), cpu(a), () -> {});
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return slice(alloc(size, alignment, minimumAllocation, elementSize), usage);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		alignment = Math.max(1L, alignment);
		long totalSize = 0L;
		for (ByteBuffer buffer : data) {
			totalSize = Mth.roundToward(totalSize + buffer.remaining(), alignment);
		}
		GpuBufferSlice.MappedView mapped = allocateStaging(totalSize, alignment, usage, minimumAllocation, elementSize);
		long dst = MemoryUtil.memAddress(mapped.data());
		long offset = 0L;
		for (ByteBuffer buffer : data) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), dst + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
			offset = Mth.roundToward(offset + buffer.remaining(), alignment);
			if (offset >= mapped.slice().length()) {
				break;
			}
		}
		return mapped.slice();
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> result = new ArrayList<>(data.size());
		for (ByteBuffer buffer : data) {
			result.add(upload(List.of(buffer), alignment, usage, buffer.remaining(), 1L));
		}
		return result;
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		return multiUploadStaging(data, alignment, usage);
	}

	private class TransientBuffer extends BaseGpuBuffer implements MtlBuffer {
		private final long mtl;
		private final long bufferSubmitIndex;
		private boolean closed;

		TransientBuffer(long mtl, @GpuBuffer.Usage int usage, long size, long bufferSubmitIndex) {
			super(usage, size);
			this.mtl = mtl;
			this.bufferSubmitIndex = bufferSubmitIndex;
		}

		@Override
		public long mtl() {
			return mtl;
		}

		@Override
		public boolean isClosed() {
			return closed || bufferSubmitIndex < submitIndex;
		}

		@Override
		public void close() {
			closed = true;
		}

		@Override
		public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}
	}
}
