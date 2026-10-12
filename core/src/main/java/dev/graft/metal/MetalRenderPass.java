package dev.graft.metal;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static dev.graft.metal.ObjC.*;
import static java.lang.foreign.ValueLayout.*;

/** One MTLRenderCommandEncoder. Mirrors Minecraft's RenderPass: set pipeline, bind resources, draw. */
public final class MetalRenderPass {
	/** clearColor == null and NaN clearDepth mean "load existing contents". */
	/**
	 * clearColor value meaning "previous contents not needed" (MTLLoadActionDontCare): for passes that overwrite every
	 * pixel of the attachment. Saves loading it into tile memory. Only the first open uses it; resumes load.
	 */
	public static final float[] DONT_CARE = new float[0];

	public record Attachment(long texture, long pixelFormat, int level, float[] clearColor, double clearDepth) {
	}

	public static final int INDEX_U16 = 0;
	public static final int INDEX_U32 = 1;
	private static final long S_SET_PIPELINE = sel("setRenderPipelineState:");
	private static final long S_SET_DEPTH_STATE = sel("setDepthStencilState:");
	private static final long S_VERTEX_BUFFER = sel("setVertexBuffer:offset:atIndex:");
	private static final long S_FRAGMENT_BUFFER = sel("setFragmentBuffer:offset:atIndex:");
	private static final long S_VERTEX_TEXTURE = sel("setVertexTexture:atIndex:");
	private static final long S_FRAGMENT_TEXTURE = sel("setFragmentTexture:atIndex:");
	private static final long S_VERTEX_SAMPLER = sel("setVertexSamplerState:atIndex:");
	private static final long S_FRAGMENT_SAMPLER = sel("setFragmentSamplerState:atIndex:");
	private static final long S_VERTEX_BYTES = sel("setVertexBytes:length:atIndex:");
	private static final long S_FRAGMENT_BYTES = sel("setFragmentBytes:length:atIndex:");
	private static final long S_DRAW = sel("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:");
	private static final long S_DRAW_INDEXED = sel("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:");
	private static final long S_DRAW_INDIRECT = sel("drawPrimitives:indirectBuffer:indirectBufferOffset:");
	private static final long S_DRAW_INDEXED_INDIRECT = sel("drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:");
	private static final long S_VERTEX_BUFFER_OFFSET = sel("setVertexBufferOffset:atIndex:");
	private static final long S_FRAGMENT_BUFFER_OFFSET = sel("setFragmentBufferOffset:atIndex:");
	private static final long S_CULL = sel("setCullMode:");
	private static final long S_WINDING = sel("setFrontFacingWinding:");
	private static final long S_FILL = sel("setTriangleFillMode:");
	private static final long S_DEPTH_BIAS = sel("setDepthBias:slopeScale:clamp:");
	private static final long S_END_ENCODING = sel("endEncoding");
	private static final long S_WIDTH = sel("width");
	private static final long S_HEIGHT = sel("height");
	private static final long S_PASS_DESCRIPTOR = sel("renderPassDescriptor");
	private static final long S_COLOR_ATTACHMENTS = sel("colorAttachments");
	private static final long S_DEPTH_ATTACHMENT = sel("depthAttachment");
	private static final long S_STENCIL_ATTACHMENT = sel("stencilAttachment");
	private static final long S_AT_INDEX = sel("objectAtIndexedSubscript:");
	private static final long S_SET_TEXTURE = sel("setTexture:");
	private static final long S_SET_LEVEL = sel("setLevel:");
	private static final long S_SET_LOAD = sel("setLoadAction:");
	private static final long S_SET_STORE = sel("setStoreAction:");
	private static final long S_SET_CLEAR_DEPTH = sel("setClearDepth:");
	private static final long S_SET_LABEL = sel("setLabel:");
	private static final long S_PUSH_GROUP = sel("pushDebugGroup:");
	private static final long S_POP_GROUP = sel("popDebugGroup");
	private static final int METAL_BUFFERS = 31;

	private final MetalCommands commands;
	private long encoder;
	/** Attachments as last opened, with load actions, used to resume after suspend(). */
	private Attachment[] currentColors;
	private Attachment currentDepth;
	private float[] viewport;
	private int[] scissor;
	private int generation;
	private long[] colorFormats;
	private long depthFormat;
	private int outputWidth;
	private int outputHeight;
	private int areaX, areaY, areaW, areaH;
	private MetalPipeline pipeline;
	/** Encoder state that must survive retarget(): vertex buffers per slot and the last push constants. */
	private final long[][] vertexBuffers = new long[ShaderTranslator.MAX_VERTEX_BUFFERS][];
	private byte[] pushConstants;
	/** Buffer slots as last bound (long[]{buffer, offset} or byte[] inline data) and texture slots (long[]{texture, sampler}). */
	// Indexed by binding (pipeline kinds index), which can exceed 31 (shader packs use 40+ resources); Metal has 128
	// texture slots. Anything not recorded here would be lost while the encoder is lazily closed.
	private final Object[] boundBuffers = new Object[128];
	private final long[][] boundTextures = new long[128][];
	private final long[] boundSamplers = new long[16];
	/** Owner/version of data last bound with setBytesCached, per binding (cleared by any other write to it). */
	private final Object[] cachedOwner = new Object[128];
	private final long[] cachedVersion = new long[128];
	private long indexBuffer;
	private int indexType = INDEX_U16;
	private int debugGroups;
	/** Debug groups pushed while no encoder was open (skipped, popped as no-ops). */
	private int ignoredGroups;
	/** Attachments recorded by open() whose encoder has not been created yet (their load actions still apply). */
	private boolean pending;
	private String pendingLabel;
	/** Buffer-slot tables of slotsFor (ShaderTranslator.slots), kept to skip its synchronized lookup per bind. */
	private java.util.List<ShaderTranslator.Kind> slotsFor;
	private int[][] slotTable;

