package dev.graft.legacy;

import dev.graft.legacy.shaders.ShaderPipeline;
import dev.graft.legacy.shaders.TerrainFormat;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.BlockRenderLayer;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Native Metal terrain: chunk meshes go from Minecraft's chunk builder straight into Metal buffers (no display lists,
 * no VBOs, no GL), and each visible chunk layer is one Metal draw. Hooked by {@link GlTransformer}:
 * ChunkRenderDispatcher.uploadChunk -> {@link #upload}, ChunkRenderContainer.renderChunkLayer -> {@link #render},
 * RenderChunk.deleteGlResources -> {@link #delete}.
 */
public final class Terrain {
	// DefaultVertexFormats.BLOCK: pos 3f, color 4ub, uv 2f, lightmap 2s (28 bytes); with OptiFine shaders also normal,
	// mc_midTexCoord, at_tangent and mc_Entity (56 bytes, SVertexFormat).
	private static final IdentityHashMap<RenderChunk, int[]> strides = new IdentityHashMap<RenderChunk, int[]>();
	private static final int LAYERS = BlockRenderLayer.values().length;
	private static final IdentityHashMap<RenderChunk, long[]> buffers = new IdentityHashMap<RenderChunk, long[]>();
	private static final IdentityHashMap<RenderChunk, int[]> counts = new IdentityHashMap<RenderChunk, int[]>();
	private static final Field CHUNKS = field("renderChunks", "field_178009_a"), INITIALIZED = field("initialized", "field_178007_b"),
		VX = field("viewEntityX", "field_178008_c"), VY = field("viewEntityY", "field_178005_d"), VZ = field("viewEntityZ", "field_178006_e");
	private static long[] drawBufs = new long[256];
	private static int[] drawCounts = new int[256];
	private static float[] drawOffs = new float[256 * 4];

	private static Field field(String dev, String srg) {
		for (String name : new String[] {dev, srg}) {
			try {
				Field f = ChunkRenderContainer.class.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new IllegalStateException("Graft: ChunkRenderContainer." + dev + " not found");
	}

	public static ListenableFuture<Object> upload(final BlockRenderLayer layer, final BufferBuilder wr, final RenderChunk chunk, CompiledChunk compiled, double distanceSq) {
		Minecraft mc = Minecraft.getMinecraft();
		// shader pack: the shader vertex layout is built and uploaded here, on the chunk worker
		final int n = wr.getVertexCount();
		final long ext = ShaderPipeline.extendedTerrain && n > 0 && wr.getVertexFormat().getSize() == TerrainFormat.VANILLA ? extend(wr, n) : 0L;
		if (mc.isCallingFromMinecraftThread()) {
			store(layer, wr, chunk, ext, n);
			return Futures.immediateFuture(null);
		}
		// Our own future: the chunk worker cancels stale uploads, and a cancelled task in Minecraft's queue would crash Util.runTask.
		final SettableFuture<Object> done = SettableFuture.create();
		mc.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				if (!done.isCancelled()) {
					store(layer, wr, chunk, ext, n);
					done.set(null);
				} else {
					MGL.releaseBuffer(ext);
				}
			}
		});
		return done;
	}

	private static final ThreadLocal<ByteBuffer> scratch = new ThreadLocal<ByteBuffer>();

	/** Vanilla chunk vertices -> a Metal buffer in the shader layout (any thread; one scratch buffer per worker). */
	private static long extend(BufferBuilder wr, int n) {
		ByteBuffer dst = scratch.get();
		if (dst == null || dst.capacity() < n * TerrainFormat.SHADER) {
			dst = ByteBuffer.allocateDirect(Math.max(n * TerrainFormat.SHADER, 1 << 20)).order(ByteOrder.nativeOrder());
			scratch.set(dst);
		}
		TerrainFormat.extend(wr.getByteBuffer(), dst, n);
		return MGL.newBufferAnyThread(dst, (long)n * TerrainFormat.SHADER);
	}

	private static void store(BlockRenderLayer layer, BufferBuilder wr, RenderChunk chunk, long ext, int extCount) {
		long[] b = buffers.get(chunk);
		int[] c = counts.get(chunk);
		if (b == null) {
			buffers.put(chunk, b = new long[LAYERS]);
			counts.put(chunk, c = new int[LAYERS]);
			strides.put(chunk, new int[LAYERS]);
		}
		int i = layer.ordinal();
		MGL.releaseBuffer(b[i]);
		b[i] = 0L;
		c[i] = 0;
		if (ext != 0L) {
			b[i] = ext;
			c[i] = extCount;
			strides.get(chunk)[i] = TerrainFormat.SHADER;
			return;
		}
		int n = wr.getVertexCount();
		if (n == 0) {
			return;
		}
		int size = wr.getVertexFormat().getSize();
		if (size != 28 && size != 56) {
			MGL.LOG.warn("Graft: unexpected chunk vertex format {}", wr.getVertexFormat());
			return;
		}
		b[i] = MGL.newBuffer(wr.getByteBuffer(), (long)n * size);
		c[i] = n;
		strides.get(chunk)[i] = size;
	}

	public static void delete(RenderChunk chunk) {
		long[] b = buffers.remove(chunk);
		counts.remove(chunk);
		strides.remove(chunk);
		if (b != null) {
			for (long buf : b) {
				MGL.releaseBuffer(buf);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void render(ChunkRenderContainer container, BlockRenderLayer layer) {
		try {
			if (!INITIALIZED.getBoolean(container)) {
				return;
			}
			List<RenderChunk> chunks = (List<RenderChunk>)CHUNKS.get(container);
			double vx = VX.getDouble(container), vy = VY.getDouble(container), vz = VZ.getDouble(container);
			int li = layer.ordinal(), n = 0, stride = 0;
			if (drawBufs.length < chunks.size()) {
				drawBufs = new long[chunks.size()];
				drawCounts = new int[chunks.size()];
				drawOffs = new float[chunks.size() * 4];
			}
			for (RenderChunk chunk : chunks) {
				long[] b = buffers.get(chunk);
				if (b == null || b[li] == 0L) {
					continue;
				}
				int st = strides.get(chunk)[li];
				if (stride == 0) {
					stride = st;
				} else if (st != stride) {
					continue; // mid-switch between formats (shaders toggled): skip until the chunk is rebuilt
				}
				BlockPos p = chunk.getPosition();
				drawBufs[n] = b[li];
				drawCounts[n] = counts.get(chunk)[li];
				drawOffs[n * 4] = (float)(p.getX() - vx);
				drawOffs[n * 4 + 1] = (float)(p.getY() - vy);
				drawOffs[n * 4 + 2] = (float)(p.getZ() - vz);
				n++;
			}
			MGL.drawTerrain(drawBufs, drawCounts, drawOffs, n, stride);
			chunks.clear();
			GlStateManager.resetColor();
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Shader-pack shadow pass: the solid and cutout layers (or the translucent one) of every built chunk the shadow view can
	 * see, drawn with the current (shadow) matrices relative to the camera at (cx, cy, cz).
	 */
	public static void drawShadow(double cx, double cy, double cz, boolean translucent) {
		int max = buffers.size();
		if (drawBufs.length < max) {
			drawBufs = new long[max];
			drawCounts = new int[max];
			drawOffs = new float[max * 4];
		}
		for (int li = translucent ? 3 : 0; li < (translucent ? 4 : 3); li++) { // SOLID, CUTOUT_MIPPED, CUTOUT | TRANSLUCENT
			int n = 0, stride = 0;
			for (java.util.Map.Entry<RenderChunk, long[]> e : buffers.entrySet()) {
				long b = e.getValue()[li];
				if (b == 0L) {
					continue;
				}
				int st = strides.get(e.getKey())[li];
				if (stride == 0) {
					stride = st;
				} else if (st != stride) {
					continue;
				}
				BlockPos p = e.getKey().getPosition();
				double rx = p.getX() - cx, ry = p.getY() - cy, rz = p.getZ() - cz;
				if (!dev.graft.legacy.shaders.ShaderPipeline.inShadowView(rx + 8, ry + 8, rz + 8)) {
					continue;
				}
				drawBufs[n] = b;
				drawCounts[n] = counts.get(e.getKey())[li];
				drawOffs[n * 4] = (float)rx;
				drawOffs[n * 4 + 1] = (float)ry;
				drawOffs[n * 4 + 2] = (float)rz;
				n++;
			}
			MGL.drawTerrain(drawBufs, drawCounts, drawOffs, n, stride);
		}
	}

	private Terrain() {
	}
}
