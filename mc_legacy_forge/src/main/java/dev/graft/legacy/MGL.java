package dev.graft.legacy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static dev.graft.legacy.Objc.*;

/**
 * OpenGL 1.x/2.x (the subset Minecraft 1.8.9 and Forge use) implemented on Metal. {@link GlTransformer} points every
 * matching org.lwjgl.opengl call here (same name and descriptor). Render targets keep GL's bottom-up row order (the vertex
 * shader flips y), so texture uploads, read-backs and viewports use GL coordinates unchanged; present() flips once.
 * Single-threaded, like the GL context it replaces.
 */
public final class MGL {
	static final Logger LOG = LogManager.getLogger("Graft");

	// ---------------------------------------------------------------- Metal objects

	private static final int BGRA8 = 80, DEPTH32F = 252;
	private static final long S_RENDER_PIPELINE = sel("setRenderPipelineState:"), S_DEPTH_STATE = sel("setDepthStencilState:"),
		S_VBYTES = sel("setVertexBytes:length:atIndex:"), S_FBYTES = sel("setFragmentBytes:length:atIndex:"),
		S_VBUF = sel("setVertexBuffer:offset:atIndex:"), S_FTEX = sel("setFragmentTexture:atIndex:"),
		S_FSAMP = sel("setFragmentSamplerState:atIndex:"), S_DRAW = sel("drawPrimitives:vertexStart:vertexCount:"),
		S_DRAW_IDX = sel("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:"), S_CULL = sel("setCullMode:"),
		S_BIAS = sel("setDepthBias:slopeScale:clamp:");

	private static final long device, queue, vfn, tvfn, ffn, presentPipe, white;
	private static long cmd, renc, benc, pool;
	private static int encFb = -1, encW, encH;
	private static boolean encDepth;

	private static long library() {
		String src;
		try (InputStream in = MGL.class.getResourceAsStream("/assets/graft/fixed.metal")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			for (int n; (n = in.read(b)) > 0; ) {
				out.write(b, 0, n);
			}
			src = new String(out.toByteArray(), "UTF-8");
		} catch (Exception e) {
			throw new IllegalStateException("Graft: missing fixed.metal", e);
		}
		ByteBuffer err = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		long lib = objc_msgSend(device, sel("newLibraryWithSource:options:error:"), nsString(src), 0L, Objc.address(err));
		if (lib == 0L) {
			throw new IllegalStateException("Graft: shader compile failed: " + string(send(err.getLong(0), "localizedDescription")));
		}
		return lib;
	}

	private static long attachment(long desc) {
		return send(send(desc, "colorAttachments"), "objectAtIndexedSubscript:", 0L);
	}

	private static long newPipeline(long desc) {
		ByteBuffer err = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		long archives = archiveArray;
		if (archives != 0L) {
			send(desc, "setBinaryArchives:", archives); // compiled on an earlier run: no compile now
		}
		long p = objc_msgSend(device, sel("newRenderPipelineStateWithDescriptor:error:"), desc, Objc.address(err));
		if (p != 0L && archives != 0L) {
			synchronized (ARCHIVE_LOCK) {
				if ((objc_msgSend(archive, sel("addRenderPipelineFunctionsWithDescriptor:error:"), desc, Objc.address(err)) & 0xFF) != 0L) { // BOOL
					archiveDirty = true;
					archiveAdded = System.nanoTime();
				}
			}
		}
		send(desc, "release");
		if (p == 0L) {
			LOG.error("Graft: pipeline failed: {}", string(send(err.getLong(0), "localizedDescription")));
		}
		return p;
	}

	// ---------------------------------------------------------------- pipeline binary archive (graft/shadercache)

	private static final Object ARCHIVE_LOCK = new Object();
	private static volatile long archive, archiveArray;
	private static java.io.File archiveFile;
	private static volatile boolean archiveDirty;
	private static volatile long archiveAdded;

	/**
	 * Opens the MTLBinaryArchive kept at this file (or starts an empty one): pipeline states found there are not compiled
	 * again, so a pack's second launch has no pipeline-compile hitches. -Dgraft.pipelineArchive=false turns it off.
	 */
	public static void openPipelineArchive(java.io.File f) {
		if (archive != 0L || "false".equals(System.getProperty("graft.pipelineArchive"))) {
			return;
		}
		try {
			long desc = send(send(cls("MTLBinaryArchiveDescriptor"), "alloc"), "init");
			ByteBuffer err = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
			long a = 0L;
			if (f.isFile()) {
				send(desc, "setUrl:", send(cls("NSURL"), "fileURLWithPath:", nsString(f.getAbsolutePath())));
				a = objc_msgSend(device, sel("newBinaryArchiveWithDescriptor:error:"), desc, Objc.address(err));
				if (a == 0L) { // made by another OS / GPU driver: start over
					LOG.info("Graft: pipeline archive {} not usable, starting a new one", f);
					send(desc, "setUrl:", 0L);
				}
			}
			if (a == 0L) {
				a = objc_msgSend(device, sel("newBinaryArchiveWithDescriptor:error:"), desc, Objc.address(err));
			}
			send(desc, "release");
			if (a != 0L) {
				archiveFile = f;
				archive = a;
				archiveArray = send(send(cls("NSArray"), "arrayWithObject:", a), "retain");
			}
		} catch (RuntimeException e) {
			LOG.warn("Graft: no pipeline archive: {}", e.toString());
		}
	}

	/** Writes the archive when new pipelines were added and none for a few seconds (a write is a few ms; rare). */
	public static void savePipelineArchive(boolean now) {
		if (!archiveDirty || !now && System.nanoTime() - archiveAdded < 5_000_000_000L) {
			return;
		}
		synchronized (ARCHIVE_LOCK) {
			archiveDirty = false;
			java.io.File tmp = new java.io.File(archiveFile.getPath() + ".tmp");
			archiveFile.getParentFile().mkdirs();
			ByteBuffer err = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
			long url = send(cls("NSURL"), "fileURLWithPath:", nsString(tmp.getAbsolutePath()));
			if ((objc_msgSend(archive, sel("serializeToURL:error:"), url, Objc.address(err)) & 0xFF) != 0L) { // BOOL
				if (!tmp.renameTo(archiveFile)) {
					tmp.delete();
				}
			} else {
				LOG.warn("Graft: could not save the pipeline archive: {}", string(send(err.getLong(0), "localizedDescription")));
			}
		}
	}