	/**
	 * What the open encoder currently has bound, to skip redundant Metal calls (-1 = unknown). Per Metal index and
	 * stage for buffers; textures/samplers are always set on both stages together (-1 also when they differ).
	 */
	private final long[] encVertexBuffer = new long[METAL_BUFFERS];
	private final long[] encVertexOffset = new long[METAL_BUFFERS];
	private final long[] encFragmentBuffer = new long[METAL_BUFFERS];
	private final long[] encFragmentOffset = new long[METAL_BUFFERS];
	private final long[] encTexture = new long[128];
	private final long[] encSampler = new long[16];
	private long encState, encDepthState, encCull, encFill, encBias;
	private boolean encWinding;
	private int encScissorX, encScissorY, encScissorW, encScissorH;

	private void resetEncoderState() {
		java.util.Arrays.fill(encVertexBuffer, -1L);
		java.util.Arrays.fill(encFragmentBuffer, -1L);
		java.util.Arrays.fill(encTexture, -1L);
		java.util.Arrays.fill(encSampler, -1L);
		encState = encDepthState = encCull = encFill = encBias = -1L;
		encWinding = false;
		encScissorW = -1;
	}

	MetalRenderPass(MetalCommands commands, Attachment[] colors, Attachment depth, int areaX, int areaY, int areaW, int areaH, String label) {
		this.commands = commands;
		open(colors, depth, label);
		this.areaX = areaX;
		this.areaY = areaY;
		this.areaW = areaW;
		this.areaH = areaH;
		disableScissor();
	}

	/**
	 * Switches this pass to new attachments (existing contents are loaded): ends the current Metal encoder and opens
	 * another. Used for GL-style framebuffer rebinding inside a pass (Iris). Pipeline and resource bindings must be set
	 * again afterwards.
	 */
	public void retarget(Attachment[] colors, Attachment depth) {
		retarget(colors, depth, null);
	}

	/** label: debug label for the new encoder (shows in Xcode/Instruments GPU traces). */
	public void retarget(Attachment[] colors, Attachment depth, String label) {
		if (encoder != 0L && sameAttachments(colors, depth)) {
			// Same attachments, loaded: keep the open encoder (ending and reopening would store and reload them all).
			closeDebugGroups();
			currentColors = colors.clone();
			currentDepth = depth;
			pipeline = null;
			viewport = null;
			Calls.setViewport(encoder, 0, 0, outputWidth, outputHeight);
			this.areaX = 0;
			this.areaY = 0;
			this.areaW = outputWidth;
			this.areaH = outputHeight;
			disableScissor();
			generation++;
			return;
		}
		if (encoder == 0L && pendingClears()) {
			enc(); // the previous attachments' requested clear must still happen
		}
		closeDebugGroups();
		if (encoder != 0L) {
			send(encoder, S_END_ENCODING);
			release(encoder);
			encoder = 0L;
		}
		pipeline = null;
		viewport = null;
		open(colors, depth, label);
		this.areaX = 0;
		this.areaY = 0;
		this.areaW = outputWidth;
		this.areaH = outputHeight;
		disableScissor(); // vertex buffers and push constants are replayed when the new encoder opens
		generation++;
	}

	/**
	 * True if colors/depth name exactly the open encoder's attachments (texture, format, level) and request no clear,
	 * and none of them has a deferred clear (a reopen would apply it first).
	 */
	private boolean sameAttachments(Attachment[] colors, Attachment depth) {
		if (colors.length != currentColors.length || (depth == null) != (currentDepth == null)) {
			return false;
		}
		for (int i = 0; i < colors.length; i++) {
			Attachment a = colors[i];
			Attachment b = currentColors[i];
			if ((a == null) != (b == null)) {
				return false;
			}
			if (a != null && (!sameTarget(a, b) || a.clearColor() != null && a.clearColor() != DONT_CARE || commands.hasDeferredClear(a.texture()))) {
				return false;
			}
		}
		return depth == null || sameTarget(depth, currentDepth) && Double.isNaN(depth.clearDepth()) && !commands.hasDeferredClear(depth.texture());
	}

	private static boolean sameTarget(Attachment a, Attachment b) {
		return a.texture() == b.texture() && a.pixelFormat() == b.pixelFormat() && a.level() == b.level();
	}

	/**
	 * A level-0 clear of textures this pass attaches (0 = not requested), taken over as the load action of the pass's
	 * next encoder instead of running as a separate clear pass (which the pass would then load back). Returns false,
	 * changing nothing, unless every requested texture is attached at level 0 only.
	 */
	boolean absorbClear(long colorTexture, int colorMips, float[] color, long depthTexture, long depthFormat, int depthMips, double depth) {
		if (colorTexture == 0L && depthTexture == 0L) {
			return false;
		}
		boolean found = false;
		if (colorTexture != 0L) {
			if (colorMips > 1 || color == null || color.length < 4 || commands.hasDeferredClear(colorTexture)) {
				return false;
			}
			for (Attachment a : currentColors) {
				if (a != null && a.texture() == colorTexture) {
					if (a.level() != 0) {
						return false;
					}
					found = true;
				}
			}
			if (!found || currentDepth != null && currentDepth.texture() == colorTexture) {
				return false;
			}
		}
		if (depthTexture != 0L) {
			if (depthMips > 1 || Double.isNaN(depth) || currentDepth == null || currentDepth.texture() != depthTexture || currentDepth.pixelFormat() != depthFormat || currentDepth.level() != 0
				|| commands.hasDeferredClear(depthTexture)) {
				return false;
			}
			for (Attachment a : currentColors) {
				if (a != null && a.texture() == depthTexture) {
					return false;
				}
			}
		}
		if (encoder != 0L) {
			suspend();
		}
		if (!pending) {
			// Suspended: the next encoder loads everything; it becomes a first open carrying the clear.
			currentColors = loaded(currentColors);
			currentDepth = loaded(currentDepth);
			pending = true;
			pendingLabel = null;
		}
		for (int i = 0; i < currentColors.length; i++) {
			Attachment a = currentColors[i];
			if (a != null && a.texture() == colorTexture) {
				currentColors[i] = new Attachment(a.texture(), a.pixelFormat(), 0, color.clone(), a.clearDepth());
			}
		}
		if (depthTexture != 0L) {
			currentDepth = new Attachment(currentDepth.texture(), currentDepth.pixelFormat(), 0, currentDepth.clearColor(), depth);
		}
		return true;
	}

