package dev.graft.legacy.shaders;

import java.nio.ByteBuffer;

/**
 * Chunk vertices for shader packs. Vanilla block vertices (28 bytes: pos 3f, color 4ub, uv 2f, lightmap 2s) become the
 * layout MGL reads for programs (56 bytes): + normal 3b (+pad), mc_midTexCoord 2f, at_tangent 4s, mc_Entity 4s.
 * Normal, tangent and mid-texcoord are computed per quad; the block id rides in the unused high bytes of the lightmap
 * shorts while the chunk builds ({@link #tag}), so it survives translucent quad sorting. No allocation per vertex.
 */
public final class TerrainFormat {
	public static final int VANILLA = 28, SHADER = 56;

	/** Marks vertices [from, to) of a vanilla vertex buffer with a block id (0..65535). */
	public static void tag(ByteBuffer b, int from, int to, int id) {
		byte lo = (byte)id, hi = (byte)(id >> 8);
		for (int v = from; v < to; v++) {
			int o = v * VANILLA + 24;
			b.put(o + 1, lo);
			b.put(o + 3, hi);
		}
	}

	/** Expands n vanilla vertices (quads) of src into dst (native order, capacity n * 56). */
	public static void extend(ByteBuffer src, ByteBuffer dst, int n) {
		for (int q = 0; q + 3 < n; q += 4) {
			int s = q * VANILLA;
			float x0 = src.getFloat(s), y0 = src.getFloat(s + 4), z0 = src.getFloat(s + 8);
			float x1 = src.getFloat(s + 28), y1 = src.getFloat(s + 32), z1 = src.getFloat(s + 36);
			float x2 = src.getFloat(s + 56), y2 = src.getFloat(s + 60), z2 = src.getFloat(s + 64);
			float x3 = src.getFloat(s + 84), y3 = src.getFloat(s + 88), z3 = src.getFloat(s + 92);
			float u0 = src.getFloat(s + 16), v0 = src.getFloat(s + 20), u1 = src.getFloat(s + 44), v1 = src.getFloat(s + 48);
			float u2 = src.getFloat(s + 72), v2 = src.getFloat(s + 76), u3 = src.getFloat(s + 100), v3 = src.getFloat(s + 104);
			// face normal from the diagonals (works for degenerate first triangles too)
			float ax = x2 - x0, ay = y2 - y0, az = z2 - z0, bx = x3 - x1, by = y3 - y1, bz = z3 - z1;
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-12f) {
				nx = 0;
				ny = 1;
				nz = 0;
			} else {
				nx /= len;
				ny /= len;
				nz /= len;
			}
			// tangent from the first triangle's edges and uv deltas; w = handedness against cross(tangent, normal)
			float e1x = x1 - x0, e1y = y1 - y0, e1z = z1 - z0, e2x = x2 - x0, e2y = y2 - y0, e2z = z2 - z0;
			float du1 = u1 - u0, dv1 = v1 - v0, du2 = u2 - u0, dv2 = v2 - v0;
			float det = du1 * dv2 - du2 * dv1, f = Math.abs(det) < 1e-20f ? 1 : 1 / det;
			float tx = f * (dv2 * e1x - dv1 * e2x), ty = f * (dv2 * e1y - dv1 * e2y), tz = f * (dv2 * e1z - dv1 * e2z);
			float btx = f * (du1 * e2x - du2 * e1x), bty = f * (du1 * e2y - du2 * e1y), btz = f * (du1 * e2z - du2 * e1z);
			len = (float)Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (len < 1e-12f) {
				tx = ny != 0 ? 1 : 0;
				ty = 0;
				tz = ny != 0 ? 0 : 1;
			} else {
				tx /= len;
				ty /= len;
				tz /= len;
			}
			float cx = ty * nz - tz * ny, cy = tz * nx - tx * nz, cz = tx * ny - ty * nx;
			short tw = cx * btx + cy * bty + cz * btz < 0 ? (short)-32767 : (short)32767;
			float mu = (u0 + u1 + u2 + u3) * 0.25f, mv = (v0 + v1 + v2 + v3) * 0.25f;
			byte bnx = (byte)Math.round(nx * 127), bny = (byte)Math.round(ny * 127), bnz = (byte)Math.round(nz * 127);
			short stx = (short)Math.round(tx * 32767), sty = (short)Math.round(ty * 32767), stz = (short)Math.round(tz * 32767);
			for (int k = 0; k < 4; k++) {
				int i = s + k * VANILLA, o = (q + k) * SHADER;
				dst.putLong(o, src.getLong(i)); // pos x, y
				dst.putLong(o + 8, src.getLong(i + 8)); // pos z, color
				dst.putLong(o + 16, src.getLong(i + 16)); // uv
				int lu = src.get(i + 24) & 0xFF, lv = src.get(i + 26) & 0xFF;
				int id = (src.get(i + 25) & 0xFF) | (src.get(i + 27) & 0xFF) << 8;
				dst.putShort(o + 24, (short)lu);
				dst.putShort(o + 26, (short)lv);
				dst.put(o + 28, bnx).put(o + 29, bny).put(o + 30, bnz).put(o + 31, (byte)0);
				dst.putFloat(o + 32, mu);
				dst.putFloat(o + 36, mv);
				dst.putShort(o + 40, stx).putShort(o + 42, sty).putShort(o + 44, stz).putShort(o + 46, tw);
				dst.putShort(o + 48, (short)id).putShort(o + 50, (short)0).putShort(o + 52, (short)0).putShort(o + 54, (short)0);
			}
		}
	}

	private TerrainFormat() {
	}
}
