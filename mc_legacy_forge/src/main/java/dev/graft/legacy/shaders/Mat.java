package dev.graft.legacy.shaders;

/** Column-major 4x4 float matrices (GL layout), allocation-free where it matters. */
final class Mat {
	static float[] identity(float[] m) {
		for (int i = 0; i < 16; i++) {
			m[i] = i % 5 == 0 ? 1 : 0;
		}
		return m;
	}

	/** out = a * b (out may alias a or b). */
	static void mul(float[] out, float[] a, float[] b) {
		float[] t = new float[16];
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				t[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
			}
		}
		System.arraycopy(t, 0, out, 0, 16);
	}

	/** m = m * rotation(deg about axis). */
	static void rotate(float[] m, float deg, float x, float y, float z) {
		double a = Math.toRadians(deg);
		float c = (float)Math.cos(a), s = (float)Math.sin(a), k = 1 - c;
		float[] r = {x * x * k + c, y * x * k + z * s, x * z * k - y * s, 0, x * y * k - z * s, y * y * k + c, y * z * k + x * s, 0,
			x * z * k + y * s, y * z * k - x * s, z * z * k + c, 0, 0, 0, 0, 1};
		mul(m, m, r);
	}

	static void translate(float[] m, float x, float y, float z) {
		for (int r = 0; r < 4; r++) {
			m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z;
		}
	}

	static float[] ortho(float[] m, float l, float r, float b, float t, float n, float f) {
		identity(m);
		m[0] = 2 / (r - l);
		m[5] = 2 / (t - b);
		m[10] = -2 / (f - n);
		m[12] = -(r + l) / (r - l);
		m[13] = -(t + b) / (t - b);
		m[14] = -(f + n) / (f - n);
		return m;
	}

	/** out = m * (x, y, z, w), xyz only. */
	static void transform(float[] out, float[] m, float x, float y, float z, float w) {
		for (int r = 0; r < 3; r++) {
			out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] * w;
		}
	}

	static boolean invert(float[] m, float[] out) {
		float[] inv = new float[16];
		inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
		inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
		inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
		inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
		inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
		inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
		inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
		inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
		inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
		inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
		inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
		inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
		inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
		inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
		inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
		inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
		float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
		if (det == 0) {
			return false;
		}
		for (int i = 0; i < 16; i++) {
			out[i] = inv[i] / det;
		}
		return true;
	}

	private Mat() {
	}
}