	/**
	 * Ends the Metal encoder so blit/compute work can run mid-pass (GL allows that; Metal does not). The next use of
	 * this pass reopens it on the same attachments, loading their contents, and restores encoder state.
	 */
	public void suspend() {
		if (encoder == 0L && pendingClears()) {
			enc(); // a requested clear must still happen before whatever runs next
		}
		if (encoder != 0L) {
			closeDebugGroups();
			send(encoder, S_END_ENCODING);
			release(encoder);
			encoder = 0L;
		}
	}

	/** Bumped whenever the encoder was reopened; resource bindings made before must be redone. */
	public int generation() {
		return generation;
	}

	private long enc() {
		if (encoder == 0L) {
			MetalPipeline keep = pipeline;
			float[] vp = viewport;
			int[] sc = scissor;
			boolean first = pending;
			pending = false;
			// First open uses the recorded load actions (clears); reopening after suspend() loads the contents.
			createEncoder(first ? currentColors : loaded(currentColors), first ? currentDepth : loaded(currentDepth), first ? pendingLabel : null);
			if (vp != null) {
				setViewport(vp[0], vp[1], vp[2], vp[3]);
			}
			if (sc != null) {
				setScissor(sc[0], sc[1], sc[2], sc[3]);
			}
			for (int slot = 0; slot < vertexBuffers.length; slot++) {
				if (vertexBuffers[slot] != null) {
					bindVertexStage(vertexBuffers[slot][0], vertexBuffers[slot][1], ShaderTranslator.vertexBufferIndex(slot));
				}
			}
			if (pushConstants != null) {
				sendBytes(commands.stage(pushConstants), pushConstants.length, ShaderTranslator.PUSH_CONSTANTS);
			}
			if (keep != null) {
				setPipeline(keep);
			}
			replayBindings();
			generation++;
		}
		return encoder;
	}

	private static Attachment[] loaded(Attachment[] colors) {
		Attachment[] out = new Attachment[colors.length];
		for (int i = 0; i < colors.length; i++) {
			out[i] = loaded(colors[i]);
		}
		return out;
	}

	private static Attachment loaded(Attachment a) {
		return a == null ? null : new Attachment(a.texture(), a.pixelFormat(), a.level(), null, Double.NaN);
	}

	/** Viewport in attachment pixels (GL-convention memory layout, so GL viewport numbers apply unchanged). */
	public void setViewport(float x, float y, float w, float h) {
		viewport = new float[]{x, y, w, h};
		if (encoder != 0L) {
			Calls.setViewport(encoder, x, y, w, h);
		}
	}

	/**
	 * Records the attachments; the Metal encoder is created lazily on first use (enc()). A pass Iris retargets before
	 * drawing (every vanilla world pass) then never loads/stores the attachments it was created with, which on a
	 * tile-based GPU is a full read + write of each attachment.
	 */
	private void open(Attachment[] colors, Attachment depth, String label) {
		this.currentColors = colors.clone();
		this.currentDepth = depth;
		this.colorFormats = new long[colors.length];
		this.depthFormat = depth != null ? depth.pixelFormat() : Mtl.PIXEL_INVALID;
		long sizeSource = 0L;
		for (int i = 0; i < colors.length; i++) {
			if (colors[i] != null) {
				colorFormats[i] = colors[i].pixelFormat();
				sizeSource = colors[i].texture();
			}
		}
		if (sizeSource == 0L && depth != null) {
			sizeSource = depth.texture();
		}
		if (sizeSource != 0L) {
			this.outputWidth = (int)send(sizeSource, S_WIDTH);
			this.outputHeight = (int)send(sizeSource, S_HEIGHT);
		}
		this.pending = true;
		this.pendingLabel = label;
		if (EAGER) {
			enc();
		}
	}

	/** -Dgraft.eagerEncoders=true: open encoders immediately (old behavior), for A/B checks. */
	private static final boolean EAGER = Boolean.getBoolean("graft.eagerEncoders");

	/** True if the recorded, not yet opened attachments ask for a clear (which must still happen). */
	private boolean pendingClears() {
		if (!pending) {
			return false;
		}
		for (Attachment a : currentColors) {
			if (a != null && a.clearColor() != null && a.clearColor() != DONT_CARE) {
				return true;
			}
		}
		return currentDepth != null && !Double.isNaN(currentDepth.clearDepth());
	}

	/** True if texture is one of this pass's current attachments. */
	boolean attaches(long texture) {
		for (Attachment a : currentColors) {
			if (a != null && a.texture() == texture) {
				return true;
			}
		}
		return currentDepth != null && currentDepth.texture() == texture;
	}