	private static long newTexture(int w, int h, int levels, int format) {
		long d = objc_msgSend(cls("MTLTextureDescriptor"), sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"), format, w, h, 0L);
		send(d, "setMipmapLevelCount:", levels);
		send(d, "setUsage:", 1L | 4L); // shader read | render target
		send(d, "setStorageMode:", 2L); // private
		return send(device, "newTextureWithDescriptor:", d);
	}

	private static long cmd() {
		if (cmd == 0L) {
			cmd = send(send(queue, "commandBuffer"), "retain");
		}
		return cmd;
	}

	private static void endEncoders() {
		if (renc != 0L) {
			send(renc, "endEncoding");
			renc = 0L;
			encFb = -1;
			if (DUMP_FRAME >= 0 && (frameNumber == DUMP_FRAME || frameNumber == DUMP_FRAME + 1)) {
				LOG.info("Graft: frame {} pass {} fb {} prog {} colors {} fmt {}", frameNumber, dumpIndex, lastPassFb,
					Programs.current != null ? Programs.current.id : 0, encColors, java.util.Arrays.toString(java.util.Arrays.copyOf(encFormats, encColors)));
				if (frameNumber == DUMP_FRAME + 1) {
					dumpPass();
				} else {
					dumpIndex++;
				}
			}
		}
		if (benc != 0L) {
			send(benc, "endEncoding");
			benc = 0L;
		}
	}

	private static long blit() {
		if (benc == 0L) {
			endEncoders();
			benc = send(cmd(), "blitCommandEncoder");
		}
		return benc;
	}

	/** Commits what has been recorded and waits for it (read-backs). */
	private static void finish() {
		endEncoders();
		if (cmd != 0L) {
			send(cmd, "commit");
			send(cmd, "waitUntilCompleted");
			send(cmd, "release");
			cmd = 0L;
		}
	}

	// ---------------------------------------------------------------- per-frame transient memory

	private static final int FRAMES = 2; // the CPU runs at most one frame ahead of the GPU (input latency)
	private static final long[] frameCmd = new long[FRAMES];
	@SuppressWarnings("unchecked")
	private static final ArrayList<Ring>[] rings = new ArrayList[] {new ArrayList<Ring>(), new ArrayList<Ring>(), new ArrayList<Ring>()};
	private static int frame, ringIdx;
	private static long rBuf, rOff, rAddr;
	private static ByteBuffer rView;

	private static final class Ring {
		final long buf, size, addr;
		final ByteBuffer view;
		long used;

		Ring(long size) {
			this.size = size;
			buf = objc_msgSend(device, sel("newBufferWithLength:options:"), size, 0L);
			addr = send(buf, "contents");
			view = Objc.view(addr, size);
		}
	}

	private static void alloc(long len) {
		ArrayList<Ring> rs = rings[frame];
		for (; ringIdx < rs.size(); ringIdx++) {
			Ring r = rs.get(ringIdx);
			long o = (r.used + 15) & ~15L;
			if (o + len <= r.size) {
				r.used = o + len;
				rBuf = r.buf;
				rOff = o;
				rView = r.view;
				rAddr = r.addr;
				return;
			}
		}
		Ring r = new Ring(Math.max(8L << 20, (len + 0xFFFF) & ~0xFFFFL));
		rs.add(r);
		r.used = len;
		rBuf = r.buf;
		rOff = 0;
		rView = r.view;
		rAddr = r.addr;
	}

	/** Copies native memory into transient GPU memory; result in rBuf/rOff. */
	private static void copyIn(long src, long len) {
		alloc(len);
		Objc.memcpy(rAddr + rOff, src, len);
	}

	// ---------------------------------------------------------------- presentation

	private static long layer;
	private static long defColor, defDepth;
	private static int defW, defH;
	private static boolean vsync = true;

	private static void syncDefault() {
		int w = Math.max(1, Win.getWidth()), h = Math.max(1, Win.getHeight());
		if (w == defW && h == defH) {
			return;
		}
		if (encFb == 0) {
			endEncoders();
		}
		if (defColor != 0L) {
			send(defColor, "release");
			send(defDepth, "release");
		}
		defColor = newTexture(w, h, 1, BGRA8);
		defDepth = newTexture(w, h, 1, DEPTH32F);
		defW = w;
		defH = h;
		if (layer != 0L) {
			Objc.drawableSize(layer, w, h);
		}
	}

	/** The window's CAMetalLayer (see {@link Win}). */
	static void setLayer(long l) {
		layer = l;
		send(l, "setDevice:", device);
		send(l, "setPixelFormat:", BGRA8);
		send(l, "setFramebufferOnly:", 1L);
		send(l, "setOpaque:", 1L);
		send(l, "setMaximumDrawableCount:", 2L);
		send(l, "setDisplaySyncEnabled:", vsync ? 1L : 0L);
		defW = defH = -1;
		syncDefault();
	}

	static void setVSync(boolean enabled) {
		vsync = enabled;
		if (layer != 0L) {
			send(layer, "setDisplaySyncEnabled:", enabled ? 1L : 0L);
		}
	}

	private static final int DUMP_FRAME = Integer.getInteger("graft.dumpFrame", -1);
	private static int frameNumber, dumpIndex, lastPassFb;

	/** Dev: saves every color target of the pass that just ended (frame graft.dumpFrame) to /tmp/graft-dump. */
	private static void dumpPass() {
		long[] tex = passColor.clone();
		int[] fmt = encFormats.clone();
		int n = encColors, pass = dumpIndex++;
		String prog = Programs.current != null ? String.valueOf(Programs.current.id) : "fixed";
		for (int k = 0; k < n; k++) {
			if (tex[k] == 0L || fmt[k] == 0) {
				continue;
			}
			int w = (int)send(tex[k], "width"), h = (int)send(tex[k], "height"), bpp = fmt[k] == 115 || fmt[k] == 110 ? 8 : fmt[k] == 125 ? 16 : 4;
			long len = (long)w * h * bpp;
			long buf = objc_msgSend(device, sel("newBufferWithLength:options:"), len, 0L);
			Objc.texToBuf(blit(), tex[k], 0, 0, 0, w, h, buf, 0, (long)w * bpp, len);
			finish();
			ByteBuffer px = Objc.view(send(buf, "contents"), len);
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = (y * w + x) * bpp;
					float r, g, b;
					switch (fmt[k]) {
						case 115: r = half(px.getShort(i)); g = half(px.getShort(i + 2)); b = half(px.getShort(i + 4)); break;
						case 125: r = px.getFloat(i); g = px.getFloat(i + 4); b = px.getFloat(i + 8); break;
						case 110: r = (px.getShort(i) & 0xFFFF) / 65535f; g = (px.getShort(i + 2) & 0xFFFF) / 65535f; b = (px.getShort(i + 4) & 0xFFFF) / 65535f; break;
						case 92: { int v = px.getInt(i); r = f11(v & 0x7FF); g = f11(v >>> 11 & 0x7FF); b = f10(v >>> 22); break; }
						case BGRA8: r = (px.get(i + 2) & 255) / 255f; g = (px.get(i + 1) & 255) / 255f; b = (px.get(i) & 255) / 255f; break;
						default: r = (px.get(i) & 255) / 255f; g = (px.get(i + 1) & 255) / 255f; b = (px.get(i + 2) & 255) / 255f;
					}
					img.setRGB(x, h - 1 - y, clamp8(r) << 16 | clamp8(g) << 8 | clamp8(b));
				}
			}
			try {
				java.io.File dir = new java.io.File("/tmp/graft-dump");
				dir.mkdirs();
				javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, String.format("%03d_p%s_c%d_f%d.png", pass, prog, k, fmt[k])));
			} catch (java.io.IOException e) {
				LOG.warn("dump failed", e);
			}
			send(buf, "release");
		}
	}

	private static int clamp8(float v) {
		return Math.max(0, Math.min(255, (int)(v * 255)));
	}

	private static float half(short h) {
		int e = h >> 10 & 31, m = h & 1023, sgn = h >> 15 & 1;
		float v = e == 0 ? m / 1024f / 16384f : e == 31 ? Float.POSITIVE_INFINITY : (1 + m / 1024f) * (float)Math.pow(2, e - 15);
		return sgn != 0 ? -v : v;
	}

	private static float f11(int v) {
		int e = v >> 6, m = v & 63;
		return e == 0 ? m / 64f / 16384f : (1 + m / 64f) * (float)Math.pow(2, e - 15);
	}

	private static float f10(int v) {
		int e = v >> 5, m = v & 31;
		return e == 0 ? m / 32f / 16384f : (1 + m / 32f) * (float)Math.pow(2, e - 15);
	}

	static void present() {
		flushBatch();
		if (layer == 0L) {
			return;
		}
		syncDefault();
		cmd();
		endEncoders();
		long drawable = send(layer, "nextDrawable");
		if (drawable != 0L) {
			long rpd = send(cls("MTLRenderPassDescriptor"), "renderPassDescriptor");
			long ca = attachment(rpd);
			send(ca, "setTexture:", send(drawable, "texture"));
			send(ca, "setLoadAction:", 0L);
			send(ca, "setStoreAction:", 1L);
			long e = send(cmd, "renderCommandEncoderWithDescriptor:", rpd);
			objc_msgSend(e, S_RENDER_PIPELINE, presentPipe);
			objc_msgSend(e, S_FTEX, defColor, 0L);
			objc_msgSend(e, S_DRAW, 3L, 0L, 3L);
			send(e, "endEncoding");
			send(cmd, "presentDrawable:", drawable);
		}
		send(cmd, "commit");
		frameCmd[frame] = cmd;
		cmd = 0L;
		frame = (frame + 1) % FRAMES;
		if (frameCmd[frame] != 0L) {
			send(frameCmd[frame], "waitUntilCompleted");
			send(frameCmd[frame], "release");
			frameCmd[frame] = 0L;
		}
		for (Ring r : rings[frame]) {
			r.used = 0;
		}
		ringIdx = 0;
		objc_autoreleasePoolPop(pool);
		pool = objc_autoreleasePoolPush();
		frameNumber++;
	}

	// ---------------------------------------------------------------- GL state

	private static final class State {
		boolean blend, alphaTest, depthTest, cull, fog, lighting, light0, light1, polyOffset, scissorTest;
		boolean[] tex2d = new boolean[UNITS];
		int srcRgb = 1, dstRgb = 0, srcA = 1, dstA = 0, blendEq = 0x8006, colorMask = 15;
		int alphaFunc = 0x207, depthFunc = 0x201, cullFace = 0x405, fogMode = 0x800;
		boolean depthMask = true;
		float alphaRef, polyFactor, polyUnits, fogStart, fogEnd = 1, fogDensity = 1;
		float[] fogColor = new float[4], l0dir = {0, 0, 1, 0}, l1dir = {0, 0, 1, 0}, l0diff = {1, 1, 1, 1}, l1diff = new float[4],
			l0amb = new float[4], l1amb = new float[4], modelAmb = {0.2f, 0.2f, 0.2f, 1};
		int[] env = {0x2100, 0x2100, 0x2100, 0x2100}, comb = {0x2100, 0x2100, 0x2100, 0x2100};
		float[][] envColor = new float[UNITS][4];

		State copy() {
			State s = new State();
			s.blend = blend; s.alphaTest = alphaTest; s.depthTest = depthTest; s.cull = cull; s.fog = fog;
			s.lighting = lighting; s.light0 = light0; s.light1 = light1; s.polyOffset = polyOffset; s.scissorTest = scissorTest;
			s.tex2d = tex2d.clone();
			s.srcRgb = srcRgb; s.dstRgb = dstRgb; s.srcA = srcA; s.dstA = dstA; s.blendEq = blendEq; s.colorMask = colorMask;
			s.alphaFunc = alphaFunc; s.depthFunc = depthFunc; s.cullFace = cullFace; s.fogMode = fogMode; s.depthMask = depthMask;
			s.alphaRef = alphaRef; s.polyFactor = polyFactor; s.polyUnits = polyUnits;
			s.fogStart = fogStart; s.fogEnd = fogEnd; s.fogDensity = fogDensity;
			s.fogColor = fogColor.clone(); s.l0dir = l0dir.clone(); s.l1dir = l1dir.clone(); s.l0diff = l0diff.clone();
			s.l1diff = l1diff.clone(); s.l0amb = l0amb.clone(); s.l1amb = l1amb.clone(); s.modelAmb = modelAmb.clone();
			s.env = env.clone(); s.comb = comb.clone();
			s.envColor = new float[UNITS][];
			for (int i = 0; i < UNITS; i++) {
				s.envColor[i] = envColor[i].clone();
			}
			return s;
		}
	}

	private static State st = new State();
	private static final ArrayList<State> attribStack = new ArrayList<State>();
	private static int unit, clientUnit;

	/** The active unit for fixed-function state (units above 3 only bind textures for shader programs). */
	private static int fx() {
		return Math.min(unit, UNITS - 1);
	}
	private static final int UNITS = 4, BIND_UNITS = 24; // fixed-function units / texture binding units (shader packs: 0-15, colortex8-15 on 16-23)
	private static final int[] bound = new int[BIND_UNITS];
	private static final float[] color = {1, 1, 1, 1}, normal = {0, 0, 1}, tc0 = new float[2], tc1 = new float[2];
	private static int vx, vy, vw = 1, vh = 1, sx, sy, sw, sh;
	private static double clearR, clearG, clearB, clearA;
	private static double clearDepth = 1.0;

	// ---------------------------------------------------------------- matrices (column-major)

	private static final float[][] mvStack = new float[64][16], prStack = new float[16][16];
	private static final float[][][] texStack = new float[UNITS][16][16];
	private static int mvTop, prTop;
	private static final int[] texTop = new int[UNITS];
	private static int matrixMode = 0x1700;

	static {
		identity(mvStack[0]);
		identity(prStack[0]);
		identity(texStack[0][0]);
		for (int u = 1; u < UNITS; u++) {
			identity(texStack[u][0]);
		}
	}

	private static void identity(float[] m) {
		for (int i = 0; i < 16; i++) {
			m[i] = i % 5 == 0 ? 1 : 0;
		}
	}

	private static float[] mat() {
		switch (matrixMode) {
			case 0x1701: return prStack[prTop];
			case 0x1702: return texStack[fx()][texTop[fx()]];
			default: return mvStack[mvTop];
		}
	}

	private static final float[] tmp = new float[16], tmp2 = new float[16];

	/** m = a * b */
	private static void mul(float[] m, float[] a, float[] b) {
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				tmp[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
			}
		}
		System.arraycopy(tmp, 0, m, 0, 16);
	}

	private static void multCur(float[] b) {
		float[] m = mat();
		mul(m, m, b);
	}

	// ---------------------------------------------------------------- display lists

	private interface Op {
		void run();
	}

	private static final class DList {
		final ArrayList<Op> ops = new ArrayList<Op>();
		ByteBuffer data;
		long buf;
	}

	private static final HashMap<Integer, DList> lists = new HashMap<Integer, DList>();
	private static int nextList = 1;
	private static DList rec;
	private static int recId;

	private static boolean record(Op op) {
		if (batchCount > 0) {
			flushBatch();
		}
		if (rec == null) {
			return false;
		}
		rec.ops.add(op);
		return true;
	}

	// ---------------------------------------------------------------- textures

	private static final class Tex {
		long mtl;
		int w, h, levels, format = BGRA8;
		int minF = 0x2702, magF = 0x2601, wrapS = 0x2901, wrapT = 0x2901, maxLevel = 1000, compareMode, compareFunc = 0x203, aniso = 1;
		long sampler, compareSampler;
	}

	/** GL internal format -> Metal pixel format (colors round up to 4 channels where Metal has no 3-channel format). */
	private static int pixelFormat(int internal) {
		switch (internal) {
			case 0x1902: case 0x81A5: case 0x81A6: case 0x81A7: case 0x8CAC: case 0x88F0: return DEPTH32F; // depth formats
			case 0x805B: case 0x8054: return 110; // RGBA16, RGB16 -> RGBA16Unorm
			case 0x881A: case 0x881B: return 115; // RGBA16F, RGB16F
			case 0x8814: case 0x8815: return 125; // RGBA32F, RGB32F
			case 0x8229: case 0x1903: return 10; // R8 -> R8Unorm
			case 0x822A: return 20; // R16
			case 0x822D: return 25; // R16F
			case 0x822E: return 55; // R32F
			case 0x822B: return 30; // RG8
			case 0x822C: return 60; // RG16
			case 0x822F: return 65; // RG16F
			case 0x8230: return 105; // RG32F
			case 0x8059: return 90; // RGB10_A2
			case 0x8C3A: return 92; // R11F_G11F_B10F
			default: return BGRA8; // RGBA, RGBA8, RGB, RGB8, ...
		}
	}

	private static final HashMap<Integer, Tex> textures = new HashMap<Integer, Tex>();
	private static final HashMap<Integer, Long> samplers = new HashMap<Integer, Long>();
	private static int nextTex = 1, proxyW, proxyH;

	private static Tex tex(int id) {
		Tex t = textures.get(id);
		if (t == null && id != 0) {
			textures.put(id, t = new Tex());
		}
		return t;
	}

	private static long sampler(Tex t) {
		return t.sampler != 0L ? t.sampler : (t.sampler = sampler(t, false));
	}

	/** Sampler for a sampler2DShadow (compare function from GL_TEXTURE_COMPARE_FUNC, LEQUAL by default). */
	private static long compareSampler(Tex t) {
		return t.compareSampler != 0L ? t.compareSampler : (t.compareSampler = sampler(t, true));
	}

	private static long sampler(Tex t, boolean compare) {
		int key = (t.minF >> 8 & 1) << 2 | t.minF & 3 | (t.magF & 1) << 4 | (int)wrapCode(t.wrapS) << 8 | (int)wrapCode(t.wrapT) << 12
			| (compare ? 1 << 16 | (t.compareFunc & 7) << 17 : 0) | (31 - Integer.numberOfLeadingZeros(t.aniso)) << 20;
		Long s = samplers.get(key);
		if (s == null) {
			long d = send(send(cls("MTLSamplerDescriptor"), "alloc"), "init");
			boolean minLinear = t.minF == 0x2601 || t.minF == 0x2701 || t.minF == 0x2703;
			long mip = t.minF == 0x2700 || t.minF == 0x2701 ? 1L : t.minF == 0x2702 || t.minF == 0x2703 ? 2L : 0L;
			send(d, "setMinFilter:", minLinear ? 1L : 0L);
			send(d, "setMagFilter:", t.magF == 0x2601 ? 1L : 0L);
			send(d, "setMipFilter:", mip);
			send(d, "setSAddressMode:", wrapCode(t.wrapS));
			send(d, "setTAddressMode:", wrapCode(t.wrapT));
			if (t.aniso > 1) {
				send(d, "setMaxAnisotropy:", (long)t.aniso);
			}
			if (compare) {
				send(d, "setCompareFunction:", t.compareFunc & 7);
			}
			samplers.put(key, s = send(device, "newSamplerStateWithDescriptor:", d));
			send(d, "release");
		}
		return s;
	}

	private static long wrapCode(int glWrap) {
		return glWrap == 0x2901 ? 2L : 0L; // repeat, else clamp to edge
	}

	private static int mipCount(int w, int h) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
	}

	/** Uploads BGRA (or RGBA when swizzle) pixels at a native address through transient memory and a blit. */
	private static void upload(long mtl, int level, int x, int y, int w, int h, long src, boolean swizzle) {
		long len = (long)w * h * 4;
		copyIn(src, len);
		if (swizzle) {
			for (long i = rOff; i < rOff + len; i += 4) {
				byte r = rView.get((int)i);
				rView.put((int)i, rView.get((int)i + 2));
				rView.put((int)i + 2, r);
			}
		}
		Objc.bufToTex(blit(), rBuf, rOff, w * 4L, len, w, h, mtl, level, x, y);
	}

	static long address(Buffer b) {
		if (b == null) {
			return 0L;
		}
		int elem = b instanceof ByteBuffer ? 1 : b instanceof ShortBuffer ? 2 : b instanceof DoubleBuffer || b instanceof LongBuffer ? 8 : 4;
		return Objc.address(b) + (long)b.position() * elem;
	}

	private static void texImage(int target, int level, int internal, int w, int h, int format, int type, Buffer pixels) {
		flushBatch();
		if (target == 0x8064) { // GL_PROXY_TEXTURE_2D: Minecraft probes the max texture size
			proxyW = w <= 16384 ? w : 0;
			proxyH = h <= 16384 ? h : 0;
			return;
		}
		Tex t = tex(bound[unit]);
		if (t == null || w <= 0 || h <= 0) {
			return;
		}
		if (level == 0) {
			int levels = t.maxLevel < 1000 ? Math.min(t.maxLevel + 1, mipCount(w, h)) : 1, pf = pixelFormat(internal);
			if (t.mtl == 0L || t.w != w || t.h != h || t.levels != levels || t.format != pf) {
				if (t.mtl != 0L) {
					send(t.mtl, "release");
				}
				t.format = pf;
				t.mtl = newTexture(w, h, levels, pf);
				t.w = w;
				t.h = h;
				t.levels = levels;
			}
		}
		if (pixels != null) {
			texSubImage(level, 0, 0, w, h, format, type, pixels);
		}
	}

	private static void texSubImage(int level, int x, int y, int w, int h, int format, int type, Buffer pixels) {
		flushBatch();
		Tex t = tex(bound[unit]);
		if (t == null || t.mtl == 0L || level >= t.levels || pixels == null || w <= 0 || h <= 0) {
			return;
		}
		if (t.format != BGRA8 || type != 0x8367 && type != 0x1401 || format != 0x80E1 && format != 0x1908 && format != 0x1907) {
			warnOnce("texture upload format 0x" + Integer.toHexString(format) + "/0x" + Integer.toHexString(type) + " into " + t.format);
			return;
		}
		long src = address(pixels);
		if (format == 0x1907) { // RGB bytes (pack noise textures): expand to RGBA
			ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4);
			ByteBuffer rgb = Objc.wrap(src, (long)w * h * 3);
			for (int i = 0, n = w * h; i < n; i++) {
				rgba.put(i * 4, rgb.get(i * 3)).put(i * 4 + 1, rgb.get(i * 3 + 1)).put(i * 4 + 2, rgb.get(i * 3 + 2)).put(i * 4 + 3, (byte)-1);
			}
			upload(t.mtl, level, x, y, w, h, Objc.address(rgba), true);
			return;
		}
		upload(t.mtl, level, x, y, w, h, src, format == 0x1908);
	}

	/** Blocking read of a texture region (GL row order) into dst. */
	private static void readback(long mtl, int level, int x, int y, int w, int h, long dst, boolean swizzle) {
		flushBatch();
		if (mtl == 0L || w <= 0 || h <= 0) {
			return;
		}
		long len = (long)w * h * 4;
		long buf = objc_msgSend(device, sel("newBufferWithLength:options:"), len, 0L);
		Objc.texToBuf(blit(), mtl, level, x, y, w, h, buf, 0, w * 4L, len);
		finish();
		ByteBuffer src = Objc.wrap(send(buf, "contents"), len);
		ByteBuffer out = Objc.wrap(dst, len);
		out.put(src);
		if (swizzle) {
			for (int i = 0; i < len; i += 4) {
				byte r = out.get(i);
				out.put(i, out.get(i + 2));
				out.put(i + 2, r);
			}
		}
		send(buf, "release");
	}

	// ---------------------------------------------------------------- buffers, framebuffers

	private static final class Vbo {
		long buf, cpu;
	}

	private static final class Fbo {
		final int[] color = new int[16];
		int depth, depthTex, read;
		int[] draw = {0}; // glDrawBuffers: attachment index per fragment output, -1 = GL_NONE
	}

	private static final HashMap<Integer, Vbo> vbos = new HashMap<Integer, Vbo>();
	private static final HashMap<Integer, Fbo> fbos = new HashMap<Integer, Fbo>();
	private static final HashMap<Integer, Long> renderbuffers = new HashMap<Integer, Long>();
	private static int nextBuffer = 1, nextFbo = 1, nextRb = 1, arrayBuffer, drawFb, readFb, boundRb;

	/** The read attachment (glReadBuffer) of a framebuffer. */
	private static long fbColor(int fb) {
		if (fb == 0) {
			syncDefault();
			return defColor;
		}
		Fbo f = fbos.get(fb);
		Tex t = f == null ? null : textures.get(f.color[f.read]);
		return t == null ? 0L : t.mtl;
	}

	private static long fbDepth(int fb) {
		if (fb == 0) {
			syncDefault();
			return defDepth;
		}
		Fbo f = fbos.get(fb);
		if (f == null) {
			return 0L;
		}
		if (f.depthTex != 0) {
			Tex t = textures.get(f.depthTex);
			return t == null ? 0L : t.mtl;
		}
		Long d = renderbuffers.get(f.depth);
		return d == null ? 0L : d;
	}

	private static final long[] passColor = new long[8];
	private static final int[] encFormats = new int[8];
	private static int encColors;

	/** Starts a render pass on the draw framebuffer, clearing what is asked. Returns false if it has no color. */
	private static boolean beginPass(boolean clearC, boolean clearD) {
		endEncoders();
		if (drawFb == 0) {
			syncDefault(); // may recreate the default targets (window resized): before reading them
		}
		long depth = fbDepth(drawFb), size = 0L;
		encColors = 0;
		if (drawFb == 0) {
			passColor[0] = defColor;
			encFormats[0] = BGRA8;
			encColors = 1;
		} else {
			Fbo f = fbos.get(drawFb);
			if (f != null) {
				for (int k = 0; k < f.draw.length && k < 8; k++) {
					Tex t = f.draw[k] >= 0 ? textures.get(f.color[f.draw[k]]) : null;
					passColor[k] = t != null ? t.mtl : 0L;
					encFormats[k] = t != null && t.mtl != 0L ? t.format : 0;
					encColors = k + 1;
				}
			}
		}
		for (int k = 0; k < encColors && size == 0L; k++) {
			size = passColor[k];
		}
		if (size == 0L) {
			size = depth;
		}
		if (size == 0L) {
			return false;
		}
		long rpd = send(cls("MTLRenderPassDescriptor"), "renderPassDescriptor");
		long cas = send(rpd, "colorAttachments");
		for (int k = 0; k < encColors; k++) {
			if (passColor[k] == 0L) {
				continue;
			}
			long ca = send(cas, "objectAtIndexedSubscript:", k);
			send(ca, "setTexture:", passColor[k]);
			send(ca, "setLoadAction:", clearC ? 2L : 1L);
			send(ca, "setStoreAction:", 1L);
			if (clearC) {
				float[] c = clearColors != null && k < clearColors.length ? clearColors[k] : null;
				if (c != null) {
					Objc.clearColor(ca, c[0], c[1], c[2], c[3]);
				} else {
					Objc.clearColor(ca, clearR, clearG, clearB, clearA);
				}
			}
		}
		if (depth != 0L) {
			long da = send(rpd, "depthAttachment");
			send(da, "setTexture:", depth);
			send(da, "setLoadAction:", clearD ? 2L : 1L);
			send(da, "setStoreAction:", 1L);
			if (clearD) {
				objc_msgSend(da, sel("setClearDepth:"), clearDepth);
			}
		}
		renc = send(cmd(), "renderCommandEncoderWithDescriptor:", rpd);
		encGen++;
		encFb = drawFb;
		lastPassFb = drawFb;
		encW = (int)send(size, "width");
		encH = (int)send(size, "height");
		encDepth = depth != 0L;
		send(renc, "setFrontFacingWinding:", 0L); // GL's CCW front faces are clockwise after the y flip
		curPipe = curDepthState = 0L;
		java.util.Arrays.fill(curTex, -1L);
		java.util.Arrays.fill(curSamp, -1L);
		java.util.Arrays.fill(curBuf, -1L);
		java.util.Arrays.fill(curProgTex, -1L); // a new encoder starts with nothing bound
		java.util.Arrays.fill(curProgSamp, -1L);
		curCull = -1;
		curVx = curVy = curVw = curVh = -1;
		curSx = curSy = curSw = curSh = -1;
		curBiasU = curBiasF = Float.NaN;
		return true;
	}

	// ---------------------------------------------------------------- vertex arrays and draws

	// Vertex attribute slots: 0-4 are GL's fixed arrays (and gl_Vertex/gl_Color/... locations), 5-15 generic attributes
	// (glVertexAttribPointer; OptiFine binds mc_Entity 10, mc_midTexCoord 11, at_tangent 12).
	static final int POS = 0, COL = 1, TEX0 = 2, TEX1 = 3, NRM = 4, SLOTS = 16;
	private static final boolean[] arrOn = new boolean[SLOTS], arrNorm = new boolean[SLOTS];
	private static final int[] arrSize = new int[SLOTS], arrType = new int[SLOTS], arrStride = new int[SLOTS], arrVbo = new int[SLOTS];
	private static final long[] arrAddr = new long[SLOTS];

	static {
		java.util.Arrays.fill(arrSize, 4);
		java.util.Arrays.fill(arrType, 0x1406);
	}

	// The draw being issued.
	private static final long[] dBuf = new long[SLOTS], dOff = new long[SLOTS];
	private static final int[] dFmt = new int[SLOTS], dStride = new int[SLOTS];

	private static int typeBytes(int type) {
		switch (type) {
			case 0x1400: case 0x1401: return 1;
			case 0x1402: case 0x1403: return 2;
			case 0x140A: return 8;
			default: return 4;
		}
	}

	static int metalFormat(int attr, int size, int type) {
		if (attr >= 5 && !arrNorm[attr]) { // generic, not normalized: integers converted to float
			switch (type) {
				case 0x1401: return size == 4 ? 3 : size == 3 ? 2 : size == 2 ? 1 : 45;
				case 0x1400: return size == 4 ? 6 : size == 3 ? 5 : size == 2 ? 4 : 46;
				case 0x1402: return size == 4 ? 18 : size == 3 ? 17 : size == 2 ? 16 : 50;
				case 0x1403: return size == 4 ? 15 : size == 3 ? 14 : size == 2 ? 13 : 49;
				default:
			}
		} else if (attr >= 5 && type == 0x1402) {
			return size == 4 ? 24 : size == 3 ? 23 : size == 2 ? 22 : 52;
		}
		switch (type) {
			case 0x1406: return 28 + size - 1; // float..float4
			case 0x1401: return size == 4 ? 9 : size == 3 ? 8 : size == 2 ? 7 : 47; // unsigned byte, normalized
			case 0x1400: return size == 4 ? 12 : size == 3 ? 11 : size == 2 ? 10 : 48; // byte, normalized
			case 0x1402:
				if (attr == NRM) {
					return size == 4 ? 24 : 23;
				}
				return size == 4 ? 18 : size == 3 ? 17 : size == 2 ? 16 : 50; // short, integer values as float
			case 0x1403: return size == 4 ? 15 : size == 3 ? 14 : size == 2 ? 13 : 49;
			default: return 0;
		}
	}

	private static void setPointer(int attr, int size, int type, int stride, long addr, int vbo) {
		arrSize[attr] = size;
		arrType[attr] = type;
		arrStride[attr] = stride != 0 ? stride : size * typeBytes(type);
		arrAddr[attr] = addr;
		arrVbo[attr] = vbo;
	}

	/** Resolves enabled arrays for vertices [first, first+count) into dBuf/dOff (client memory copied, VBOs bound). */
	private static boolean gatherArrays(int first, int count, DList into) {
		long min = Long.MAX_VALUE, max = 0;
		for (int i = 0; i < SLOTS; i++) {
			dFmt[i] = 0;
			if (!arrOn[i]) {
				continue;
			}
			int f = metalFormat(i, arrSize[i], arrType[i]);
			if (f == 0) {
				warnOnce("vertex type 0x" + Integer.toHexString(arrType[i]));
				continue;
			}
			dFmt[i] = f;
			dStride[i] = arrStride[i];
			long start = arrAddr[i] + (long)first * arrStride[i], end = start + (long)(count - 1) * arrStride[i] + arrSize[i] * typeBytes(arrType[i]);
			if (arrVbo[i] != 0) {
				Vbo v = vbos.get(arrVbo[i]);
				if (v == null || v.buf == 0L) {
					return false;
				}
				if (into == null) {
					dBuf[i] = v.buf;
					dOff[i] = start;
					continue;
				}
				start += v.cpu;
				end += v.cpu;
			}
			dBuf[i] = 0L;
			dOff[i] = start;
			min = Math.min(min, start);
			max = Math.max(max, end);
		}
		if (dFmt[POS] == 0) {
			return false;
		}
		if (max > min) {
			long len = max - min;
			long base;
			if (into != null) {
				ByteBuffer d = into.data;
				int pos = (d == null ? 0 : d.position() + 3) & ~3;
				long reserve = Math.max(len, (long)count * dStride[POS]);
				if (d == null || d.capacity() < pos + reserve) {
					ByteBuffer n = ByteBuffer.allocateDirect((int)Math.max(pos + reserve, d == null ? 65536 : d.capacity() * 2L)).order(ByteOrder.nativeOrder());
					if (d != null) {
						d.flip();
						n.put(d);
					}
					into.data = d = n;
				}
				d.position(pos);
				d.put(Objc.wrap(min, len));
				d.position((int)(pos + reserve));
				base = pos;
			} else {
				copyIn(min, len);
				base = rOff;
			}
			for (int i = 0; i < SLOTS; i++) {
				if (dFmt[i] != 0 && (dBuf[i] == 0L || into != null)) {
					dBuf[i] = into != null ? -1L : rBuf;
					dOff[i] = base + dOff[i] - min;
				}
			}
		}
		return true;
	}

	/** A draw compiled into a display list; its vertices live in the list's buffer. */
	private static final class ListDraw implements Op {
		final DList list;
		final int mode;
		int count;
		final int[] fmt = new int[SLOTS], stride = new int[SLOTS];
		final long[] off = new long[SLOTS];

		ListDraw(DList list, int mode, int count) {
			this.list = list;
			this.mode = mode;
			this.count = count;
			System.arraycopy(dFmt, 0, fmt, 0, SLOTS);
			System.arraycopy(dStride, 0, stride, 0, SLOTS);
			System.arraycopy(dOff, 0, off, 0, SLOTS);
		}

		/** Merges the next quads into this draw when they share the layout and follow it in the list buffer. */
		boolean append(int mode, int count) {
			if (this.mode != 7 || mode != 7) {
				return false;
			}
			for (int i = 0; i < SLOTS; i++) {
				if (fmt[i] != dFmt[i] || fmt[i] != 0 && (stride[i] != dStride[i] || dOff[i] != off[i] + (long)this.count * stride[i])) {
					return false;
				}
			}
			this.count += count;
			return true;
		}

		@Override
		public void run() {
			if (list.buf == 0L) {
				return;
			}
			for (int i = 0; i < SLOTS; i++) {
				dFmt[i] = fmt[i];
				dStride[i] = stride[i];
				dOff[i] = off[i];
				dBuf[i] = list.buf;
			}
			draw(mode, count);
		}
	}

	private static void addListDraw(int mode, int count) {
		Op last = rec.ops.isEmpty() ? null : rec.ops.get(rec.ops.size() - 1);
		if (!(last instanceof ListDraw && ((ListDraw)last).append(mode, count))) {
			rec.ops.add(new ListDraw(rec, mode, count));
		}
	}

	// ---------------------------------------------------------------- native submit + batching
	// Tessellator draws and immediate-mode quads with the same layout and no state change in between are merged into one
	// Metal draw (a GUI screen or a line of text becomes a handful of draws). Anything that changes state flushes first.

	private static ByteBuffer batch = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
	private static int batchCount, batchStride;
	private static final int[] batchFmt = new int[5], batchOff = new int[5];

	private static void clearGeneric() {
		for (int i = 5; i < SLOTS; i++) {
			dFmt[i] = 0;
		}
	}

	static void flushBatch() {
		if (batchCount == 0) {
			return;
		}
		int n = batchCount;
		batchCount = 0;
		batch.flip();
		copyIn(address(batch), batch.remaining());
		batch.clear();
		for (int i = 0; i < 5; i++) {
			dFmt[i] = batchFmt[i];
			dStride[i] = batchStride;
			dBuf[i] = rBuf;
			dOff[i] = rOff + batchOff[i];
		}
		clearGeneric();
		draw(7, n);
	}

	/** Draws interleaved vertices at addr (fmt 0 = attribute absent, uses the current value). */
	static void submit(int mode, int count, int stride, int[] fmt, int[] off, long addr) {
		if (count <= 0 || fmt[POS] == 0) {
			return;
		}
		long len = (long)count * stride;
		if (rec != null) {
			ByteBuffer d = rec.data;
			int pos = (d == null ? 0 : d.position() + 3) & ~3;
			if (d == null || d.capacity() < pos + len) {
				ByteBuffer n = ByteBuffer.allocateDirect((int)Math.max(pos + len, d == null ? 65536 : d.capacity() * 2L)).order(ByteOrder.nativeOrder());
				if (d != null) {
					d.flip();
					n.put(d);
				}
				rec.data = d = n;
			}
			d.position(pos);
			d.put(Objc.wrap(addr, len));
			for (int i = 0; i < 5; i++) {
				dFmt[i] = fmt[i];
				dStride[i] = stride;
				dOff[i] = pos + off[i];
			}
			clearGeneric();
			addListDraw(mode, count);
			return;
		}
		if (mode == 7 && (count & 3) == 0) {
			if (batchCount > 0 && (batchStride != stride || !java.util.Arrays.equals(batchFmt, fmt) || !java.util.Arrays.equals(batchOff, off))) {
				flushBatch();
			}
			if (batch.remaining() < len) {
				ByteBuffer n = ByteBuffer.allocateDirect((int)Math.max(batch.capacity() * 2L, batch.position() + len)).order(ByteOrder.nativeOrder());
				batch.flip();
				n.put(batch);
				batch = n;
			}
			Objc.memcpy(address(batch) + batch.position(), addr, len);
			batch.position(batch.position() + (int)len);
			System.arraycopy(fmt, 0, batchFmt, 0, 5);
			System.arraycopy(off, 0, batchOff, 0, 5);
			batchStride = stride;
			batchCount += count;
			return;
		}
		flushBatch();
		copyIn(addr, len);
		for (int i = 0; i < 5; i++) {
			dFmt[i] = fmt[i];
			dStride[i] = stride;
			dBuf[i] = rBuf;
			dOff[i] = rOff + off[i];
		}
		clearGeneric();
		draw(mode, count);
	}

	private static void drawArraysImpl(int mode, int first, int count) {
		if (count <= 0) {
			return;
		}
		if (rec != null) {
			if (gatherArrays(first, count, rec)) {
				addListDraw(mode, count);
			}
			return;
		}
		flushBatch();
		if (gatherArrays(first, count, null)) {
			draw(mode, count);
		}
	}

	// Pipeline/state caches.
	private static final HashMap<Integer, Long> depthStates = new HashMap<Integer, Long>();
	private static long curPipe, curDepthState;
	private static final long[] curTex = new long[2], curSamp = new long[2], curBuf = new long[32], curOff = new long[32];
	private static int curCull, curVx, curVy, curVw, curVh, curSx, curSy, curSw, curSh;
	private static float curBiasU, curBiasF;
	private static long quadIdx, fanIdx;
	private static int quadCap, fanCap;
	private static final ByteBuffer vuB = ByteBuffer.allocateDirect(352).order(ByteOrder.nativeOrder()), fuB = ByteBuffer.allocateDirect(112).order(ByteOrder.nativeOrder()),
		cstB = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
	private static final long vu = Objc.address(vuB), fu = Objc.address(fuB), cst = Objc.address(cstB);

	private static int blendFactor(int gl) {
		switch (gl) {
			case 0: return 0;
			case 1: return 1;
			case 0x300: return 2;
			case 0x301: return 3;
			case 0x302: return 4;
			case 0x303: return 5;
			case 0x306: return 6;
			case 0x307: return 7;
			case 0x304: return 8;
			case 0x305: return 9;
			case 0x308: return 10;
			case 0x8001: return 11;
			case 0x8002: return 12;
			case 0x8003: return 13;
			case 0x8004: return 14;
			default: return 1;
		}
	}

	/** Encoded blend state (as in pipeline keys) for GL factors with GL_FUNC_ADD; used for pack blend.<program> overrides. */
	public static int blendState(int srcRgb, int dstRgb, int srcA, int dstA) {
		return 1 | blendFactor(srcRgb) << 1 | blendFactor(dstRgb) << 5 | blendFactor(srcA) << 9 | blendFactor(dstA) << 13;
	}

	private static int blendOp(int gl) {
		switch (gl) {
			case 0x800A: return 1;
			case 0x800B: return 2;
			case 0x8007: return 3;
			case 0x8008: return 4;
			default: return 0;
		}
	}

	// Draw kinds for pipeline(): fixed-function arrays, native terrain (28- or 56-byte chunk vertices).
	private static final int FIXED = 0, TERRAIN = 1;
	private static final HashMap<String, Long> fixedPipelines = new HashMap<String, Long>();
	private static final StringBuilder key = new StringBuilder();
	private static int terrainStride = 28;

	private static int lastKind = -1, lastBlend, lastMask, lastTerrainStride, encGen, lastEncGen = -1;
	private static Programs.Program lastProg;
	private static long lastPipe;

	/** A relinked program keeps its identity, so the one-entry cache must not hand back its old pipeline. */
	static void forgetPipeline() {
		lastPipe = 0L;
	}
	private static final int[] lastFmt = new int[SLOTS], lastStride = new int[SLOTS];

	/** The pipeline state for the next draw; consecutive draws with the same state skip the key lookup (no allocation). */
	private static long pipeline(int kind) {
		Programs.Program prog = Programs.current;
		int blend = prog != null && prog.blend >= 0 ? prog.blend
			: st.blend ? 1 | blendFactor(st.srcRgb) << 1 | blendFactor(st.dstRgb) << 5 | blendFactor(st.srcA) << 9 | blendFactor(st.dstA) << 13 | blendOp(st.blendEq) << 17 : 0;
		int slots = prog != null ? SLOTS : 5;
		if (lastPipe != 0L && kind == lastKind && prog == lastProg && blend == lastBlend && st.colorMask == lastMask && encGen == lastEncGen
			&& (kind == TERRAIN ? terrainStride == lastTerrainStride : sameLayout(slots))) {
			return lastPipe;
		}
		String k = pipelineKey(key, kind, blend, st.colorMask, encDepth, encFormats, encColors, slots, dFmt, dStride, terrainStride);
		HashMap<String, Long> cache = prog != null ? prog.pipelines : fixedPipelines;
		Long p = cache.get(k);
		if (p == null) {
			cache.put(k, p = buildPipeline(blend, st.colorMask, kind, prog != null, prog != null ? prog.vertexFn : kind == TERRAIN ? tvfn : vfn,
				prog != null ? prog.fragmentFn : ffn, prog != null ? prog.attachBlend : null, encFormats, encColors, encDepth, dFmt, dStride, terrainStride));
		}
		lastKind = kind;
		lastProg = prog;
		lastBlend = blend;
		lastMask = st.colorMask;
		lastEncGen = encGen;
		lastTerrainStride = terrainStride;
		System.arraycopy(dFmt, 0, lastFmt, 0, SLOTS);
		System.arraycopy(dStride, 0, lastStride, 0, SLOTS);
		lastPipe = p;
		return p;
	}

	private static boolean sameLayout(int slots) {
		for (int i = 0; i < slots; i++) {
			if (dFmt[i] != lastFmt[i] || dFmt[i] != 0 && dStride[i] != lastStride[i]) {
				return false;
			}
		}
		return true;
	}

	private static String pipelineKey(StringBuilder key, int kind, int blend, int colorMask, boolean depth, int[] formats, int colors, int slots, int[] fmt,
		int[] stride, int terrainStride) {
		key.setLength(0);
		key.append(kind).append(':').append(blend).append(':').append(colorMask).append(':').append(depth ? 'd' : '-');
		for (int k = 0; k < colors; k++) {
			key.append(',').append(formats[k]);
		}
		key.append('|');
		if (kind == TERRAIN) {
			key.append(terrainStride);
		} else {
			for (int i = 0; i < slots; i++) {
				key.append(fmt[i]).append('/').append(fmt[i] != 0 ? stride[i] : 0).append(' ');
			}
		}
		return key.toString();
	}

	/**
	 * Shader-pack prewarm, on the loader thread: builds the pipeline state a program's draws will need into {@code into}
	 * (key -> pipeline; {@link Programs#addPipelines} installs them). terrain = chunk draws in the 56-byte layout, else the
	 * full-screen quad of deferred/composite/final passes. Formats are GL internal formats of the pass's color targets.
	 */
	public static void prewarm(long[] fns, int blend, int[] attachBlend, boolean terrain, int[] internalFormats, boolean depth, java.util.Map<String, Long> into) {
		int[] formats = new int[internalFormats.length], fmt = new int[SLOTS], stride = new int[SLOTS];
		for (int k = 0; k < formats.length; k++) {
			formats[k] = internalFormats[k] == 0 ? BGRA8 : pixelFormat(internalFormats[k]);
		}
		for (int i = 0; i < 5; i++) {
			fmt[i] = IMM_FMT[i];
			stride[i] = 36;
		}
		int kind = terrain ? TERRAIN : FIXED;
		String k = pipelineKey(new StringBuilder(), kind, blend, 15, depth, formats, formats.length, SLOTS, fmt, stride, 56);
		if (into.containsKey(k)) {
			return;
		}
		long pool = Objc.objc_autoreleasePoolPush();
		try {
			long p = buildPipeline(blend, 15, kind, true, fns[0], fns[1], attachBlend, formats, formats.length, depth, fmt, stride, 56);
			if (p != 0L) {
				into.put(k, p);
			}
		} finally {
			Objc.objc_autoreleasePoolPop(pool);
		}
	}

	private static long buildPipeline(int blend, int colorMask, int kind, boolean program, long vertexFn, long fragmentFn, int[] attachBlend,
		int[] formats, int colors, boolean depth, int[] fmt, int[] stride, int terrainStride) {
		long vd = send(cls("MTLVertexDescriptor"), "vertexDescriptor");
		long attrs = send(vd, "attributes"), layouts = send(vd, "layouts");
		int slots = program ? SLOTS : 5, constBuffer = program ? 17 : 6;
		boolean[] set = new boolean[SLOTS];
		if (kind == TERRAIN) { // chunk vertices in buffer 1: pos, color, uv, lightmap (+ normal, mc_midTexCoord, at_tangent, mc_Entity)
			long[][] layout = terrainStride == 56
				? new long[][] {{0, 30, 0}, {1, 9, 12}, {2, 29, 16}, {3, 16, 24}, {4, 11, 28}, {11, 29, 32}, {12, 18, 40}, {10, 17, 48}}
				: new long[][] {{0, 30, 0}, {1, 9, 12}, {2, 29, 16}, {3, 16, 24}};
			for (long[] e : layout) {
				if (e[0] >= slots) {
					continue;
				}
				long a = send(attrs, "objectAtIndexedSubscript:", e[0]);
				send(a, "setFormat:", e[1]);
				send(a, "setOffset:", e[2]);
				send(a, "setBufferIndex:", 1L);
				set[(int)e[0]] = true;
			}
			long l = send(layouts, "objectAtIndexedSubscript:", 1L);
			send(l, "setStride:", terrainStride);
			send(l, "setStepFunction:", 1L);
		} else {
			for (int i = 0; i < slots; i++) {
				if (fmt[i] != 0) {
					long a = send(attrs, "objectAtIndexedSubscript:", i);
					send(a, "setFormat:", fmt[i]);
					send(a, "setOffset:", 0L);
					send(a, "setBufferIndex:", 1L + i);
					long l = send(layouts, "objectAtIndexedSubscript:", 1L + i);
					send(l, "setStride:", stride[i]);
					send(l, "setStepFunction:", 1L);
					set[i] = true;
				}
			}
		}
		boolean constants = false;
		if (kind != TERRAIN || program) {
			for (int i = 0; i < slots; i++) {
				if (!set[i]) { // absent attribute: a float4 from the constants buffer (current color/normal/texcoords)
					long a = send(attrs, "objectAtIndexedSubscript:", i);
					send(a, "setFormat:", 31L);
					send(a, "setOffset:", i * 16L);
					send(a, "setBufferIndex:", constBuffer);
					constants = true;
				}
			}
		}
		if (constants) {
			long l = send(layouts, "objectAtIndexedSubscript:", constBuffer);
			send(l, "setStride:", slots * 16L);
			send(l, "setStepFunction:", 0L);
			send(l, "setStepRate:", 0L);
		}
		long pd = send(send(cls("MTLRenderPipelineDescriptor"), "alloc"), "init");
		send(pd, "setVertexFunction:", vertexFn);
		send(pd, "setFragmentFunction:", fragmentFn);
		send(pd, "setVertexDescriptor:", vd);
		long mask = ((colorMask & 1) != 0 ? 8 : 0) | ((colorMask & 2) != 0 ? 4 : 0) | ((colorMask & 4) != 0 ? 2 : 0) | ((colorMask & 8) != 0 ? 1 : 0);
		long cas = send(pd, "colorAttachments");
		for (int k = 0; k < colors; k++) {
			if (formats[k] == 0) {
				continue;
			}
			long ca = send(cas, "objectAtIndexedSubscript:", k);
			send(ca, "setPixelFormat:", formats[k]);
			send(ca, "setWriteMask:", mask);
			int bl = attachBlend != null && k < attachBlend.length && attachBlend[k] >= 0 ? attachBlend[k] : blend;
			if ((bl & 1) != 0) {
				send(ca, "setBlendingEnabled:", 1L);
				send(ca, "setSourceRGBBlendFactor:", bl >> 1 & 15);
				send(ca, "setDestinationRGBBlendFactor:", bl >> 5 & 15);
				send(ca, "setSourceAlphaBlendFactor:", bl >> 9 & 15);
				send(ca, "setDestinationAlphaBlendFactor:", bl >> 13 & 15);
				send(ca, "setRgbBlendOperation:", bl >> 17 & 7);
				send(ca, "setAlphaBlendOperation:", bl >> 17 & 7);
			}
		}
		if (depth) {
			send(pd, "setDepthAttachmentPixelFormat:", DEPTH32F);
		}
		return newPipeline(pd);
	}

	/** Compiles one MSL stage and returns its (retained) function, or 0 with the error logged. */
	static long metalFunction(String msl, String entry) {
		ByteBuffer err = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		long lib = objc_msgSend(device, sel("newLibraryWithSource:options:error:"), nsString(msl), 0L, Objc.address(err));
		if (lib == 0L) {
			LOG.warn("Graft: Metal rejected a translated shader: {}", string(send(err.getLong(0), "localizedDescription")));
			return 0L;
		}
		long fn = send(lib, "newFunctionWithName:", nsString(entry));
		send(lib, "release");
		return fn;
	}

	static void releaseObject(long obj) {
		if (obj != 0L) {
			send(obj, "release");
		}
	}

	private static long depthState() {
		int key = st.depthTest ? 1 | (st.depthFunc & 7) << 1 | (st.depthMask ? 16 : 0) : 0;
		Long d = depthStates.get(key);
		if (d == null) {
			long desc = send(send(cls("MTLDepthStencilDescriptor"), "alloc"), "init");
			send(desc, "setDepthCompareFunction:", st.depthTest ? st.depthFunc & 7 : 7L);
			send(desc, "setDepthWriteEnabled:", st.depthTest && st.depthMask ? 1L : 0L);
			depthStates.put(key, d = send(device, "newDepthStencilStateWithDescriptor:", desc));
			send(desc, "release");
		}
		return d;
	}

	private static long indexBuffer(boolean quads, int count) {
		if (quads ? count > quadCap : count > fanCap) {
			int cap = Math.max(4096, Integer.highestOneBit(count) << 1);
			int tris = quads ? cap / 4 * 2 : cap - 2;
			IntBuffer ib = ByteBuffer.allocateDirect(tris * 12).order(ByteOrder.nativeOrder()).asIntBuffer();
			if (quads) {
				for (int q = 0; q < cap / 4; q++) {
					int b = q * 4;
					ib.put(b).put(b + 1).put(b + 2).put(b).put(b + 2).put(b + 3);
				}
			} else {
				for (int i = 1; i < cap - 1; i++) {
					ib.put(0).put(i).put(i + 1);
				}
			}
			ib.flip();
			long buf = objc_msgSend(device, sel("newBufferWithBytes:length:options:"), address(ib), tris * 12L, 0L);
			if (quads) {
				if (quadIdx != 0L) {
					send(quadIdx, "release");
				}
				quadIdx = buf;
				quadCap = cap;
			} else {
				if (fanIdx != 0L) {
					send(fanIdx, "release");
				}
				fanIdx = buf;
				fanCap = cap;
			}
		}
		return quads ? quadIdx : fanIdx;
	}

	private static void putMat(ByteBuffer b, int at, float[] m) {
		for (int i = 0; i < 16; i++) {
			b.putFloat(at + i * 4, m[i]);
		}
	}

	private static void put4(ByteBuffer b, int at, float[] v, float scale) {
		for (int i = 0; i < 4; i++) {
			b.putFloat(at + i * 4, i < v.length ? v[i] * scale : 0);
		}
	}

	private static boolean texOn(int u) {
		Tex t = st.tex2d[u] ? textures.get(bound[u]) : null;
		return t != null && t.mtl != 0L;
	}

	/** Pass, pipeline, fixed-function state, uniforms and textures for the next draw. */
	private static boolean prepare(int kind) {
		if (encFb != drawFb || renc == 0L) {
			if (!beginPass(false, false)) {
				return false;
			}
		}
		long e = renc;
		long p = pipeline(kind);
		if (p == 0L) {
			return false;
		}
		if (p != curPipe) {
			objc_msgSend(e, S_RENDER_PIPELINE, p);
			curPipe = p;
		}
		long ds = depthState();
		if (ds != curDepthState) {
			objc_msgSend(e, S_DEPTH_STATE, ds);
			curDepthState = ds;
		}
		// dynamic state
		int cull = st.cull ? st.cullFace == 0x404 ? 1 : st.cullFace == 0x405 ? 2 : 0 : 0;
		if (cull != curCull) {
			objc_msgSend(e, S_CULL, cull);
			curCull = cull;
		}
		float vs = encFb == SCALED_FB ? scaledFactor : 1f;
		int pvx = Math.round(vx * vs), pvy = Math.round(vy * vs), pvw = Math.round(vw * vs), pvh = Math.round(vh * vs);
		if (pvx != curVx || pvy != curVy || pvw != curVw || pvh != curVh) {
			Objc.viewport(e, pvx, pvy, pvw, pvh);
			curVx = pvx;
			curVy = pvy;
			curVw = pvw;
			curVh = pvh;
		}
		int x0 = 0, y0 = 0, x1 = encW, y1 = encH;
		if (st.scissorTest) {
			x0 = Math.max(0, Math.round(sx * vs));
			y0 = Math.max(0, Math.round(sy * vs));
			x1 = Math.min(encW, Math.round((sx + sw) * vs));
			y1 = Math.min(encH, Math.round((sy + sh) * vs));
			if (x1 <= x0 || y1 <= y0) {
				return false;
			}
		}
		if (x0 != curSx || y0 != curSy || x1 - x0 != curSw || y1 - y0 != curSh) {
			Objc.scissor(e, x0, y0, x1 - x0, y1 - y0);
			curSx = x0;
			curSy = y0;
			curSw = x1 - x0;
			curSh = y1 - y0;
		}
		float bu = st.polyOffset ? st.polyUnits : 0, bf = st.polyOffset ? st.polyFactor : 0;
		if (bu != curBiasU || bf != curBiasF) {
			objc_msgSend(e, S_BIAS, bu, bf, 0f);
			curBiasU = bu;
			curBiasF = bf;
		}
		if (Programs.current != null) {
			prepareProgram(e, Programs.current);
			return true;
		}
		// vertex uniforms
		float[] mv = mvStack[mvTop];
		mul(tmp2, prStack[prTop], mv);
		putMat(vuB, 0, tmp2);
		putMat(vuB, 64, mv);
		putMat(vuB, 128, texStack[0][texTop[0]]);
		putMat(vuB, 192, texStack[1][texTop[1]]);
		put4(vuB, 256, st.l0dir, 1);
		put4(vuB, 272, st.l1dir, 1);
		put4(vuB, 288, st.l0diff, st.light0 ? 1 : 0);
		put4(vuB, 304, st.l1diff, st.light1 ? 1 : 0);
		for (int i = 0; i < 4; i++) {
			vuB.putFloat(320 + i * 4, st.modelAmb[i] + (st.light0 ? st.l0amb[i] : 0) + (st.light1 ? st.l1amb[i] : 0));
		}
		vuB.putInt(336, st.lighting ? 1 : 0);
		objc_msgSend(e, S_VBYTES, vu, 352L, 0L);
		// fragment uniforms
		put4(fuB, 0, st.fogColor, 1);
		fuB.putFloat(16, st.fogStart);
		fuB.putFloat(20, st.fogEnd);
		fuB.putFloat(24, st.fogDensity);
		fuB.putInt(28, st.fog ? st.fogMode : 0);
		boolean t0 = texOn(0), t1 = texOn(1);
		fuB.putInt(32, t0 ? 1 : 0);
		fuB.putInt(36, t1 ? 1 : 0);
		fuB.putInt(96, texOn(2) ? 1 : 0);
		fuB.putInt(40, st.alphaTest ? st.alphaFunc : 0);
		fuB.putFloat(44, st.alphaRef);
		fuB.putInt(48, st.env[0]);
		fuB.putInt(52, st.env[1]);
		fuB.putInt(56, st.comb[0]);
		fuB.putInt(60, st.comb[1]);
		put4(fuB, 64, st.envColor[0], 1);
		put4(fuB, 80, st.envColor[1], 1);
		objc_msgSend(e, S_FBYTES, fu, 112L, 0L);
		for (int u = 0; u < 2; u++) {
			Tex t = (u == 0 ? t0 : t1) ? textures.get(bound[u]) : null;
			long mtl = t != null ? t.mtl : white, samp = t != null ? sampler(t) : sampler(DEFAULT_TEX);
			if (mtl != curTex[u]) {
				objc_msgSend(e, S_FTEX, mtl, u);
				curTex[u] = mtl;
			}
			if (samp != curSamp[u]) {
				objc_msgSend(e, S_FSAMP, samp, u);
				curSamp[u] = samp;
			}
			curProgTex[u] = curProgSamp[u] = -1L;
		}
		return true;
	}

	private static final long S_VBUF_FRAG = sel("setFragmentBuffer:offset:atIndex:"), S_VTEX = sel("setVertexTexture:atIndex:"),
		S_VSAMP = sel("setVertexSamplerState:atIndex:");
	private static final long[] curProgTex = new long[32], curProgSamp = new long[32];
	private static long zeroOffsets, whiteDepth;
	private static final float[] inv = new float[16], nrm = new float[9], handPr = new float[16];
	/** Scale of clip-space z for shader programs (the shader-pack hand pass sets MC_HAND_DEPTH); 1 = off. */
	public static float handDepth = 1f;

	/** mc_Uniforms (pack values + compatibility built-ins from GL state) and the program's samplers. */
	private static final java.util.HashSet<Integer> dumpedUniforms = new java.util.HashSet<Integer>();

	private static void prepareProgram(long e, Programs.Program p) {
		if (DUMP_FRAME >= 0 && frameNumber == DUMP_FRAME && dumpedUniforms.add(p.id)) {
			StringBuilder b = new StringBuilder();
			for (MetalGlsl.Member m : p.linked.members) {
				if (m.offset < 0 || m.name.startsWith("mc_")) {
					continue;
				}
				b.append(m.name).append('=');
				int n = m.type.startsWith("mat") ? 0 : m.type.endsWith("2") ? 2 : m.type.endsWith("3") ? 3 : m.type.endsWith("4") ? 4 : 1;
				for (int c = 0; c < n; c++) {
					b.append(m.type.startsWith("i") || m.type.equals("int") || m.type.equals("bool") ? String.valueOf(p.block.getInt(m.offset + c * 4))
						: String.valueOf(p.block.getFloat(m.offset + c * 4))).append(c + 1 < n ? "," : "");
				}
				b.append(' ');
			}
			LOG.info("Graft: uniforms of program {}: {}", p.id, b);
		}
		int size = p.block.capacity();
		alloc(size);
		ByteBuffer v = rView;
		int base = (int)rOff;
		Objc.memcpy(rAddr + rOff, p.blockAddr, size);
		int[] b = p.builtins;
		float[] mv = mvStack[mvTop], pr = prStack[prTop];
		if (handDepth != 1f) { // shader-pack hand: depth squeezed towards the near plane (MC_HAND_DEPTH)
			System.arraycopy(pr, 0, handPr, 0, 16);
			for (int i = 2; i < 16; i += 4) {
				handPr[i] *= handDepth;
			}
			pr = handPr;
		}
		mul(tmp2, pr, mv);
		putMatAt(v, base, b[0], mv);
		putMatAt(v, base, b[1], pr);
		putMatAt(v, base, b[2], tmp2);
		if (b[3] >= 0 && invert(mv, inv)) {
			putMatAt(v, base, b[3], inv);
		}
		if (b[4] >= 0 && invert(pr, inv)) {
			putMatAt(v, base, b[4], inv);
		}
		if (b[5] >= 0 && invert(tmp2, inv)) {
			putMatAt(v, base, b[5], inv);
		}
		if (b[6] >= 0 && invert(mv, inv)) { // normal matrix: transpose of the inverse upper 3x3, as three vec4 columns
			for (int c = 0; c < 3; c++) {
				for (int r = 0; r < 3; r++) {
					v.putFloat(base + b[6] + c * 16 + r * 4, inv[r * 4 + c]);
				}
			}
		}
		if (b[7] >= 0) {
			for (int u = 0; u < 4; u++) {
				putMatAt(v, base, b[7] + u * 64, texStack[u][texTop[u]]);
			}
		}
		if (b[8] >= 0) {
			for (int i = 0; i < 4; i++) {
				v.putFloat(base + b[8] + i * 4, st.fogColor[i]);
			}
		}
		if (b[9] >= 0) {
			v.putFloat(base + b[9], st.fogDensity).putFloat(base + b[9] + 4, st.fogStart).putFloat(base + b[9] + 8, st.fogEnd)
				.putFloat(base + b[9] + 12, st.fogEnd != st.fogStart ? 1 / (st.fogEnd - st.fogStart) : 0);
		}
		if (b[10] >= 0) {
			v.putInt(base + b[10], p.alphaFunc >= 0 ? p.alphaFunc : st.alphaTest ? st.alphaFunc : 0);
		}
		if (b[11] >= 0) {
			v.putFloat(base + b[11], p.alphaFunc >= 0 ? p.alphaRef : st.alphaRef);
		}
		objc_msgSend(e, S_VBUF, rBuf, rOff, 28L);
		objc_msgSend(e, S_VBUF_FRAG, rBuf, rOff, 28L);
		curBuf[28] = -1L;
		if (zeroOffsets == 0L) {
			zeroOffsets = objc_msgSend(device, sel("newBufferWithLength:options:"), 16L, 0L);
			whiteDepth = newTexture(1, 1, 1, DEPTH32F);
		}
		if (curBuf[29] != zeroOffsets) {
			objc_msgSend(e, S_VBUF, zeroOffsets, 0L, 29L);
			curBuf[29] = zeroOffsets;
		}
		for (int i = 0; i < p.units.length && i < curProgTex.length; i++) {
			int unitIndex = p.units[i];
			Tex t = unitIndex >= 0 && unitIndex < BIND_UNITS ? textures.get(bound[unitIndex]) : null;
			boolean shadow = p.linked.shadowSamplers.get(i);
			if (t != null && (t.mtl == 0L || shadow != (t.format == DEPTH32F) && shadow)) {
				t = null;
			}
			long mtl = t != null ? t.mtl : shadow ? whiteDepth : white;
			long samp = t != null ? shadow ? compareSampler(t) : sampler(t) : shadow ? compareSampler(DEFAULT_TEX) : sampler(DEFAULT_TEX);
			boolean ownSampler = i < MetalGlsl.BOUND_SAMPLERS; // later ones use a constexpr sampler (Metal has 16 slots)
			if (mtl != curProgTex[i] || ownSampler && samp != curProgSamp[i]) {
				objc_msgSend(e, S_FTEX, mtl, i);
				if (ownSampler) {
					objc_msgSend(e, S_FSAMP, samp, i);
				}
				if (p.linked.vertexSamplers) {
					objc_msgSend(e, S_VTEX, mtl, i);
					if (ownSampler) {
						objc_msgSend(e, S_VSAMP, samp, i);
					}
				}
				curProgTex[i] = mtl;
				curProgSamp[i] = samp;
			}
		}
		if (p.units.length > 0) {
			curTex[0] = curTex[1] = curSamp[0] = curSamp[1] = -1L;
		}
	}

	private static void putMatAt(ByteBuffer v, int base, int offset, float[] m) {
		if (offset >= 0) {
			for (int i = 0; i < 16; i++) {
				v.putFloat(base + offset + i * 4, m[i]);
			}
		}
	}

	/** 4x4 inverse (column-major); false if singular. */
	private static boolean invert(float[] m, float[] out) {
		float[] o = out;
		o[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
		o[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
		o[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
		o[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
		o[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
		o[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
		o[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
		o[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
		o[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
		o[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
		o[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
		o[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
		o[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
		o[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
		o[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
		o[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
		float det = m[0] * o[0] + m[1] * o[4] + m[2] * o[8] + m[3] * o[12];
		if (det == 0) {
			return false;
		}
		det = 1 / det;
		for (int i = 0; i < 16; i++) {
			o[i] *= det;
		}
		return true;
	}

	static long drawCalls;
	private static final ByteBuffer progConst = ByteBuffer.allocateDirect(SLOTS * 16).order(ByteOrder.nativeOrder());
	private static final long progConstAddr = Objc.address(progConst);

	/** Current values for attributes without an array: color, texcoords, normal; generic attributes (0, 0, 0, 1). */
	private static void bindConstants(long e, boolean program) {
		ByteBuffer c = program ? progConst : cstB;
		for (int i = 0; i < 4; i++) {
			c.putFloat(16 + i * 4, color[i]);
			c.putFloat(32 + i * 4, i < 2 ? tc0[i] : i == 3 ? 1 : 0);
			c.putFloat(48 + i * 4, i < 2 ? tc1[i] : i == 3 ? 1 : 0);
			c.putFloat(64 + i * 4, i < 3 ? normal[i] : 0);
		}
		if (program) {
			for (int s = 5; s < SLOTS; s++) {
				c.putFloat(s * 16, 0).putFloat(s * 16 + 4, 0).putFloat(s * 16 + 8, 0).putFloat(s * 16 + 12, 1);
			}
			objc_msgSend(e, S_VBYTES, progConstAddr, SLOTS * 16L, 17L);
		} else {
			objc_msgSend(e, S_VBYTES, cst, 80L, 6L);
		}
	}

	private static void draw(int mode, int count) {
		if (!prepare(FIXED)) {
			return;
		}
		drawCalls++;
		long e = renc;
		boolean program = Programs.current != null;
		for (int i = 0; i < (program ? SLOTS : 5); i++) {
			if (dFmt[i] != 0 && (dBuf[i] != curBuf[1 + i] || dOff[i] != curOff[1 + i])) {
				objc_msgSend(e, S_VBUF, dBuf[i], dOff[i], 1L + i);
				curBuf[1 + i] = dBuf[i];
				curOff[1 + i] = dOff[i];
			}
		}
		bindConstants(e, program);
		switch (mode) {
			case 7: // GL_QUADS
				count &= ~3;
				if (count > 0) {
					objc_msgSend(e, S_DRAW_IDX, 3L, count / 4 * 6L, 1L, indexBuffer(true, count), 0L);
				}
				break;
			case 6: case 9: // GL_TRIANGLE_FAN, GL_POLYGON
				if (count >= 3) {
					objc_msgSend(e, S_DRAW_IDX, 3L, (count - 2) * 3L, 1L, indexBuffer(false, count), 0L);
				}
				break;
			default:
				long prim = mode == 0 ? 0 : mode == 1 ? 1 : mode == 2 || mode == 3 ? 2 : mode == 4 ? 3 : 4;
				objc_msgSend(e, S_DRAW, prim, 0L, (long)count);
		}
	}

	private static final long S_DRAW_IDX_INST = sel("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:");

	/** Terrain: per chunk only a buffer bind and a draw; its camera-relative offset is read via base_instance. */
	static void drawTerrain(long[] bufs, int[] counts, float[] offs, int n, int stride) {
		flushBatch();
		terrainStride = stride;
		if (n == 0 || !prepare(TERRAIN)) {
			return;
		}
		long e = renc;
		boolean program = Programs.current != null;
		alloc(n * 16L);
		int max = 0;
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 4; k++) {
				rView.putFloat((int)rOff + i * 16 + k * 4, offs[i * 4 + k]);
			}
			max = Math.max(max, counts[i]);
		}
		objc_msgSend(e, S_VBUF, rBuf, rOff, program ? 29L : 2L);
		if (program) {
			bindConstants(e, true);
		}
		long idx = indexBuffer(true, max);
		for (int i = 0; i < n; i++) {
			objc_msgSend(e, S_VBUF, bufs[i], 0L, 1L);
			objc_msgSend(e, S_DRAW_IDX_INST, 3L, counts[i] / 4 * 6L, 1L, idx, 0L, 1L, 0L, (long)i);
			drawCalls++;
		}
		curBuf[1] = curBuf[2] = curBuf[29] = -1L;
	}

	static long newBuffer(ByteBuffer data, long len) {
		return objc_msgSend(device, sel("newBufferWithBytes:length:options:"), address(data), len, 0L);
	}

	/** Width or height of a texture, 0 if unknown (render thread). */
	public static int textureSize(int id, boolean height) {
		Tex t = textures.get(id);
		return t == null ? 0 : height ? t.h : t.w;
	}

	private static final long S_NEW_BUFFER = sel("newBufferWithBytes:length:options:");

	/** newBuffer from any thread (chunk workers): creating Metal buffers is thread-safe. */
	static long newBufferAnyThread(ByteBuffer data, long len) {
		long pool = Objc.objc_autoreleasePoolPush();
		try {
			return objc_msgSend(device, S_NEW_BUFFER, address(data), len, 0L);
		} finally {
			Objc.objc_autoreleasePoolPop(pool);
		}
	}

		static void releaseBuffer(long buf) {
		if (buf != 0L) {
			send(buf, "release"); // command buffers retain what they use
		}
	}

	private static final Tex DEFAULT_TEX = new Tex();

	// Immediate mode: pos float3, color ubyte4, uv0 float2, uv1 float2, normal byte4 (36 bytes).
	private static ByteBuffer imm = ByteBuffer.allocateDirect(36 * 4096).order(ByteOrder.nativeOrder());
	private static int immMode = -1, immCount;

	private static void vertex(float x, float y, float z) {
		if (imm.remaining() < 36) {
			ByteBuffer n = ByteBuffer.allocateDirect(imm.capacity() * 2).order(ByteOrder.nativeOrder());
			imm.flip();
			n.put(imm);
			imm = n;
		}
		imm.putFloat(x).putFloat(y).putFloat(z);
		for (int i = 0; i < 4; i++) {
			imm.put((byte)Math.round(Math.max(0, Math.min(1, color[i])) * 255));
		}
		imm.putFloat(tc0[0]).putFloat(tc0[1]).putFloat(tc1[0]).putFloat(tc1[1]);
		imm.put((byte)(normal[0] * 127)).put((byte)(normal[1] * 127)).put((byte)(normal[2] * 127)).put((byte)0);
		immCount++;
	}

	// ---------------------------------------------------------------- misc

	private static final Set<String> warned = new HashSet<String>();

	private static void warnOnce(String what) {
		if (warned.add(what)) {
			LOG.warn("Graft: unsupported {}", what);
		}
	}

	private static float[] floats(FloatBuffer b, int n) {
		float[] v = new float[n];
		for (int i = 0; i < n && b.position() + i < b.limit(); i++) {
			v[i] = b.get(b.position() + i);
		}
		return v;
	}

	// ================================================================ GL11

	public static void glEnable(int cap) {
		setCap(cap, true);
	}

	public static void glDisable(int cap) {
		setCap(cap, false);
	}

	private static void setCap(final int cap, final boolean on) {
		if (record(new Op() {
			public void run() {
				setCap(cap, on);
			}
		})) {
			return;
		}
		switch (cap) {
			case 0xBE2: st.blend = on; break;
			case 0xBC0: st.alphaTest = on; break;
			case 0xB71: st.depthTest = on; break;
			case 0xB44: st.cull = on; break;
			case 0xB60: st.fog = on; break;
			case 0xB50: st.lighting = on; break;
			case 0x4000: st.light0 = on; break;
			case 0x4001: st.light1 = on; break;
			case 0x8037: st.polyOffset = on; break;
			case 0xC11: st.scissorTest = on; break;
			case 0xDE1:
				st.tex2d[fx()] = on;
				if (unit == 0 && dev.graft.legacy.shaders.ShaderPipeline.active) {
					dev.graft.legacy.shaders.ShaderPipeline.textureToggled(on);
				}
				break;
			default: // normalize, rescale normal, color material, texgen, logic op, line smooth: no effect here
		}
	}

	public static void glAlphaFunc(final int func, final float ref) {
		if (record(new Op() {
			public void run() {
				glAlphaFunc(func, ref);
			}
		})) {
			return;
		}
		st.alphaFunc = func;
		st.alphaRef = ref;
	}

	public static void glBlendFunc(int s, int d) {
		glBlendFuncSeparate(s, d, s, d);
	}

	public static void glBlendFuncSeparate(final int sRgb, final int dRgb, final int sA, final int dA) {
		if (record(new Op() {
			public void run() {
				glBlendFuncSeparate(sRgb, dRgb, sA, dA);
			}
		})) {
			return;
		}
		st.srcRgb = sRgb;
		st.dstRgb = dRgb;
		st.srcA = sA;
		st.dstA = dA;
	}

	public static void glBlendFuncSeparateEXT(int sRgb, int dRgb, int sA, int dA) {
		glBlendFuncSeparate(sRgb, dRgb, sA, dA);
	}

	public static void glBlendEquation(final int mode) {
		if (record(new Op() {
			public void run() {
				glBlendEquation(mode);
			}
		})) {
			return;
		}
		st.blendEq = mode;
	}

	public static void glDepthFunc(final int func) {
		if (record(new Op() {
			public void run() {
				glDepthFunc(func);
			}
		})) {
			return;
		}
		st.depthFunc = func;
	}

	public static void glDepthMask(final boolean flag) {
		if (record(new Op() {
			public void run() {
				glDepthMask(flag);
			}
		})) {
			return;
		}
		st.depthMask = flag;
	}

	public static void glColorMask(final boolean r, final boolean g, final boolean b, final boolean a) {
		if (record(new Op() {
			public void run() {
				glColorMask(r, g, b, a);
			}
		})) {
			return;
		}
		st.colorMask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
	}

	public static void glCullFace(final int mode) {
		if (record(new Op() {
			public void run() {
				glCullFace(mode);
			}
		})) {
			return;
		}
		st.cullFace = mode;
	}

	public static void glPolygonOffset(final float factor, final float units) {
		if (record(new Op() {
			public void run() {
				glPolygonOffset(factor, units);
			}
		})) {
			return;
		}
		st.polyFactor = factor;
		st.polyUnits = units;
	}

	public static void glShadeModel(int mode) {
	}

	public static void glLineWidth(float width) {
	}

	public static void glLogicOp(int op) {
	}

	public static void glColorMaterial(int face, int mode) {
	}

	public static void glTexGeni(int coord, int pname, int param) {
	}

	public static void glTexGen(int coord, int pname, FloatBuffer params) {
	}

	public static void glClearColor(float r, float g, float b, float a) {
		clearR = r;
		clearG = g;
		clearB = b;
		clearA = a;
	}

	public static void glClearDepth(double depth) {
		clearDepth = depth;
	}

	private static float[][] clearColors;

	/** One render pass that clears the draw buffers to their own colors (null entry = glClearColor) and depth if asked. */
	public static void clearBuffers(float[][] colors, boolean depth) {
		clearColors = colors;
		glClear(depth ? 0x4100 : 0x4000);
		clearColors = null;
	}

	public static void glClear(int mask) {
		flushBatch();
		boolean c = (mask & 0x4000) != 0, d = (mask & 0x100) != 0;
		if (c || d) {
			beginPass(c, d);
		}
	}

	public static void glViewport(int x, int y, int w, int h) {
		flushBatch();
		vx = x;
		vy = y;
		vw = w;
		vh = h;
	}

	public static void glScissor(int x, int y, int w, int h) {
		flushBatch();
		sx = x;
		sy = y;
		sw = w;
		sh = h;
	}

	public static void glPushAttrib(int mask) {
		flushBatch();
		attribStack.add(st.copy());
	}

	public static void glPopAttrib() {
		flushBatch();
		if (!attribStack.isEmpty()) {
			st = attribStack.remove(attribStack.size() - 1);
		}
	}

	/** Reported to Minecraft, Forge and OptiFine in place of a GL context's strings. */
	public static String glGetString(int name) {
		switch (name) {
			case 0x1F00: return "Apple";
			case 0x1F01: return string(send(device, "name")) + " (Graft)";
			case 0x1F02: return "2.1 Graft";
			default: return "";
		}
	}

	private static org.lwjgl.opengl.ContextCapabilities caps;

	/**
	 * GLContext.getCapabilities(): a ContextCapabilities built without a context, advertising what MGL implements:
	 * GL 2.0 (OptiFine's shader packs, translated by MetalGlsl) and ARB/EXT framebuffer objects. GL 2.1 and the ARB
	 * shader extensions stay off, which keeps vanilla's post-processing shaders (Super Secret Settings) disabled.
	 */
	public static org.lwjgl.opengl.ContextCapabilities getCapabilities() {
		if (caps == null) {
			try {
				java.lang.reflect.Field u = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
				u.setAccessible(true);
				caps = (org.lwjgl.opengl.ContextCapabilities)((sun.misc.Unsafe)u.get(null)).allocateInstance(org.lwjgl.opengl.ContextCapabilities.class);
				for (String f : new String[] {"OpenGL11", "OpenGL12", "OpenGL13", "OpenGL14", "OpenGL15", "OpenGL20", "GL_ARB_multitexture",
					"GL_ARB_vertex_buffer_object", "GL_ARB_framebuffer_object", "GL_EXT_framebuffer_object", "GL_EXT_blend_func_separate",
					"GL_ARB_texture_non_power_of_two", "GL_EXT_blend_subtract", "GL_EXT_blend_minmax", "GL_ARB_depth_texture"}) {
					java.lang.reflect.Field field = org.lwjgl.opengl.ContextCapabilities.class.getField(f);
					field.setAccessible(true);
					field.setBoolean(caps, true);
				}
			} catch (Exception e) {
				throw new IllegalStateException("Graft: could not build GL capabilities", e);
			}
		}
		return caps;
	}

	/** Every LWJGL GL call MGL does not implement is replaced by a default value and this (see GlTransformer). */
	public static void unsupported(String call) {
		warnOnce(call);
	}

	public static void glFlush() {
	}

	public static void glFinish() {
	}

	public static void glHint(int target, int mode) {
	}

	public static void glDrawBuffer(int mode) {
	}

	public static void glColor3f(float r, float g, float b) {
		glColor4f(r, g, b, 1);
	}

	public static void glGenTextures(IntBuffer out) {
		for (int i = out.position(); i < out.limit(); i++) {
			out.put(i, glGenTextures());
		}
	}

	public static float glGetFloat(int pname) {
		unsupported("glGetFloat 0x" + Integer.toHexString(pname));
		return 0;
	}

	public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
		flushBatch();
		texSubImage(level, x, y, width, height, format, type, pixels);
	}

	public static void glGenerateMipmapEXT(int target) {
		glGenerateMipmap(target);
	}

	// ---- render scale (MetalFX)

	private static final int SCALED_FB = -100, SCALED_COLOR = -101, SCALED_DEPTH = -102;
	private static int scaledFor = -1, mainRead;
	private static float scaledFactor = 1f;
	private static long scaler, scalerOut;
	private static final int[] scalerKey = new int[4];
	private static Boolean metalFx;

	static boolean metalFxAvailable() {
		if (metalFx == null) {
			long c = Objc.objc_getClass("MTLFXSpatialScalerDescriptor");
			metalFx = c != 0L && objc_msgSend(c, sel("supportsDevice:"), device) != 0L;
			LOG.info("Graft: MetalFX upscaling {}", metalFx ? "available" : "unavailable");
		}
		return metalFx;
	}

	/** Starts drawing the world into a w x h stand-in for framebuffer `main` (0 = the window). */
	static boolean beginScaled(int main, int w, int h) {
		flushBatch();
		Tex c = tex(SCALED_COLOR);
		if (c.mtl == 0L || c.w != w || c.h != h) {
			if (c.mtl != 0L) {
				send(c.mtl, "release");
			}
			c.mtl = newTexture(w, h, 1, BGRA8);
			c.w = w;
			c.h = h;
			c.levels = 1;
			Long d = renderbuffers.remove(SCALED_DEPTH);
			if (d != null) {
				send(d, "release");
			}
			renderbuffers.put(SCALED_DEPTH, newTexture(w, h, 1, DEPTH32F));
		}
		Fbo f = fbos.get(SCALED_FB);
		if (f == null) {
			fbos.put(SCALED_FB, f = new Fbo());
		}
		f.color[0] = SCALED_COLOR;
		f.depth = SCALED_DEPTH;
		int fullW = main == 0 ? defW : fboWidth(main);
		scaledFactor = fullW > 0 ? (float)w / fullW : 1f;
		scaledFor = main;
		mainRead = readFb;
		if (drawFb == main) {
			drawFb = SCALED_FB;
		}
		if (readFb == main) {
			readFb = SCALED_FB;
		}
		if (encFb == main) {
			endEncoders();
		}
		return true;
	}

	private static int fboWidth(int fb) {
		Fbo f = fbos.get(fb);
		Tex t = f == null ? null : textures.get(f.color[0]);
		return t == null ? 0 : t.w;
	}

	/** Upscales the scaled world into the real framebuffer with the MetalFX spatial scaler and restores bindings. */
	static void endScaled() {
		flushBatch();
		int main = scaledFor;
		scaledFor = -1;
		if (drawFb == SCALED_FB) {
			drawFb = main;
		}
		if (readFb == SCALED_FB) {
			readFb = main;
		}
		endEncoders();
		Tex src = textures.get(SCALED_COLOR);
		long dst = main == 0 ? defColor : 0L;
		int dw = defW, dh = defH;
		if (main != 0) {
			Fbo f = fbos.get(main);
			Tex t = f == null ? null : textures.get(f.color[0]);
			if (t == null || t.mtl == 0L) {
				return;
			}
			dst = t.mtl;
			dw = t.w;
			dh = t.h;
		}
		if (src == null || src.mtl == 0L || dst == 0L) {
			return;
		}
		if (scaler == 0L || scalerKey[0] != src.w || scalerKey[1] != src.h || scalerKey[2] != dw || scalerKey[3] != dh) {
			releaseObject(scaler);
			releaseObject(scalerOut);
			long desc = send(send(cls("MTLFXSpatialScalerDescriptor"), "alloc"), "init");
			send(desc, "setInputWidth:", src.w);
			send(desc, "setInputHeight:", src.h);
			send(desc, "setOutputWidth:", dw);
			send(desc, "setOutputHeight:", dh);
			send(desc, "setColorTextureFormat:", BGRA8);
			send(desc, "setOutputTextureFormat:", BGRA8);
			send(desc, "setColorProcessingMode:", 0L); // perceptual (display-referred 8-bit color)
			scaler = send(desc, "newSpatialScalerWithDevice:", device);
			send(desc, "release");
			if (scaler == 0L) {
				return;
			}
			// MetalFX writes its own output texture (with the usage it asks for); a blit copies it into the target.
			long d = objc_msgSend(cls("MTLTextureDescriptor"), sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"), BGRA8, dw, dh, 0L);
			send(d, "setUsage:", send(scaler, "outputTextureUsage") | 1L);
			send(d, "setStorageMode:", 2L);
			scalerOut = send(device, "newTextureWithDescriptor:", d);
			scalerKey[0] = src.w;
			scalerKey[1] = src.h;
			scalerKey[2] = dw;
			scalerKey[3] = dh;
		}
		send(scaler, "setColorTexture:", src.mtl);
		send(scaler, "setOutputTexture:", scalerOut);
		send(scaler, "setInputContentWidth:", src.w);
		send(scaler, "setInputContentHeight:", src.h);
		send(scaler, "encodeToCommandBuffer:", cmd());
		Objc.texToTex(blit(), scalerOut, 0, 0, dw, dh, dst, 0, 0, 0);
	}

	/** glDrawBuffers: which color attachment each fragment output writes (per framebuffer, as in GL). */
	public static void glDrawBuffers(IntBuffer buffers) {
		int n = buffers.remaining();
		int[] draw = new int[Math.min(n, 8)];
		for (int i = 0; i < draw.length; i++) {
			int b = buffers.get(buffers.position() + i);
			draw[i] = b >= 0x8CE0 && b < 0x8CF0 ? b - 0x8CE0 : -1;
		}
		setDrawBuffers(draw);
	}

	public static void glDrawBuffers(int buffer) {
		setDrawBuffers(new int[] {buffer >= 0x8CE0 && buffer < 0x8CF0 ? buffer - 0x8CE0 : -1});
	}

	private static void setDrawBuffers(int[] draw) {
		Fbo f = fbos.get(drawFb);
		if (f == null || java.util.Arrays.equals(f.draw, draw)) {
			return;
		}
		flushBatch();
		f.draw = draw;
		if (encFb == drawFb) {
			endEncoders();
		}
	}

	public static void glReadBuffer(int mode) {
		Fbo f = fbos.get(readFb);
		if (f != null && mode >= 0x8CE0 && mode < 0x8CF0) {
			f.read = mode - 0x8CE0;
		}
	}

	// ---- generic vertex attributes (shader programs)

	public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer buffer) {
		if (index >= 5 && index < SLOTS) {
			arrNorm[index] = normalized;
			setPointer(index, size, type, stride, address(buffer), 0);
		}
	}

	public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset) {
		if (index >= 5 && index < SLOTS) {
			arrNorm[index] = normalized;
			setPointer(index, size, type, stride, offset, arrayBuffer);
		}
	}

	public static void glEnableVertexAttribArray(int index) {
		if (index >= 5 && index < SLOTS) {
			arrOn[index] = true;
		}
	}

	public static void glDisableVertexAttribArray(int index) {
		if (index >= 5 && index < SLOTS) {
			arrOn[index] = false;
		}
	}

	private static long depthRead;

	/** glReadPixels of GL_DEPTH_COMPONENT floats (OptiFine's centerDepth): returns last frame's value, no stall. */
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
		if (format != 0x1902 || width != 1 || height != 1) {
			unsupported("glReadPixels float 0x" + Integer.toHexString(format));
			return;
		}
		flushBatch();
		if (depthRead == 0L) {
			depthRead = objc_msgSend(device, sel("newBufferWithLength:options:"), 4L, 0L);
			Objc.view(send(depthRead, "contents"), 4).putFloat(0, 1f);
		}
		pixels.put(pixels.position(), Objc.view(send(depthRead, "contents"), 4).getFloat(0));
		long depth = fbDepth(readFb);
		if (depth != 0L && x >= 0 && y >= 0 && x < (int)send(depth, "width") && y < (int)send(depth, "height")) {
			Objc.texToBuf(blit(), depth, 0, x, y, 1, 1, depthRead, 0, 4, 4);
		}
	}

	public static void glGenerateMipmap(int target) {
		Tex t = textures.get(bound[unit]);
		if (t != null && t.mtl != 0L && t.levels > 1) {
			flushBatch();
			send(blit(), "generateMipmapsForTexture:", t.mtl);
		}
	}

	public static int glGetError() {
		return 0;
	}

	public static int glGetInteger(int pname) {
		switch (pname) {
			case 0xD33: return 16384; // GL_MAX_TEXTURE_SIZE
			case 0x8069: return bound[unit]; // GL_TEXTURE_BINDING_2D
			case 0x84E2: return 2; // GL_MAX_TEXTURE_UNITS
			case 0x8872: case 0x8B4C: case 0x8869: return 16; // texture image units, vertex texture units, vertex attribs
			case 0x8B49: case 0x8B4A: return 4096; // fragment / vertex uniform components
			case 0x88FF: return 2048; // GL_MAX_ARRAY_TEXTURE_LAYERS
			case 0x8CDF: case 0x8824: return 8; // GL_MAX_COLOR_ATTACHMENTS, GL_MAX_DRAW_BUFFERS
			case 0x84E0: return 0x84C0 + unit; // GL_ACTIVE_TEXTURE
			case 0x8CA6: return drawFb; // GL_DRAW_FRAMEBUFFER_BINDING
			case 0x8CAA: return readFb; // GL_READ_FRAMEBUFFER_BINDING
			case 0x8B8D: return Programs.current != null ? Programs.current.id : 0; // GL_CURRENT_PROGRAM
			default:
				unsupported("glGetInteger 0x" + Integer.toHexString(pname));
				return 0;
		}
	}

	public static void glGetInteger(int pname, IntBuffer out) {
		if (pname == 0xBA2) { // GL_VIEWPORT
			int p = out.position();
			out.put(p, vx).put(p + 1, vy).put(p + 2, vw).put(p + 3, vh);
			return;
		}
		out.put(out.position(), glGetInteger(pname));
	}

	public static void glGetFloat(int pname, FloatBuffer out) {
		float[] m;
		switch (pname) {
			case 0xBA6: m = mvStack[mvTop]; break;
			case 0xBA7: m = prStack[prTop]; break;
			case 0xBA8: m = texStack[fx()][texTop[fx()]]; break;
			case 0xC22: // GL_COLOR_CLEAR_VALUE
				out.put(out.position(), (float)clearR).put(out.position() + 1, (float)clearG).put(out.position() + 2, (float)clearB)
					.put(out.position() + 3, (float)clearA);
				return;
			default:
				warnOnce("glGetFloat 0x" + Integer.toHexString(pname));
				return;
		}
		int p = out.position();
		for (int i = 0; i < 16; i++) {
			out.put(p + i, m[i]);
		}
	}

	// ---- matrices

	public static void glMatrixMode(final int mode) {
		if (record(new Op() {
			public void run() {
				glMatrixMode(mode);
			}
		})) {
			return;
		}
		matrixMode = mode;
	}

	public static void glLoadIdentity() {
		if (record(new Op() {
			public void run() {
				glLoadIdentity();
			}
		})) {
			return;
		}
		identity(mat());
	}

	public static void glPushMatrix() {
		if (record(new Op() {
			public void run() {
				glPushMatrix();
			}
		})) {
			return;
		}
		switch (matrixMode) {
			case 0x1701:
				if (prTop + 1 < prStack.length) {
					System.arraycopy(prStack[prTop], 0, prStack[++prTop], 0, 16);
				}
				break;
			case 0x1702:
				if (texTop[fx()] + 1 < 16) {
					System.arraycopy(texStack[fx()][texTop[fx()]], 0, texStack[fx()][++texTop[fx()]], 0, 16);
				}
				break;
			default:
				if (mvTop + 1 < mvStack.length) {
					System.arraycopy(mvStack[mvTop], 0, mvStack[++mvTop], 0, 16);
				}
		}
	}

	public static void glPopMatrix() {
		if (record(new Op() {
			public void run() {
				glPopMatrix();
			}
		})) {
			return;
		}
		switch (matrixMode) {
			case 0x1701: if (prTop > 0) prTop--; break;
			case 0x1702: if (texTop[fx()] > 0) texTop[fx()]--; break;
			default: if (mvTop > 0) mvTop--;
		}
	}

	public static void glTranslatef(final float x, final float y, final float z) {
		if (record(new Op() {
			public void run() {
				glTranslatef(x, y, z);
			}
		})) {
			return;
		}
		float[] m = mat();
		for (int r = 0; r < 4; r++) {
			m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z;
		}
	}

	public static void glTranslated(double x, double y, double z) {
		glTranslatef((float)x, (float)y, (float)z);
	}

	public static void glScalef(final float x, final float y, final float z) {
		if (record(new Op() {
			public void run() {
				glScalef(x, y, z);
			}
		})) {
			return;
		}
		float[] m = mat();
		for (int r = 0; r < 4; r++) {
			m[r] *= x;
			m[4 + r] *= y;
			m[8 + r] *= z;
		}
	}

	public static void glScaled(double x, double y, double z) {
		glScalef((float)x, (float)y, (float)z);
	}

	public static void glRotatef(final float angle, final float ax, final float ay, final float az) {
		if (record(new Op() {
			public void run() {
				glRotatef(angle, ax, ay, az);
			}
		})) {
			return;
		}
		float len = (float)Math.sqrt(ax * ax + ay * ay + az * az);
		if (len == 0) {
			return;
		}
		float x = ax / len, y = ay / len, z = az / len;
		double rad = Math.toRadians(angle);
		float c = (float)Math.cos(rad), s = (float)Math.sin(rad), t = 1 - c;
		float[] r = {
			x * x * t + c, y * x * t + z * s, x * z * t - y * s, 0,
			x * y * t - z * s, y * y * t + c, y * z * t + x * s, 0,
			x * z * t + y * s, y * z * t - x * s, z * z * t + c, 0,
			0, 0, 0, 1};
		multCur(r);
	}

	public static void glMultMatrix(FloatBuffer m) {
		final float[] v = floats(m, 16);
		if (record(new Op() {
			public void run() {
				multCur(v);
			}
		})) {
			return;
		}
		multCur(v);
	}

	public static void glOrtho(final double l, final double r, final double b, final double t, final double n, final double f) {
		if (record(new Op() {
			public void run() {
				glOrtho(l, r, b, t, n, f);
			}
		})) {
			return;
		}
		float[] o = new float[16];
		o[0] = (float)(2 / (r - l));
		o[5] = (float)(2 / (t - b));
		o[10] = (float)(-2 / (f - n));
		o[12] = (float)(-(r + l) / (r - l));
		o[13] = (float)(-(t + b) / (t - b));
		o[14] = (float)(-(f + n) / (f - n));
		o[15] = 1;
		multCur(o);
	}

	/** org.lwjgl.util.glu.Project / GLU.gluPerspective: GLU lives in LWJGL (not transformed), so its own GL calls are redirected here. */
	public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
		float f = (float)(1 / Math.tan(Math.toRadians(fovy) / 2));
		float[] p = new float[16];
		p[0] = f / aspect;
		p[5] = f;
		p[10] = (zFar + zNear) / (zNear - zFar);
		p[11] = -1;
		p[14] = 2 * zFar * zNear / (zNear - zFar);
		multCur(p);
	}

	// ---- lighting and fog

	public static void glLight(int light, int pname, FloatBuffer params) {
		final int l = light, pn = pname;
		final float[] v = floats(params, 4);
		if (record(new Op() {
			public void run() {
				light(l, pn, v);
			}
		})) {
			return;
		}
		light(l, pn, v);
	}

	private static void light(int light, int pname, float[] v) {
		if (light != 0x4000 && light != 0x4001) {
			return;
		}
		boolean l0 = light == 0x4000;
		switch (pname) {
			case 0x1203: { // GL_POSITION, stored in eye space
				float[] m = mvStack[mvTop];
				float[] d = l0 ? st.l0dir : st.l1dir;
				for (int r = 0; r < 3; r++) {
					d[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3];
				}
				float len = (float)Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
				if (len > 0) {
					d[0] /= len;
					d[1] /= len;
					d[2] /= len;
				}
				break;
			}
			case 0x1201: System.arraycopy(v, 0, l0 ? st.l0diff : st.l1diff, 0, 4); break;
			case 0x1200: System.arraycopy(v, 0, l0 ? st.l0amb : st.l1amb, 0, 4); break;
			default:
		}
	}

	public static void glLightModel(int pname, FloatBuffer params) {
		final float[] v = floats(params, 4);
		if (pname != 0xB53) {
			return;
		}
		if (record(new Op() {
			public void run() {
				System.arraycopy(v, 0, st.modelAmb, 0, 4);
			}
		})) {
			return;
		}
		System.arraycopy(v, 0, st.modelAmb, 0, 4);
	}

	public static void glFogi(int pname, int param) {
		fog(pname, param);
	}

	public static void glFogf(int pname, float param) {
		fog(pname, param);
	}

	private static void fog(final int pname, final float v) {
		if (record(new Op() {
			public void run() {
				fog(pname, v);
			}
		})) {
			return;
		}
		switch (pname) {
			case 0xB65: st.fogMode = (int)v; break;
			case 0xB62: st.fogDensity = v; break;
			case 0xB63: st.fogStart = v; break;
			case 0xB64: st.fogEnd = v; break;
			default:
		}
	}

	public static void glFog(int pname, FloatBuffer params) {
		if (pname == 0xB66) {
			final float[] v = floats(params, 4);
			if (record(new Op() {
				public void run() {
					System.arraycopy(v, 0, st.fogColor, 0, 4);
				}
			})) {
				return;
			}
			System.arraycopy(v, 0, st.fogColor, 0, 4);
		} else {
			fog(pname, params.get(params.position()));
		}
	}

	// ---- current vertex attributes and immediate mode

	public static void glColor4f(final float r, final float g, final float b, final float a) {
		if (immMode < 0 && record(new Op() {
			public void run() {
				glColor4f(r, g, b, a);
			}
		})) {
			return;
		}
		color[0] = r;
		color[1] = g;
		color[2] = b;
		color[3] = a;
	}

	public static void glColor3ub(byte r, byte g, byte b) {
		glColor4f((r & 255) / 255f, (g & 255) / 255f, (b & 255) / 255f, 1);
	}

	public static void glNormal3f(final float x, final float y, final float z) {
		if (immMode < 0 && record(new Op() {
			public void run() {
				glNormal3f(x, y, z);
			}
		})) {
			return;
		}
		normal[0] = x;
		normal[1] = y;
		normal[2] = z;
	}

	public static void glTexCoord2f(final float s, final float t) {
		if (immMode < 0 && record(new Op() {
			public void run() {
				glTexCoord2f(s, t);
			}
		})) {
			return;
		}
		tc0[0] = s;
		tc0[1] = t;
	}

	public static void glMultiTexCoord2f(final int target, final float s, final float t) {
		if (immMode < 0 && record(new Op() {
			public void run() {
				glMultiTexCoord2f(target, s, t);
			}
		})) {
			return;
		}
		float[] tc = target == 0x84C1 ? tc1 : target == 0x84C0 ? tc0 : null;
		if (tc != null) {
			tc[0] = s;
			tc[1] = t;
		}
	}

	public static void glMultiTexCoord2fARB(int target, float s, float t) {
		glMultiTexCoord2f(target, s, t);
	}

	public static void glBegin(int mode) {
		immMode = mode;
		immCount = 0;
		imm.clear();
	}

	public static void glVertex2f(float x, float y) {
		vertex(x, y, 0);
	}

	public static void glVertex3f(float x, float y, float z) {
		vertex(x, y, z);
	}

	private static final int[] IMM_FMT = {30, 9, 29, 29, 12}, IMM_OFF = {0, 12, 16, 24, 32};

	public static void glEnd() {
		int mode = immMode;
		immMode = -1;
		if (immCount == 0) {
			return;
		}
		if (mode == 5 && immCount == 4) { // a 4-vertex strip (font glyph) is a quad: swap the last two vertices
			for (int i = 0; i < 36; i++) {
				byte t = imm.get(72 + i);
				imm.put(72 + i, imm.get(108 + i));
				imm.put(108 + i, t);
			}
			mode = 7;
		}
		submit(mode, immCount, 36, IMM_FMT, IMM_OFF, address(imm) - imm.position());
	}

	// ---- client arrays

	private static int arrayIndex(int cap) {
		switch (cap) {
			case 0x8074: return POS;
			case 0x8076: return COL;
			case 0x8075: return NRM;
			case 0x8078: return clientUnit == 0 ? TEX0 : clientUnit == 1 ? TEX1 : -1;
			default: return -1;
		}
	}

	public static void glEnableClientState(int cap) {
		int i = arrayIndex(cap);
		if (i >= 0) {
			arrOn[i] = true;
		}
	}

	public static void glDisableClientState(int cap) {
		int i = arrayIndex(cap);
		if (i >= 0) {
			arrOn[i] = false;
		}
	}

	public static void glClientActiveTexture(int texture) {
		clientUnit = Math.max(0, Math.min(UNITS - 1, texture - 0x84C0));
	}

	public static void glClientActiveTextureARB(int texture) {
		glClientActiveTexture(texture);
	}

	public static void glVertexPointer(int size, int type, int stride, ByteBuffer pointer) {
		setPointer(POS, size, type, stride, address(pointer), 0);
	}

	public static void glVertexPointer(int size, int type, int stride, long offset) {
		setPointer(POS, size, type, stride, offset, arrayBuffer);
	}

	public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) {
		setPointer(COL, size, type, stride, address(pointer), 0);
	}

	public static void glColorPointer(int size, int type, int stride, long offset) {
		setPointer(COL, size, type, stride, offset, arrayBuffer);
	}

	public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer pointer) {
		if (clientUnit > 1) {
			return;
		}
		setPointer(clientUnit == 0 ? TEX0 : TEX1, size, type, stride, address(pointer), 0);
	}

	public static void glTexCoordPointer(int size, int type, int stride, long offset) {
		if (clientUnit > 1) {
			return;
		}
		setPointer(clientUnit == 0 ? TEX0 : TEX1, size, type, stride, offset, arrayBuffer);
	}

	public static void glNormalPointer(int type, int stride, ByteBuffer pointer) {
		setPointer(NRM, 3, type, stride, address(pointer), 0);
	}

	public static void glNormalPointer(int type, int stride, long offset) {
		setPointer(NRM, 3, type, stride, offset, arrayBuffer);
	}

	// Older LWJGL overloads (1.7.10): type implied by the buffer.
	public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
		setPointer(POS, size, 0x1406, stride, address(pointer), 0);
	}

	public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
		setPointer(COL, size, unsigned ? 0x1401 : 0x1400, stride, address(pointer), 0);
	}

	public static void glNormalPointer(int stride, ByteBuffer pointer) {
		setPointer(NRM, 3, 0x1400, stride, address(pointer), 0);
	}

	public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
		if (clientUnit <= 1) {
			setPointer(clientUnit == 0 ? TEX0 : TEX1, size, 0x1406, stride, address(pointer), 0);
		}
	}

	public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) {
		if (clientUnit <= 1) {
			setPointer(clientUnit == 0 ? TEX0 : TEX1, size, 0x1402, stride, address(pointer), 0);
		}
	}

	public static void glCallLists(IntBuffer lists) {
		for (int i = lists.position(); i < lists.limit(); i++) {
			glCallList(lists.get(i));
		}
	}

	// Occlusion queries are not advertised; if asked anyway, everything is visible.
	public static void glGenQueriesARB(IntBuffer ids) {
	}

	public static void glBeginQueryARB(int target, int id) {
	}

	public static void glEndQueryARB(int target) {
	}

	public static void glGetQueryObjectuARB(int id, int pname, IntBuffer out) {
		out.put(out.position(), 1);
	}

	public static int glGetTexParameteri(int target, int pname) {
		Tex t = textures.get(bound[unit]);
		if (t == null) {
			return 0;
		}
		switch (pname) {
			case 0x2801: return t.minF;
			case 0x2800: return t.magF;
			case 0x2802: return t.wrapS;
			case 0x2803: return t.wrapT;
			case 0x813D: return t.maxLevel;
			default: return 0;
		}
	}

	public static float glGetTexParameterf(int target, int pname) {
		return glGetTexParameteri(target, pname);
	}

	public static void glDrawArrays(int mode, int first, int count) {
		drawArraysImpl(mode, first, count);
	}

	// ---- display lists

	public static int glGenLists(int range) {
		int base = nextList;
		nextList += range;
		return base;
	}

	public static void glNewList(int list, int mode) {
		flushBatch();
		DList old = lists.remove(list);
		release(old);
		rec = new DList();
		recId = list;
	}

	public static void glEndList() {
		DList l = rec;
		rec = null;
		if (l == null) {
			return;
		}
		if (l.data != null && l.data.position() > 0) {
			l.buf = objc_msgSend(device, sel("newBufferWithBytes:length:options:"), Objc.address(l.data), (long)l.data.position(), 0L);
		}
		l.data = null;
		lists.put(recId, l);
	}

	public static void glCallList(final int list) {
		if (record(new Op() {
			public void run() {
				glCallList(list);
			}
		})) {
			return;
		}
		DList l = lists.get(list);
		if (l != null) {
			for (int i = 0, n = l.ops.size(); i < n; i++) {
				l.ops.get(i).run();
			}
		}
	}

	public static void glDeleteLists(int list, int range) {
		for (int i = 0; i < range; i++) {
			release(lists.remove(list + i));
		}
	}

	private static void release(DList l) {
		if (l != null && l.buf != 0L) {
			send(l.buf, "release"); // command buffers retain what they use
			l.buf = 0L;
		}
	}

	// ---- textures

	public static int glGenTextures() {
		return nextTex++;
	}

	public static void glDeleteTextures(int id) {
		flushBatch();
		Tex t = textures.remove(id);
		if (t != null && t.mtl != 0L) {
			send(t.mtl, "release");
		}
	}

	public static void glBindTexture(final int target, final int id) {
		if (record(new Op() {
			public void run() {
				glBindTexture(target, id);
			}
		})) {
			return;
		}
		bound[unit] = id;
	}

	public static void glActiveTexture(int texture) {
		unit = Math.max(0, Math.min(BIND_UNITS - 1, texture - 0x84C0));
	}

	public static void glActiveTextureARB(int texture) {
		glActiveTexture(texture);
	}

	public static void glTexParameteri(int target, int pname, int param) {
		flushBatch();
		Tex t = tex(bound[unit]);
		if (t == null) {
			return;
		}
		switch (pname) {
			case 0x2801: t.minF = param; break;
			case 0x2800: t.magF = param; break;
			case 0x2802: t.wrapS = param; break;
			case 0x2803: t.wrapT = param; break;
			case 0x813D: t.maxLevel = param; break;
			case 0x884C: t.compareMode = param; t.compareSampler = 0L; return; // GL_TEXTURE_COMPARE_MODE
			case 0x884D: t.compareFunc = param; t.compareSampler = 0L; return; // GL_TEXTURE_COMPARE_FUNC
			case 0x84FE: t.aniso = Integer.highestOneBit(Math.max(1, Math.min(16, param))); t.compareSampler = 0L; break; // GL_TEXTURE_MAX_ANISOTROPY
			default: return;
		}
		t.sampler = 0L;
	}

	public static void glTexParameterf(int target, int pname, float param) {
		glTexParameteri(target, pname, (int)param);
	}

	public static void glTexEnvi(final int target, final int pname, final int param) {
		if (record(new Op() {
			public void run() {
				glTexEnvi(target, pname, param);
			}
		})) {
			return;
		}
		if (pname == 0x2200) {
			st.env[fx()] = param;
		} else if (pname == 0x8571) {
			st.comb[fx()] = param;
		}
	}

	public static void glTexEnvf(int target, int pname, float param) {
		glTexEnvi(target, pname, (int)param);
	}

	public static void glPolygonMode(int face, int mode) {
	}

	public static void glTexEnv(int target, int pname, FloatBuffer params) {
		if (pname == 0x2201) {
			final float[] v = floats(params, 4);
			final int u = fx();
			if (record(new Op() {
				public void run() {
					System.arraycopy(v, 0, st.envColor[u], 0, 4);
				}
			})) {
				return;
			}
			System.arraycopy(v, 0, st.envColor[u], 0, 4);
		}
	}

	public static void glPixelStorei(int pname, int param) {
	}

	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
		texImage(target, level, internalformat, width, height, format, type, pixels);
	}

	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
		texImage(target, level, internalformat, width, height, format, type, null); // float data: render targets (null) only
	}

	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
		texImage(target, level, internalformat, width, height, format, type, pixels);
	}

	public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
		texSubImage(level, x, y, width, height, format, type, pixels);
	}

	public static int glGetTexLevelParameteri(int target, int level, int pname) {
		if (target == 0x8064) {
			return pname == 0x1000 ? proxyW : pname == 0x1001 ? proxyH : 0;
		}
		Tex t = textures.get(bound[unit]);
		if (t == null) {
			return 0;
		}
		return pname == 0x1000 ? Math.max(1, t.w >> level) : pname == 0x1001 ? Math.max(1, t.h >> level) : 0;
	}

	public static void glGetTexImage(int target, int level, int format, int type, IntBuffer pixels) {
		Tex t = textures.get(bound[unit]);
		if (t != null) {
			readback(t.mtl, level, 0, 0, Math.max(1, t.w >> level), Math.max(1, t.h >> level), address(pixels), format == 0x1908);
		}
	}

	public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
		readback(fbColor(readFb), 0, x, y, width, height, address(pixels), format == 0x1908);
	}

	public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
		flushBatch();
		Tex t = textures.get(bound[unit]);
		long src = t != null && t.format == DEPTH32F ? fbDepth(readFb) : fbColor(readFb); // depth textures copy the depth buffer
		if (t == null || t.mtl == 0L || src == 0L || width <= 0 || height <= 0) {
			return;
		}
		Objc.texToTex(blit(), src, x, y, width, height, t.mtl, level, xoffset, yoffset);
	}

	// ================================================================ GL15 / ARB vertex buffer objects

	public static int glGenBuffers() {
		return nextBuffer++;
	}

	public static void glBindBuffer(int target, int buffer) {
		if (target == 0x8892) {
			arrayBuffer = buffer;
		}
	}

	public static void glBufferData(int target, ByteBuffer data, int usage) {
		if (target != 0x8892 || arrayBuffer == 0) {
			return;
		}
		Vbo v = vbos.get(arrayBuffer);
		if (v == null) {
			vbos.put(arrayBuffer, v = new Vbo());
		}
		if (v.buf != 0L) {
			send(v.buf, "release");
			v.buf = 0L;
		}
		if (data != null && data.remaining() > 0) {
			v.buf = objc_msgSend(device, sel("newBufferWithBytes:length:options:"), address(data), (long)data.remaining(), 0L);
			v.cpu = send(v.buf, "contents");
		}
	}

	public static void glDeleteBuffers(int buffer) {
		Vbo v = vbos.remove(buffer);
		if (v != null && v.buf != 0L) {
			send(v.buf, "release");
		}
	}

	public static int glGenBuffersARB() {
		return glGenBuffers();
	}

	public static void glBindBufferARB(int target, int buffer) {
		glBindBuffer(target, buffer);
	}

	public static void glBufferDataARB(int target, ByteBuffer data, int usage) {
		glBufferData(target, data, usage);
	}

	public static void glDeleteBuffersARB(int buffer) {
		glDeleteBuffers(buffer);
	}

	// ================================================================ GL30 / ARB / EXT framebuffer objects

	public static int glGenFramebuffers() {
		fbos.put(nextFbo, new Fbo());
		return nextFbo++;
	}

	public static void glBindFramebuffer(int target, int fb) {
		flushBatch();
		if (scaledFor >= 0 && fb == scaledFor) {
			fb = SCALED_FB; // render scale: the world draws into the smaller framebuffer
		}
		if (dev.graft.legacy.shaders.ShaderPipeline.active && fb == dev.graft.legacy.shaders.ShaderPipeline.mainFb) {
			fb = dev.graft.legacy.shaders.ShaderPipeline.gbufferFb; // shader pack: the world draws into the gbuffers
		}
		if (target != 0x8CA8) {
			drawFb = fb;
		}
		if (target != 0x8CA9) {
			readFb = fb;
		}
	}

	public static void glDeleteFramebuffers(int fb) {
		fbos.remove(fb);
		if (encFb == fb) {
			endEncoders();
		}
	}

	public static int glCheckFramebufferStatus(int target) {
		return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
	}

	public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
		flushBatch();
		int fb = target == 0x8CA8 ? readFb : drawFb;
		Fbo f = fbos.get(fb);
		if (f == null || fb == SCALED_FB) { // render scale: the stand-in keeps its own (smaller) attachments
			return;
		}
		if (attachment >= 0x8CE0 && attachment < 0x8CF0) {
			f.color[attachment - 0x8CE0] = texture;
		} else if (attachment == 0x8D00 || attachment == 0x821A) {
			f.depthTex = texture;
			f.depth = 0;
		}
		if (encFb == drawFb) {
			endEncoders();
		}
	}

	public static int glGenRenderbuffers() {
		return nextRb++;
	}

	public static void glBindRenderbuffer(int target, int rb) {
		boundRb = rb;
	}

	public static void glDeleteRenderbuffers(int rb) {
		Long d = renderbuffers.remove(rb);
		if (d != null) {
			send(d, "release");
		}
	}

	public static void glRenderbufferStorage(int target, int internalformat, int width, int height) {
		flushBatch();
		if (boundRb == 0) {
			return;
		}
		Long old = renderbuffers.remove(boundRb);
		if (old != null) {
			send(old, "release");
		}
		renderbuffers.put(boundRb, newTexture(Math.max(1, width), Math.max(1, height), 1, DEPTH32F));
	}

	public static void glFramebufferRenderbuffer(int target, int attachment, int rbTarget, int rb) {
		Fbo f = fbos.get(target == 0x8CA8 ? readFb : drawFb);
		if (f != null && (attachment == 0x8D00 || attachment == 0x821A)) {
			f.depth = rb;
			f.depthTex = 0;
			if (encFb == drawFb) {
				endEncoders();
			}
		}
	}

	public static int glGenFramebuffersEXT() {
		return glGenFramebuffers();
	}

	public static void glBindFramebufferEXT(int target, int fb) {
		glBindFramebuffer(target, fb);
	}

	public static void glDeleteFramebuffersEXT(int fb) {
		glDeleteFramebuffers(fb);
	}

	public static int glCheckFramebufferStatusEXT(int target) {
		return 0x8CD5;
	}

	public static void glFramebufferTexture2DEXT(int target, int attachment, int textarget, int texture, int level) {
		glFramebufferTexture2D(target, attachment, textarget, texture, level);
	}

	public static int glGenRenderbuffersEXT() {
		return glGenRenderbuffers();
	}

	public static void glBindRenderbufferEXT(int target, int rb) {
		glBindRenderbuffer(target, rb);
	}

	public static void glDeleteRenderbuffersEXT(int rb) {
		glDeleteRenderbuffers(rb);
	}

	public static void glRenderbufferStorageEXT(int target, int internalformat, int width, int height) {
		glRenderbufferStorage(target, internalformat, width, height);
	}

	public static void glFramebufferRenderbufferEXT(int target, int attachment, int rbTarget, int rb) {
		glFramebufferRenderbuffer(target, attachment, rbTarget, rb);
	}

	// Last in the class: static initializers run in order and this one uses the fields above.
	static {
		long dev = Objc.createDevice();
		if (dev == 0L) {
			throw new IllegalStateException("Graft: no Metal device");
		}
		device = dev;
		pool = objc_autoreleasePoolPush();
		queue = send(device, "newCommandQueue");
		long lib = library();
		vfn = send(lib, "newFunctionWithName:", nsString("vmain"));
		ffn = send(lib, "newFunctionWithName:", nsString("fmain"));
		tvfn = send(lib, "newFunctionWithName:", nsString("tvert"));
		long pd = send(send(cls("MTLRenderPipelineDescriptor"), "alloc"), "init");
		send(pd, "setVertexFunction:", send(lib, "newFunctionWithName:", nsString("bvert")));
		send(pd, "setFragmentFunction:", send(lib, "newFunctionWithName:", nsString("bfrag")));
		send(attachment(pd), "setPixelFormat:", BGRA8);
		presentPipe = newPipeline(pd);
		white = newTexture(1, 1, 1, BGRA8);
		ByteBuffer px = ByteBuffer.allocateDirect(4);
		px.putInt(0, -1);
		upload(white, 0, 0, 0, 1, 1, Objc.address(px), false);
		LOG.info("Graft: Metal renderer active on {}", string(send(device, "name")));
	}

	private MGL() {
	}
}
