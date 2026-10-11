package dev.graft.metal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;

import static dev.graft.metal.ObjC.rethrow;
import static dev.graft.metal.ObjC.sel;
import static java.lang.foreign.ValueLayout.*;

/** objc_msgSend calls that pass or return C structs by value. */
final class Calls {
	static final StructLayout ORIGIN = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
	static final StructLayout SIZE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
	static final StructLayout RANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
	static final StructLayout SCISSOR = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	static final StructLayout VIEWPORT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
	static final StructLayout COLOR = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
	static final StructLayout CGSIZE = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);

	private static final MethodHandle SET_VIEWPORT = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, VIEWPORT));
	private static final MethodHandle SET_SCISSOR = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, SCISSOR));
	private static final MethodHandle SET_COLOR = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, COLOR));
	private static final MethodHandle SET_CGSIZE = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, CGSIZE));
	private static final MethodHandle GET_DOUBLE = ObjC.custom(FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_LONG));
	private static final MethodHandle TEXTURE_VIEW = ObjC.custom(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, RANGE, RANGE));
	// copyFromBuffer:sourceOffset:sourceBytesPerRow:sourceBytesPerImage:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:
	private static final MethodHandle BUFFER_TO_TEXTURE = ObjC.custom(FunctionDescriptor.ofVoid(
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, SIZE, JAVA_LONG, JAVA_LONG, JAVA_LONG, ORIGIN
	));
	// copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:
	private static final MethodHandle TEXTURE_TO_BUFFER = ObjC.custom(FunctionDescriptor.ofVoid(
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ORIGIN, SIZE, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG
	));
	// copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:
	private static final MethodHandle TEXTURE_TO_TEXTURE = ObjC.custom(FunctionDescriptor.ofVoid(
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ORIGIN, SIZE, JAVA_LONG, JAVA_LONG, JAVA_LONG, ORIGIN
	));

	private static final long SEL_SET_VIEWPORT = sel("setViewport:");
	private static final long SEL_SET_SCISSOR = sel("setScissorRect:");
	private static final long SEL_SET_CLEAR_COLOR = sel("setClearColor:");
	private static final long SEL_SET_DRAWABLE_SIZE = sel("setDrawableSize:");
	private static final long SEL_TEXTURE_VIEW = sel("newTextureViewWithPixelFormat:textureType:levels:slices:");
	private static final long SEL_BUFFER_TO_TEXTURE = sel(
		"copyFromBuffer:sourceOffset:sourceBytesPerRow:sourceBytesPerImage:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:"
	);
	private static final long SEL_TEXTURE_TO_BUFFER = sel(
		"copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:"
	);
	private static final long SEL_TEXTURE_TO_TEXTURE = sel(
		"copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:"
	);

	// Struct arguments are copied by the callee, so one scratch area per thread is enough.
	private static final ThreadLocal<MemorySegment[]> SCRATCH = ThreadLocal.withInitial(() -> {
		java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofAuto();
		return new MemorySegment[]{arena.allocate(64, 8), arena.allocate(64, 8), arena.allocate(64, 8)};
	});

	private Calls() {
	}

	private static MemorySegment longs(int slot, long... values) {
		MemorySegment s = SCRATCH.get()[slot];
		for (int i = 0; i < values.length; i++) {
			s.setAtIndex(JAVA_LONG, i, values[i]);
		}
		return s;
	}

	private static MemorySegment doubles(int slot, double... values) {
		MemorySegment s = SCRATCH.get()[slot];
		for (int i = 0; i < values.length; i++) {
			s.setAtIndex(JAVA_DOUBLE, i, values[i]);
		}
		return s;
	}

	static void setViewport(long encoder, double x, double y, double w, double h) {
		try {
			SET_VIEWPORT.invokeExact(encoder, SEL_SET_VIEWPORT, doubles(0, x, y, w, h, 0.0, 1.0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setScissor(long encoder, long x, long y, long w, long h) {
		try {
			SET_SCISSOR.invokeExact(encoder, SEL_SET_SCISSOR, longs(0, x, y, w, h));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setClearColor(long attachment, double r, double g, double b, double a) {
		try {
			SET_COLOR.invokeExact(attachment, SEL_SET_CLEAR_COLOR, doubles(0, r, g, b, a));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setDrawableSize(long layer, double w, double h) {
		try {
			SET_CGSIZE.invokeExact(layer, SEL_SET_DRAWABLE_SIZE, doubles(0, w, h));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static double getDouble(long obj, String selector) {
		try {
			return (double)GET_DOUBLE.invokeExact(obj, sel(selector));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long newTextureView(long texture, long pixelFormat, long type, long baseLevel, long levels, long baseSlice, long slices) {
		try {
			return (long)TEXTURE_VIEW.invokeExact(texture, SEL_TEXTURE_VIEW, pixelFormat, type, longs(0, baseLevel, levels), longs(1, baseSlice, slices));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void copyBufferToTexture(
		long blit, long buffer, long offset, long bytesPerRow, long bytesPerImage, long w, long h, long texture, long slice, long level, long x, long y
	) {
		try {
			BUFFER_TO_TEXTURE.invokeExact(
				blit, SEL_BUFFER_TO_TEXTURE, buffer, offset, bytesPerRow, bytesPerImage, longs(0, w, h, 1L), texture, slice, level, longs(1, x, y, 0L)
			);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void copyBufferToTexture3D(long blit, long buffer, long offset, long bytesPerRow, long bytesPerImage, long w, long h, long d, long texture) {
		try {
			BUFFER_TO_TEXTURE.invokeExact(
				blit, SEL_BUFFER_TO_TEXTURE, buffer, offset, bytesPerRow, bytesPerImage, longs(0, w, h, d), texture, 0L, 0L, longs(1, 0L, 0L, 0L)
			);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static final MethodHandle DISPATCH = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, SIZE, SIZE));
	private static final MethodHandle DISPATCH_INDIRECT = ObjC.custom(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, SIZE));
	private static final long SEL_DISPATCH = sel("dispatchThreadgroups:threadsPerThreadgroup:");
	private static final long SEL_DISPATCH_INDIRECT = sel("dispatchThreadgroupsWithIndirectBuffer:indirectBufferOffset:threadsPerThreadgroup:");

	static void dispatch(long encoder, long gx, long gy, long gz, long tx, long ty, long tz) {
		try {
			DISPATCH.invokeExact(encoder, SEL_DISPATCH, longs(0, gx, gy, gz), longs(1, tx, ty, tz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void dispatchIndirect(long encoder, long buffer, long offset, long tx, long ty, long tz) {
		try {
			DISPATCH_INDIRECT.invokeExact(encoder, SEL_DISPATCH_INDIRECT, buffer, offset, longs(0, tx, ty, tz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void copyTextureToBuffer(
		long blit, long texture, long slice, long level, long x, long y, long w, long h, long buffer, long offset, long bytesPerRow, long bytesPerImage
	) {
		try {
			TEXTURE_TO_BUFFER.invokeExact(
				blit, SEL_TEXTURE_TO_BUFFER, texture, slice, level, longs(0, x, y, 0L), longs(1, w, h, 1L), buffer, offset, bytesPerRow, bytesPerImage
			);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void copyTextureToTexture(long blit, long src, long level, long srcX, long srcY, long w, long h, long dst, long dstX, long dstY) {
		try {
			TEXTURE_TO_TEXTURE.invokeExact(
				blit, SEL_TEXTURE_TO_TEXTURE, src, 0L, level, longs(0, srcX, srcY, 0L), longs(1, w, h, 1L), dst, 0L, level, longs(2, dstX, dstY, 0L)
			);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