	private void createEncoder(Attachment[] colors, Attachment depth, String label) {
		// Pending clears of attached textures become this encoder's load actions (level 0) or run first (other levels).
		colors = colors.clone();
		for (int i = 0; i < colors.length; i++) {
			Attachment a = colors[i];
			if (a == null) {
				continue;
			}
			if (a.level() != 0) {
				commands.flushClear(a.texture());
				continue;
			}
			float[] pending = commands.takeDeferredClear(a.texture());
			if (pending != null && (a.clearColor() == null || a.clearColor() == DONT_CARE)) {
				colors[i] = new Attachment(a.texture(), a.pixelFormat(), a.level(), pending, a.clearDepth());
			}
		}
		if (depth != null) {
			commands.flushClear(depth.texture());
		}
		this.colorFormats = new long[colors.length];
		this.depthFormat = depth != null ? depth.pixelFormat() : Mtl.PIXEL_INVALID;
		long pool = poolPush();
		try {
			long desc = send(cls("MTLRenderPassDescriptor"), S_PASS_DESCRIPTOR);
			long colorAttachments = send(desc, S_COLOR_ATTACHMENTS);
			long sizeSource = 0L;
			for (int i = 0; i < colors.length; i++) {
				Attachment a = colors[i];
				if (a == null) {
					continue;
				}
				colorFormats[i] = a.pixelFormat();
				long ca = send(colorAttachments, S_AT_INDEX, i);
				send(ca, S_SET_TEXTURE, a.texture());
				send(ca, S_SET_LEVEL, a.level());
				send(ca, S_SET_STORE, Mtl.STORE_STORE);
				if (a.clearColor() == DONT_CARE) {
					send(ca, S_SET_LOAD, Mtl.LOAD_DONT_CARE);
				} else if (a.clearColor() != null) {
					send(ca, S_SET_LOAD, Mtl.LOAD_CLEAR);
					float[] c = a.clearColor();
					Calls.setClearColor(ca, c[0], c[1], c[2], c[3]);
				} else {
					send(ca, S_SET_LOAD, Mtl.LOAD_LOAD);
				}
				sizeSource = a.texture();
			}
			if (depth != null) {
				configureDepth(desc, depth.texture(), depth.pixelFormat(), depth.level(), Double.isNaN(depth.clearDepth()) ? Mtl.LOAD_LOAD : Mtl.LOAD_CLEAR, depth.clearDepth());
				if (sizeSource == 0L) {
					sizeSource = depth.texture();
				}
			}
			this.outputWidth = (int)send(sizeSource, S_WIDTH);
			this.outputHeight = (int)send(sizeSource, S_HEIGHT);
			this.encoder = commands.openRenderEncoder(desc);
			resetEncoderState();
			if (label != null) {
				send(encoder, S_SET_LABEL, nsString(label));
			}
		} finally {
			poolPop(pool);
		}
		Calls.setViewport(encoder, 0, 0, outputWidth, outputHeight);
	}

	static void configureDepth(long passDescriptor, long texture, long pixelFormat, int level, long loadAction, double clearDepth) {
		long da = send(passDescriptor, S_DEPTH_ATTACHMENT);
		send(da, S_SET_TEXTURE, texture);
		send(da, S_SET_LEVEL, level);
		send(da, S_SET_LOAD, loadAction);
		send(da, S_SET_STORE, Mtl.STORE_STORE);
		if (loadAction == Mtl.LOAD_CLEAR) {
			sendD(da, S_SET_CLEAR_DEPTH, clearDepth);
		}
		if (Mtl.hasStencil(pixelFormat)) {
			long sa = send(passDescriptor, S_STENCIL_ATTACHMENT);
			send(sa, S_SET_TEXTURE, texture);
			send(sa, S_SET_LEVEL, level);
			send(sa, S_SET_LOAD, loadAction);
			send(sa, S_SET_STORE, Mtl.STORE_STORE);
		}
	}

	void end() {
		suspend();
	}

	public void pushDebugGroup(String label) {
		if (encoder == 0L) {
			ignoredGroups++;
			return;
		}
		long pool = poolPush();
		try {
			send(enc(), S_PUSH_GROUP, nsString(label));
			debugGroups++;
		} finally {
			poolPop(pool);
		}
	}

	/**
	 * Groups can't span encoders: close the open ones before ending this encoder. The caller still pops them later, so
	 * those pops are swallowed.
	 */
	private void closeDebugGroups() {
		while (debugGroups > 0) {
			send(enc(), S_POP_GROUP);
			debugGroups--;
			ignoredGroups++;
		}
	}

	public void popDebugGroup() {
		if (ignoredGroups > 0) {
			ignoredGroups--;
			return;
		}
		if (debugGroups == 0) {
			throw new IllegalStateException("Can't pop more debug groups than were pushed");
		}
		send(enc(), S_POP_GROUP);
		debugGroups--;
	}

	public MetalPipeline pipeline() {
		return pipeline;
	}

	public void setPipeline(MetalPipeline pipeline) {
		if (pipeline != this.pipeline) {
			java.util.Arrays.fill(cachedOwner, null);
		}
		this.pipeline = pipeline;
		if (encoder == 0L) {
			return; // applied when the encoder opens
		}
		MetalDevice device = commands.device();
		bindState(pipeline.state(colorFormats, depthFormat));
		MetalPipeline.Raster r = pipeline.raster;
		boolean depthTest = depthFormat != Mtl.PIXEL_INVALID && Mtl.hasDepth(depthFormat) && r.depthCompare() >= 0;
		bindDepthState(depthTest ? device.depthState(r.depthCompare(), r.depthWrite()) : device.depthState(7, false));
		bindCull(r.cull() ? 2L : 0L);
		if (!encWinding) {
			send(encoder, S_WINDING, 0L);
			encWinding = true;
		}
		long fill = r.wireframe() ? 1L : 0L;
		if (encFill != fill) {
			send(encoder, S_FILL, fill);
			encFill = fill;
		}
		// GL disables polygon offset only when both terms are zero.
		boolean bias = depthTest && (r.depthBiasConstant() != 0.0F || r.depthBiasSlope() != 0.0F);
		float biasConstant = bias ? r.depthBiasConstant() : 0.0F;
		float biasSlope = bias ? r.depthBiasSlope() : 0.0F;
		long biasKey = (long)Float.floatToRawIntBits(biasConstant) << 32 | Float.floatToRawIntBits(biasSlope) & 0xFFFFFFFFL;
		if (encBias != biasKey) {
			sendF3(encoder, S_DEPTH_BIAS, biasConstant, biasSlope, 0.0F);
			encBias = biasKey;
		}
	}

	private void bindState(long state) {
		if (encState != state) {
			send(encoder, S_SET_PIPELINE, state);
			encState = state;
		}
	}

	private void bindDepthState(long state) {
		if (encDepthState != state) {
			send(encoder, S_SET_DEPTH_STATE, state);
			encDepthState = state;
		}
	}

	private void bindCull(long mode) {
		if (encCull != mode) {
			send(encoder, S_CULL, mode);
			encCull = mode;
		}
	}

