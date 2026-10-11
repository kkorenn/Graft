package dev.graft.metal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.graft.metal.ObjC.*;

/**
 * A compiled render pipeline. Metal bakes attachment formats into MTLRenderPipelineState, so states are built lazily per
 * combination of attachment formats actually used by a render pass (usually exactly one).
 */
public final class MetalPipeline implements AutoCloseable {
	public record VertexBuffer(int slot, int stride, int stepRate) {
	}

	public record Attribute(int slot, int location, int offset, long vertexFormat) {
	}

	/** Blend factors/ops are Metal values. srcRgb < 0 means blending disabled. */
	public record ColorTarget(long pixelFormat, long writeMask, long srcRgb, long dstRgb, long opRgb, long srcAlpha, long dstAlpha, long opAlpha) {
		public static ColorTarget opaque(long pixelFormat, long writeMask) {
			return new ColorTarget(pixelFormat, writeMask, -1, 0, 0, 0, 0, 0);
		}
	}

	/** depthCompare < 0 means the pipeline has no depth test. */
	public record Raster(long primitive, boolean triangleFan, boolean cull, boolean wireframe, long depthCompare, boolean depthWrite, float depthBiasConstant, float depthBiasSlope) {
	}

	private record Variant(long[] colorFormats, long depthFormat, long state) {
	}

	public final String name;
	public final List<ShaderTranslator.Kind> bindings;
	public final Raster raster;

	/**
	 * Geometry-shader emulation (Graft Shaders): the vertex function fetches vertices itself, so draws become
	 * drawPrimitives over slotsPerPrim vertices per input primitive. paramsSlot gets {indexMode, first, baseVertex,
	 * stride, attributes...} as uint32s; vertexSlot/indexSlot get the raw vertex and index buffers.
	 */
	public record Geometry(int inVerts, int slotsPerPrim, int paramsSlot, int vertexSlot, int indexSlot, int stride, int[] attributes) {
	}

	/** Set by the creator right after construction; null for ordinary pipelines. */
	public Geometry geometry;

	/**
	 * Tessellation (Graft Shaders): prepass is a compute pipeline running the vertex + control stages (threads per group);
	 * the vertex function is the post-tessellation (evaluation) function. patchBytes: control-point data per patch.
	 * partition: MTLTessellationPartitionMode; winding: MTLWinding. Slots: params (header + attribute table), raw
	 * vertex/index buffers, control-point output, tessellation factor output.
	 */
	public record Tessellation(long prepass, int threads, int inVerts, int outVerts, int patchBytes, boolean quads, long partition, long winding,
							   int paramsSlot, int vertexSlot, int indexSlot, int controlPointSlot, int factorSlot, int stride, int[] attributes) {
	}

	/** Set by the creator right after construction (before the first draw); null for ordinary pipelines. */
	public Tessellation tessellation;

	/**
	 * Depth-only sibling (see {@link #createDepthPrepass}); when set, every draw first runs it, then this pipeline,
	 * which should test with equality allowed and not write depth, so each visible pixel is shaded once.
	 */
	public MetalPipeline depthPrepass;
	private final MetalDevice device;
	private final long vertexFunction;
	private final long fragmentFunction;
	private final List<VertexBuffer> vertexBuffers;
	private final List<Attribute> attributes;
	private final ColorTarget[] colorTargets;
	private final List<Variant> variants = new ArrayList<>(2);
	private boolean closed;

	public MetalPipeline(
		MetalDevice device,
		String name,
		String vertexMsl,
		String fragmentMsl,
		List<ShaderTranslator.Kind> bindings,
		List<VertexBuffer> vertexBuffers,
		List<Attribute> attributes,
		ColorTarget[] colorTargets,
		Raster raster
	) {
		this.device = device;
		this.name = name;
		this.bindings = List.copyOf(bindings);
		this.vertexBuffers = List.copyOf(vertexBuffers);
		this.attributes = List.copyOf(attributes);
		this.colorTargets = colorTargets.clone();
		this.raster = raster;
		long vsLib = device.compileLibrary(name + " (vertex)", vertexMsl);
		long fsLib = device.compileLibrary(name + " (fragment)", fragmentMsl);
		try {
			this.vertexFunction = MetalDevice.function(vsLib, "main0");
			this.fragmentFunction = MetalDevice.function(fsLib, "main0");
		} finally {
			release(vsLib);
			release(fsLib);
		}
	}

	/**
	 * Depth-only pipeline sharing this one's vertex function, so both rasterize bit-identical depths. Color writes are
	 * off; fragmentMsl should keep only what decides discards (outputs dead, the compiler strips the shading).
	 */
	public MetalPipeline createDepthPrepass(String fragmentMsl, Raster raster) {
		return new MetalPipeline(this, fragmentMsl, raster);
	}

