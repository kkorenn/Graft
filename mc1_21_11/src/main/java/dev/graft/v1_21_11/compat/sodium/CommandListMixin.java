package dev.graft.v1_21_11.compat.sodium;

import dev.graft.v1_21_11.compat.SodiumMetal;

import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlImmutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.sync.GlFence;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

import java.nio.ByteBuffer;

/** Sodium's immediate command list, on Metal (see SodiumMetal). */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateCommandList", remap = false)
public abstract class CommandListMixin {
	/** @author Graft @reason Metal */
	@Overwrite
	public void bindVertexArray(GlVertexArray array) {
		SodiumMetal.bindVertexArray(array.handle());
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void unbindVertexArray() {
		SodiumMetal.bindVertexArray(0);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void uploadData(GlMutableBuffer buffer, ByteBuffer data, GlBufferUsage usage) {
		SodiumMetal.allocate(buffer.handle(), data.remaining(), MemoryUtil.memAddress(data));
		buffer.setSize(data.remaining());
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void uploadDataToOffset(GlMutableBuffer buffer, int offset, long pointer, int size) {
		SodiumMetal.subData(buffer.handle(), offset, pointer, size);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes) {
		SodiumMetal.copy(src.handle(), dst.handle(), readOffset, writeOffset, bytes);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void bindBuffer(GlBufferTarget target, GlBuffer buffer) {
		SodiumMetal.bindBuffer(target.getTargetParameter(), buffer.handle());
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void allocateStorage(GlMutableBuffer buffer, long size, GlBufferUsage usage) {
		SodiumMetal.allocate(buffer.handle(), size, 0L);
		buffer.setSize(size);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void deleteBuffer(GlBuffer buffer) {
		if (buffer.getActiveMapping() != null) {
			buffer.getActiveMapping().dispose();
			buffer.setActiveMapping(null);
		}
		int handle = buffer.handle();
		buffer.invalidateHandle();
		SodiumMetal.deleteBuffer(handle);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void deleteVertexArray(GlVertexArray array) {
		int handle = array.handle();
		array.invalidateHandle();
		SodiumMetal.deleteVertexArray(handle);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
		if (buffer.getActiveMapping() != null) {
			throw new IllegalStateException("Buffer is already mapped");
		}
		GlBufferMapping mapping = new GlBufferMapping(buffer, SodiumMetal.map(buffer.handle(), offset, length));
		buffer.setActiveMapping(mapping);
		return mapping;
	}

	/** @author Graft @reason Metal (shared memory: nothing to unmap) */
	@Overwrite
	public void unmap(GlBufferMapping map) {
		map.getBufferObject().setActiveMapping(null);
		map.dispose();
	}

	/** @author Graft @reason Metal (shared memory is coherent) */
	@Overwrite
	public void flushMappedRange(GlBufferMapping map, int offset, int length) {
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public GlFence createFence() {
		return new GlFence(SodiumMetal.fence());
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public GlImmutableBuffer createImmutableBuffer(long size, EnumBitField<GlBufferStorageFlags> flags) {
		GlImmutableBuffer buffer = new GlImmutableBuffer(flags);
		SodiumMetal.allocate(buffer.handle(), size, 0L);
		return buffer;
	}
}