	private void bindVertexStage(long buffer, long offset, int index) {
		if (index < METAL_BUFFERS) {
			if (encVertexBuffer[index] == buffer && buffer != 0L) {
				if (encVertexOffset[index] != offset) {
					send(encoder, S_VERTEX_BUFFER_OFFSET, offset, index);
					encVertexOffset[index] = offset;
				}
				return;
			}
			encVertexBuffer[index] = buffer;
			encVertexOffset[index] = offset;
		}
		send(encoder, S_VERTEX_BUFFER, buffer, offset, index);
	}

	private void bindFragmentStage(long buffer, long offset, int index) {
		if (index < METAL_BUFFERS) {
			if (encFragmentBuffer[index] == buffer && buffer != 0L) {
				if (encFragmentOffset[index] != offset) {
					send(encoder, S_FRAGMENT_BUFFER_OFFSET, offset, index);
					encFragmentOffset[index] = offset;
				}
				return;
			}
			encFragmentBuffer[index] = buffer;
			encFragmentOffset[index] = offset;
		}
		send(encoder, S_FRAGMENT_BUFFER, buffer, offset, index);
	}

	/** setBytes on both stages (Metal copies the data during the call). */
	private void sendBytes(long address, long length, int index) {
		if (index < METAL_BUFFERS) {
			encVertexBuffer[index] = -1L;
			encFragmentBuffer[index] = -1L;
		}
		send(encoder, S_VERTEX_BYTES, address, length, index);
		send(encoder, S_FRAGMENT_BYTES, address, length, index);
	}

	private void bindTexture(long texture, int index) {
		if (index < encTexture.length) {
			if (encTexture[index] == texture) {
				return;
			}
			encTexture[index] = texture;
		}
		send(encoder, S_VERTEX_TEXTURE, texture, index);
		send(encoder, S_FRAGMENT_TEXTURE, texture, index);
	}

	private void bindSampler(long sampler, int index) {
		if (index < encSampler.length) {
			if (encSampler[index] == sampler) {
				return;
			}
			encSampler[index] = sampler;
		}
		send(encoder, S_VERTEX_SAMPLER, sampler, index);
		send(encoder, S_FRAGMENT_SAMPLER, sampler, index);
	}

	/** Binds a uniform buffer to both stages at the binding's slot. */
	public void setBuffer(int binding, long buffer, long offset) {
		if (binding < cachedOwner.length) {
			cachedOwner[binding] = null;
		}
		if (binding < boundBuffers.length) {
			if (boundBuffers[binding] instanceof long[] b) {
				b[0] = buffer;
				b[1] = offset;
			} else {
				boundBuffers[binding] = new long[]{buffer, offset};
			}
		}
		if (encoder == 0L) {
			return; // recorded above; replayed when the encoder opens
		}
		int index = buf(binding);
		bindVertexStage(buffer, offset, index);
		bindFragmentStage(buffer, offset, index);
	}

	/** Binds a texture (and sampler, if non-zero) to both stages at the binding's slot. */
	public void setTexture(int binding, long texture, long sampler) {
		commands.flushClear(texture); // a texture sampled with a pending clear must be cleared first
		if (binding < boundTextures.length) {
			if (boundTextures[binding] != null) {
				boundTextures[binding][0] = texture;
				boundTextures[binding][1] = sampler;
			} else {
				boundTextures[binding] = new long[]{texture, sampler};
			}
		}
		if (encoder == 0L) {
			return; // recorded above; replayed when the encoder opens
		}
		bindTexture(texture, binding);
		if (sampler != 0L) {
			bindSampler(sampler, smp(binding));
		}
	}

	/** Binds a sampler state alone (both stages), for shaders that pick samplers from a shared array. */
	public void setSampler(int index, long sampler) {
		boundSamplers[index] = sampler;
		if (encoder == 0L) {
			return; // recorded above; replayed when the encoder opens
		}
		bindSampler(sampler, index);
	}

	public void setPushConstants(ByteBuffer data) {
		if (pushConstants == null || pushConstants.length != data.remaining()) {
			pushConstants = new byte[data.remaining()];
		}
		data.get(data.position(), pushConstants);
		if (encoder == 0L) {
			return; // recorded above; replayed when the encoder opens
		}
		sendBytes(commands.stage(data), data.remaining(), ShaderTranslator.PUSH_CONSTANTS);
	}

	/** Small uniform data inline with the draw (both stages) at a buffer binding; larger data goes through scratch memory. */
	/**
	 * setBytes that does nothing if the same owner already bound the same version at this binding in this pass (the
	 * binding survives encoder switches: recorded bindings are replayed). Any other write to the binding or a
	 * pipeline change (buffer indices depend on it) forgets it.
	 */
	public void setBytesCached(int binding, ByteBuffer data, Object owner, long version) {
		if (binding < cachedOwner.length && cachedOwner[binding] == owner && cachedVersion[binding] == version) {
			return;
		}
		setBytes(binding, data);
		if (binding < cachedOwner.length) {
			cachedOwner[binding] = owner;
			cachedVersion[binding] = version;
		}
	}

	public void setBytes(int binding, ByteBuffer data) {
		if (binding < cachedOwner.length) {
			cachedOwner[binding] = null;
		}
		if (data.remaining() <= 4096) {
			if (binding < boundBuffers.length) {
				byte[] saved = boundBuffers[binding] instanceof byte[] old && old.length == data.remaining() ? old : new byte[data.remaining()];
				data.get(data.position(), saved);
				boundBuffers[binding] = saved;
			}
			if (encoder == 0L && binding < boundBuffers.length) {
				return; // recorded above; replayed when the encoder opens
			}
			enc();
			sendBytes(commands.stage(data), data.remaining(), buf(binding));
		} else {
			MetalCommands.Scratch s = commands.scratch(data.remaining() + 256L);
			long aligned = (s.offset() + 255) & ~255L;
			MemorySegment.ofAddress(s.address() + (aligned - s.offset())).reinterpret(data.remaining()).copyFrom(MemorySegment.ofBuffer(data));
			setBuffer(binding, s.buffer(), aligned);
		}
	}

