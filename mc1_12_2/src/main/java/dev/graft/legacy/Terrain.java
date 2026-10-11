package dev.graft.legacy;

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
		if (mc.isCallingFromMinecraftThread()) {
			store(layer, wr, chunk);
			return Futures.immediateFuture(null);
		}
		// Our own future: the chunk worker cancels stale uploads, and a cancelled task in Minecraft's queue would crash Util.runTask.
		final SettableFuture<Object> done = SettableFuture.create();
		mc.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				if (!done.isCancelled()) {
					store(layer, wr, chunk);
					done.set(null);
				}
			}
		});
		return done;
	}

	private static void store(BlockRenderLayer layer, BufferBuilder wr, RenderChunk chunk) {
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

	private Terrain() {
	}
}