	private MetalPipeline(MetalPipeline main, String fragmentMsl, Raster raster) {
		this.device = main.device;
		this.name = main.name + " (depth prepass)";
		this.bindings = main.bindings;
		this.vertexBuffers = main.vertexBuffers;
		this.attributes = main.attributes;
		this.colorTargets = new ColorTarget[0];
		this.raster = raster;
		this.vertexFunction = retain(main.vertexFunction);
		long fsLib = device.compileLibrary(name + " (fragment)", fragmentMsl);
		try {
			this.fragmentFunction = MetalDevice.function(fsLib, "main0");
		} finally {
			release(fsLib);
		}
	}

	/** Pipeline state matching the render pass attachments (PIXEL_INVALID for missing ones). */
	public long state(long[] colorFormats, long depthFormat) {
		for (Variant v : variants) {
			if (v.depthFormat == depthFormat && Arrays.equals(v.colorFormats, colorFormats)) {
				return v.state;
			}
		}
		long state = build(colorFormats, depthFormat);
		variants.add(new Variant(colorFormats.clone(), depthFormat, state));
		return state;
	}

	private long build(long[] colorFormats, long depthFormat) {
		long pool = poolPush();
		long desc = alloc("MTLRenderPipelineDescriptor");
		long vd = alloc("MTLVertexDescriptor");
		try {
			send(desc, sel("setLabel:"), nsString(name));
			send(desc, sel("setVertexFunction:"), vertexFunction);
			send(desc, sel("setFragmentFunction:"), fragmentFunction);

			long layouts = send(vd, sel("layouts"));
			for (VertexBuffer vb : vertexBuffers) {
				long layout = send(layouts, sel("objectAtIndexedSubscript:"), ShaderTranslator.vertexBufferIndex(vb.slot()));
				send(layout, sel("setStride:"), vb.stride());
				// stepRate < 0: constant (every vertex reads the same element), > 0: per instance, 0: per vertex.
				send(layout, sel("setStepFunction:"), vb.stepRate() < 0 ? 0L : vb.stepRate() > 0 ? 2L : 1L);
				send(layout, sel("setStepRate:"), vb.stepRate() < 0 ? 0L : Math.max(1, vb.stepRate()));
			}
			long attrs = send(vd, sel("attributes"));
			for (Attribute a : attributes) {
				long attr = send(attrs, sel("objectAtIndexedSubscript:"), a.location());
				send(attr, sel("setFormat:"), a.vertexFormat());
				send(attr, sel("setOffset:"), a.offset());
				send(attr, sel("setBufferIndex:"), ShaderTranslator.vertexBufferIndex(a.slot()));
			}
			send(desc, sel("setVertexDescriptor:"), vd);
			if (tessellation != null) {
				send(desc, sel("setTessellationPartitionMode:"), tessellation.partition());
				// Metal requires an odd maximum for fractional_odd and an even one otherwise.
				send(desc, sel("setMaxTessellationFactor:"), tessellation.partition() == 2 ? 63L : 64L);
				send(desc, sel("setTessellationFactorStepFunction:"), 1L); // per patch
				send(desc, sel("setTessellationFactorFormat:"), 0L); // half
				send(desc, sel("setTessellationControlPointIndexType:"), 0L); // none
				send(desc, sel("setTessellationOutputWindingOrder:"), tessellation.winding());
			}

			long colorAttachments = send(desc, sel("colorAttachments"));
			for (int i = 0; i < colorFormats.length; i++) {
				if (colorFormats[i] == Mtl.PIXEL_INVALID) {
					continue;
				}
				long ca = send(colorAttachments, sel("objectAtIndexedSubscript:"), i);
				send(ca, sel("setPixelFormat:"), colorFormats[i]);
				ColorTarget target = i < colorTargets.length ? colorTargets[i] : null;
				if (target == null) {
					send(ca, sel("setWriteMask:"), 0L);
					continue;
				}
				send(ca, sel("setWriteMask:"), target.writeMask());
				if (target.srcRgb() >= 0) {
					send(ca, sel("setBlendingEnabled:"), 1L);
					send(ca, sel("setSourceRGBBlendFactor:"), target.srcRgb());
					send(ca, sel("setDestinationRGBBlendFactor:"), target.dstRgb());
					send(ca, sel("setRgbBlendOperation:"), target.opRgb());
					send(ca, sel("setSourceAlphaBlendFactor:"), target.srcAlpha());
					send(ca, sel("setDestinationAlphaBlendFactor:"), target.dstAlpha());
					send(ca, sel("setAlphaBlendOperation:"), target.opAlpha());
				}
			}
			if (depthFormat != Mtl.PIXEL_INVALID) {
				send(desc, sel("setDepthAttachmentPixelFormat:"), depthFormat);
				if (Mtl.hasStencil(depthFormat)) {
					send(desc, sel("setStencilAttachmentPixelFormat:"), depthFormat);
				}
			}
			return device.newPipelineState(desc, name);
		} finally {
			release(vd);
			release(desc);
			poolPop(pool);
		}
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			if (depthPrepass != null) {
				depthPrepass.close();
			}
			variants.forEach(v -> release(v.state));
			variants.clear();
			release(vertexFunction);
			release(fragmentFunction);
		}
	}
}