	public void setVertexBuffer(int slot, long buffer, long offset) {
		if (slot < vertexBuffers.length) {
			if (vertexBuffers[slot] != null) {
				vertexBuffers[slot][0] = buffer;
				vertexBuffers[slot][1] = offset;
			} else {
				vertexBuffers[slot] = new long[]{buffer, offset};
			}
		}
		if (encoder == 0L) {
			return; // recorded above; replayed when the encoder opens
		}
		bindVertexStage(buffer, offset, ShaderTranslator.vertexBufferIndex(slot));
	}

	public void setIndexBuffer(long buffer, int type) {
		this.indexBuffer = buffer;
		this.indexType = type;
	}

	public void setScissor(int x, int y, int w, int h) {
		// Metal rejects scissors outside the attachment; Minecraft occasionally sends them.
		int x0 = Math.clamp(x, 0, outputWidth);
		int y0 = Math.clamp(y, 0, outputHeight);
		int x1 = Math.clamp((long)x + Math.max(0, w), x0, outputWidth);
		int y1 = Math.clamp((long)y + Math.max(0, h), y0, outputHeight);
		if (scissor == null) {
			scissor = new int[4];
		}
		scissor[0] = x;
		scissor[1] = y;
		scissor[2] = w;
		scissor[3] = h;
		if (encoder != 0L && (encScissorX != x0 || encScissorY != y0 || encScissorW != x1 - x0 || encScissorH != y1 - y0)) {
			Calls.setScissor(encoder, x0, y0, x1 - x0, y1 - y0);
			encScissorX = x0;
			encScissorY = y0;
			encScissorW = x1 - x0;
			encScissorH = y1 - y0;
		}
	}

	public void disableScissor() {
		setScissor(areaX, areaY, areaW, areaH);
	}

	/** Runs the draw through the pipeline's depth prepass first (when it has one), then for real. */
	private void withPrepass(Runnable draw) {
		if (!hasPrepass()) {
			draw.run();
			return;
		}
		beginPrepass();
		draw.run();
		endPrepass();
		draw.run();
	}

	private boolean hasPrepass() {
		return pipeline.depthPrepass != null && depthFormat != Mtl.PIXEL_INVALID && Mtl.hasDepth(depthFormat);
	}

	private void beginPrepass() {
		enc();
		MetalPipeline pre = pipeline.depthPrepass;
		bindState(pre.state(colorFormats, depthFormat));
		bindDepthState(commands.device().depthState(pre.raster.depthCompare(), true));
	}

	private void endPrepass() {
		bindState(pipeline.state(colorFormats, depthFormat));
		bindDepthState(commands.device().depthState(pipeline.raster.depthCompare(), pipeline.raster.depthWrite()));
	}

