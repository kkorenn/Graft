package dev.graft.legacy.shaders;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Headless microbenchmark of the shader path's per-vertex / per-draw Java work: the chunk vertex expansion (chunk
 * builder threads), block tagging, and the per-draw uniform path when Metal is available. Reports time and bytes
 * allocated (must be 0 on the hot paths). ./gradlew :mc1_8_9:shaderBench
 */
public final class ShaderBench {
	public static void main(String[] args) throws Exception {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
		long tid = Thread.currentThread().getId();
		int n = 65536; // vertices: a dense chunk layer is ~10-40k
		ByteBuffer src = ByteBuffer.allocateDirect(n * TerrainFormat.VANILLA).order(ByteOrder.nativeOrder());
		java.util.Random r = new java.util.Random(1);
		for (int i = 0; i < n; i++) {
			int o = i * TerrainFormat.VANILLA;
			src.putFloat(o, r.nextFloat() * 16).putFloat(o + 4, r.nextFloat() * 16).putFloat(o + 8, r.nextFloat() * 16).putInt(o + 12, -1)
				.putFloat(o + 16, r.nextFloat()).putFloat(o + 20, r.nextFloat()).putShort(o + 24, (short)240).putShort(o + 26, (short)240);
		}
		ByteBuffer dst = ByteBuffer.allocateDirect(n * TerrainFormat.SHADER).order(ByteOrder.nativeOrder());
		for (int i = 0; i < 50; i++) { // warm up the JIT
			TerrainFormat.tag(src, 0, n, 10008);
			TerrainFormat.extend(src, dst, n);
		}
		int runs = 200;
		long a0 = mx.getThreadAllocatedBytes(tid), t0 = System.nanoTime();
		for (int i = 0; i < runs; i++) {
			TerrainFormat.tag(src, 0, n, i);
			TerrainFormat.extend(src, dst, n);
		}
		long t1 = System.nanoTime(), a1 = mx.getThreadAllocatedBytes(tid);
		System.out.printf("chunk vertices -> shader layout: %.2f ns/vertex (%.2f ms per 32k-vertex chunk layer), %d bytes allocated in %d runs%n",
			(t1 - t0) / (double)runs / n, (t1 - t0) / (double)runs / n * 32768 / 1e6, a1 - a0, runs);
		IdMap ids = IdMap.parse("block.10008=water flowing_water\nblock.10005=tallgrass:1 double_plant:half=lower\n", "block");
		java.util.Map<String, String> props = new java.util.HashMap<String, String>();
		props.put("half", "lower");
		for (int i = 0; i < 100000; i++) {
			ids.get("minecraft:double_plant", 175, 0, props);
		}
		a0 = mx.getThreadAllocatedBytes(tid);
		t0 = System.nanoTime();
		int hits = 0;
		for (int i = 0; i < 1000000; i++) {
			hits += ids.get("minecraft:double_plant", 175, i & 15, props) > 0 ? 1 : 0;
		}
		t1 = System.nanoTime();
		a1 = mx.getThreadAllocatedBytes(tid);
		System.out.printf("block.properties lookup (table build, loader thread): %.1f ns each, %d bytes allocated (%d hits)%n", (t1 - t0) / 1e6, a1 - a0, hits);
		System.out.println("per-draw uniform path (Programs/MGL): needs a Metal device and the runtime shader compiler; "
			+ "zero-allocation by construction (Programs.f/i, MGL memcpy copies, pipeline fast path) - measure in-game with -Dgraft.dumpFrame");
	}
}