	private void requirePipeline() {
		if (pipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}
	}

	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		requirePipeline();
		if (indexCount <= 0 || instanceCount <= 0) {
			return;
		}
		if (pipeline.geometry != null) {
			drawGeometry(indexCount, instanceCount, firstIndex, baseVertex, firstInstance, indexType == INDEX_U16 ? 1 : 2);
			return;
		}
		if (pipeline.tessellation != null) {
			drawTessellated(indexCount, instanceCount, firstIndex, baseVertex, firstInstance, indexType == INDEX_U16 ? 1 : 2);
			return;
		}
		if (pipeline.raster.triangleFan()) {
			drawFanIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
			return;
		}
		long indexOffset = firstIndex * (indexType == INDEX_U16 ? 2L : 4L);
		if (hasPrepass()) {
			beginPrepass();
			send(enc(), S_DRAW_INDEXED, pipeline.raster.primitive(), indexCount, indexType, indexBuffer, indexOffset, instanceCount, baseVertex, firstInstance);
			endPrepass();
		}
		send(enc(), S_DRAW_INDEXED, pipeline.raster.primitive(), indexCount, indexType, indexBuffer, indexOffset, instanceCount, baseVertex, firstInstance);
	}

	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		requirePipeline();
		if (vertexCount <= 0 || instanceCount <= 0) {
			return;
		}
		if (pipeline.geometry != null) {
			drawGeometry(vertexCount, instanceCount, firstVertex, 0, firstInstance, 0);
			return;
		}
		if (pipeline.tessellation != null) {
			drawTessellated(vertexCount, instanceCount, firstVertex, 0, firstInstance, 0);
			return;
		}
		if (pipeline.raster.triangleFan()) {
			int triangles = vertexCount - 2;
			if (triangles <= 0) {
				return;
			}
			MetalCommands.Scratch s = commands.scratch(triangles * 12L);
			MemorySegment out = MemorySegment.ofAddress(s.address()).reinterpret(triangles * 12L);
			for (int t = 0; t < triangles; t++) {
				out.setAtIndex(JAVA_INT, t * 3L, firstVertex);
				out.setAtIndex(JAVA_INT, t * 3L + 1, firstVertex + t + 1);
				out.setAtIndex(JAVA_INT, t * 3L + 2, firstVertex + t + 2);
			}
			send(enc(), S_DRAW_INDEXED, Mtl.PRIM_TRIANGLE, triangles * 3L, (long)INDEX_U32, s.buffer(), s.offset(), instanceCount, 0L, firstInstance);
			return;
		}
		if (hasPrepass()) {
			beginPrepass();
			send(enc(), S_DRAW, pipeline.raster.primitive(), firstVertex, vertexCount, instanceCount, firstInstance);
			endPrepass();
		}
		send(enc(), S_DRAW, pipeline.raster.primitive(), firstVertex, vertexCount, instanceCount, firstInstance);
	}

	/**
	 * Metal has no triangle fans. Minecraft draws fans with a CPU-visible sequential index buffer, so the fan is rewritten
	 * into a triangle list on the CPU.
	 * ponytail: reads the index buffer from shared memory; a fan whose indices were written by a GPU copy earlier in the
	 * same submit would see stale data. Do the expansion in a compute shader if that ever matters.
	 */
	private void drawFanIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		int triangles = indexCount - 2;
		if (triangles <= 0) {
			return;
		}
		int indexSize = indexType == INDEX_U16 ? 2 : 4;
		MemorySegment in = MemorySegment.ofAddress(MetalDevice.contents(indexBuffer) + (long)firstIndex * indexSize).reinterpret((long)indexCount * indexSize);
		MetalCommands.Scratch s = commands.scratch(triangles * 12L);
		MemorySegment out = MemorySegment.ofAddress(s.address()).reinterpret(triangles * 12L);
		int first = readIndex(in, 0, indexSize);
		for (int t = 0; t < triangles; t++) {
			out.setAtIndex(JAVA_INT, t * 3L, first);
			out.setAtIndex(JAVA_INT, t * 3L + 1, readIndex(in, t + 1, indexSize));
			out.setAtIndex(JAVA_INT, t * 3L + 2, readIndex(in, t + 2, indexSize));
		}
		send(enc(), S_DRAW_INDEXED, Mtl.PRIM_TRIANGLE, triangles * 3L, (long)INDEX_U32, s.buffer(), s.offset(), instanceCount, baseVertex, firstInstance);
	}

	/** indexMode: 0 non-indexed, 1 uint16 indices, 2 uint32 indices. See MetalPipeline.Geometry. */
	private void drawGeometry(int count, int instanceCount, int first, int baseVertex, int firstInstance, int indexMode) {
		MetalPipeline.Geometry g = pipeline.geometry;
		long prims = count / g.inVerts();
		long[] vertices = vertexBuffers[0];
		if (prims == 0 || vertices == null) {
			return;
		}
		ByteBuffer params = ByteBuffer.allocate(16 + g.attributes().length * 4).order(java.nio.ByteOrder.nativeOrder());
		params.putInt(indexMode).putInt(first).putInt(baseVertex).putInt(g.stride());
		for (int a : g.attributes()) {
			params.putInt(a);
		}
		setBytes(g.paramsSlot(), params.flip());
		setBuffer(g.vertexSlot(), vertices[0], vertices[1]);
		setBuffer(g.indexSlot(), indexMode != 0 ? indexBuffer : vertices[0], 0L);
		send(enc(), S_DRAW, pipeline.raster.primitive(), 0L, prims * g.slotsPerPrim(), (long)instanceCount, (long)firstInstance);
	}

	/** Metal buffer index of a binding of the current pipeline (ShaderTranslator.bufferSlot). */
	private int buf(int binding) {
		int[][] t = slotTable(binding);
		if (t == null) {
			return binding;
		}
		int slot = t[0][binding];
		if (slot >= ShaderTranslator.PUSH_CONSTANTS) {
			throw new IllegalStateException("Too many buffers for Metal: binding " + binding + " would use buffer index " + slot);
		}
		return slot;
	}

	private int smp(int binding) {
		int[][] t = slotTable(binding);
		return t == null ? binding : t[1][binding];
	}

	/** Same as ShaderTranslator.bufferSlot/samplerSlot, memoized for the current pipeline's (immutable) binding list. */
	private int[][] slotTable(int binding) {
		java.util.List<ShaderTranslator.Kind> b = pipeline != null ? pipeline.bindings : null;
		if (b == null || binding >= b.size()) {
			return null;
		}
		if (b != slotsFor) {
			slotTable = ShaderTranslator.slots(b);
			slotsFor = b;
		}
		return slotTable;
	}

	/** Re-applies recorded resource bindings (to a reopened encoder). */
	private void replayBindings() {
		// Binds directly: going through setBuffer/setBytes would forget setBytesCached's owners.
		for (int i = 0; i < boundBuffers.length; i++) {
			if (boundBuffers[i] instanceof long[] b) {
				int index = buf(i);
				bindVertexStage(b[0], b[1], index);
				bindFragmentStage(b[0], b[1], index);
			} else if (boundBuffers[i] instanceof byte[] bytes) {
				sendBytes(commands.stage(bytes), bytes.length, buf(i));
			}
			if (boundTextures[i] != null) {
				setTexture(i, boundTextures[i][0], boundTextures[i][1]);
			}
		}
		for (int i = 0; i < boundSamplers.length; i++) {
			if (boundSamplers[i] != 0L) {
				setSampler(i, boundSamplers[i]);
			}
		}
	}

	private static final long S_SET_TESS_FACTORS = sel("setTessellationFactorBuffer:offset:instanceStride:");
	private static final long S_DRAW_PATCHES = sel("drawPatches:patchStart:patchCount:patchIndexBuffer:patchIndexBufferOffset:instanceCount:baseInstance:");

	/**
	 * Tessellation (MetalPipeline.Tessellation): a compute pre-pass runs the vertex + control stages per patch, writing
	 * control points and tessellation factors to scratch memory; then drawPatches runs Metal's tessellator and the
	 * evaluation stage. The pre-pass sees the same resources as the draw. One pre-pass per instance (gl_InstanceID).
	 */
	private void drawTessellated(int count, int instanceCount, int first, int baseVertex, int firstInstance, int indexMode) {
		MetalPipeline.Tessellation t = pipeline.tessellation;
		int patches = count / t.inVerts();
		long[] vertices = vertexBuffers[0];
		if (patches == 0 || vertices == null) {
			return;
		}
		for (int instance = 0; instance < instanceCount; instance++) {
			MetalCommands.Scratch cp = commands.scratch((long)patches * t.patchBytes() + 256);
			long cpOffset = (cp.offset() + 255) & ~255L;
			MetalCommands.Scratch tf = commands.scratch((long)patches * (t.quads() ? 12 : 8) + 256);
			long tfOffset = (tf.offset() + 255) & ~255L;
			ByteBuffer params = ByteBuffer.allocate(32 + t.attributes().length * 4).order(java.nio.ByteOrder.nativeOrder());
			params.putInt(indexMode).putInt(first).putInt(baseVertex).putInt(t.stride()).putInt(patches).putInt(firstInstance + instance).putInt(0).putInt(0);
			for (int a : t.attributes()) {
				params.putInt(a);
			}
			params.flip();

			// Pre-pass (suspends this render pass; the next enc() resumes it with bindings replayed).
			commands.setComputePipeline(t.prepass());
			for (int i = 0; i < boundBuffers.length; i++) {
				if (boundBuffers[i] instanceof long[] b) {
					commands.setComputeBuffer(buf(i), b[0], b[1]);
				} else if (boundBuffers[i] instanceof byte[] bytes) {
					commands.setComputeBytes(buf(i), ByteBuffer.wrap(bytes));
				}
				if (boundTextures[i] != null) {
					commands.setComputeTexture(i, boundTextures[i][0], smp(i), boundTextures[i][1]);
				}
			}
			for (int i = 0; i < boundSamplers.length; i++) {
				if (boundSamplers[i] != 0L) {
					commands.setComputeSampler(i, boundSamplers[i]);
				}
			}
			if (pushConstants != null) {
				commands.setComputeBytes(ShaderTranslator.PUSH_CONSTANTS, ByteBuffer.wrap(pushConstants));
			}
			commands.setComputeBytes(buf(t.paramsSlot()), params);
			commands.setComputeBuffer(buf(t.vertexSlot()), vertices[0], vertices[1]);
			commands.setComputeBuffer(buf(t.indexSlot()), indexMode != 0 ? indexBuffer : vertices[0], 0L);
			commands.setComputeBuffer(buf(t.controlPointSlot()), cp.buffer(), cpOffset);
			commands.setComputeBuffer(buf(t.factorSlot()), tf.buffer(), tfOffset);
			commands.dispatch((patches + t.threads() - 1) / t.threads(), 1, 1, t.threads(), 1, 1);

			setBuffer(t.controlPointSlot(), cp.buffer(), cpOffset);
			send(enc(), S_SET_TESS_FACTORS, tf.buffer(), tfOffset, 0L);
			send(enc(), S_DRAW_PATCHES, (long)t.outVerts(), 0L, (long)patches, 0L, 0L, 1L, (long)(firstInstance + instance));
		}
	}

	private static int readIndex(MemorySegment in, long i, int indexSize) {
		return indexSize == 2 ? Short.toUnsignedInt(in.getAtIndex(JAVA_SHORT_UNALIGNED, i)) : in.getAtIndex(JAVA_INT_UNALIGNED, i);
	}

	/** Each command is a MTLDrawIndexedPrimitivesIndirectArguments (same layout as VkDrawIndexedIndirectCommand). */
	public void drawIndexedIndirect(long buffer, long offset, int drawCount) {
		requirePipeline();
		if (pipeline.geometry != null || pipeline.tessellation != null) {
			// ponytail: geometry/tessellation needs the counts on the CPU, read from shared memory; commands written by a GPU
			// copy earlier in the same submit would be stale. Convert on the GPU (compute) if a mod does that.
			MemorySegment cmds = MemorySegment.ofAddress(MetalDevice.contents(buffer) + offset).reinterpret(drawCount * 20L);
			for (int i = 0; i < drawCount; i++) {
				drawIndexed(cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 5L), cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 5L + 1), cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 5L + 2),
					cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 5L + 3), cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 5L + 4));
			}
			return;
		}
		withPrepass(() -> {
			for (int i = 0; i < drawCount; i++) {
				send(enc(), S_DRAW_INDEXED_INDIRECT, pipeline.raster.primitive(), indexType, indexBuffer, 0L, buffer, offset + i * 20L);
			}
		});
	}

	/** Each command is a MTLDrawPrimitivesIndirectArguments (same layout as VkDrawIndirectCommand). */
	public void drawIndirect(long buffer, long offset, int drawCount) {
		requirePipeline();
		if (pipeline.geometry != null || pipeline.tessellation != null) {
			MemorySegment cmds = MemorySegment.ofAddress(MetalDevice.contents(buffer) + offset).reinterpret(drawCount * 16L);
			for (int i = 0; i < drawCount; i++) {
				draw(cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 4L), cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 4L + 1), cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 4L + 2),
					cmds.getAtIndex(JAVA_INT_UNALIGNED, i * 4L + 3));
			}
			return;
		}
		withPrepass(() -> {
			for (int i = 0; i < drawCount; i++) {
				send(enc(), S_DRAW_INDIRECT, pipeline.raster.primitive(), buffer, offset + i * 16L);
			}
		});
	}

	void drawInternalClear(long state, float[] color, float depth, int x, int y, int w, int h) {
		enc();
		bindState(state);
		bindDepthState(commands.device().depthState(7, true));
		bindCull(0L);
		encVertexBuffer[0] = -1L;
		encFragmentBuffer[0] = -1L;
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			MemorySegment d = arena.allocateFrom(JAVA_FLOAT, depth);
			MemorySegment c = arena.allocateFrom(JAVA_FLOAT, color);
			send(enc(), S_VERTEX_BYTES, d.address(), 4L, 0L);
			send(enc(), S_FRAGMENT_BYTES, c.address(), 16L, 0L);
		}
		setScissor(x, y, w, h);
		send(enc(), S_DRAW, Mtl.PRIM_TRIANGLE, 0L, 3L, 1L, 0L);
	}

	/** Fullscreen copy of src into this pass with a vertical flip, used for presenting. */
	void drawInternalPresent(long state, long sourceTexture, int copyWidth, int copyHeight) {
		enc();
		bindState(state);
		bindDepthState(commands.device().depthState(7, false));
		encTexture[0] = -1L;
		encVertexBuffer[0] = -1L;
		encFragmentBuffer[0] = -1L;
		send(enc(), S_FRAGMENT_TEXTURE, sourceTexture, 0L);
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			MemorySegment d = arena.allocateFrom(JAVA_FLOAT, 0.0F);
			MemorySegment h = arena.allocateFrom(JAVA_INT, copyHeight);
			send(enc(), S_VERTEX_BYTES, d.address(), 4L, 0L);
			send(enc(), S_FRAGMENT_BYTES, h.address(), 4L, 0L);
		}
		setScissor(0, 0, copyWidth, copyHeight);
		send(enc(), S_DRAW, Mtl.PRIM_TRIANGLE, 0L, 3L, 1L, 0L);
	}
}
