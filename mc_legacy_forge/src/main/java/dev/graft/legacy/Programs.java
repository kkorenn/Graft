package dev.graft.legacy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GLSL program objects on Metal (the ARB_shader_objects / GL 2.0 calls OptiFine's shader engine makes), as Graft Shaders'
 * MetalProgram: a linked program is two Metal functions plus a CPU copy of mc_Uniforms. Uniform locations are
 * (member index << 8 | array element); sampler locations are 100000 + binding, and their value is a texture unit.
 */
public final class Programs {
	static final class Shader {
		final int type;
		String source = "";

		Shader(int type) {
			this.type = type;
		}
	}

	static final class Program {
		final int id;
		final List<Shader> shaders = new ArrayList<Shader>();
		final Map<String, Integer> attributes = new HashMap<String, Integer>();
		MetalGlsl.Linked linked;
		boolean ok;
		String log = "";
		long vertexFn, fragmentFn;
		ByteBuffer block;
		int[] units;
		/** Offsets of the compatibility members (MetalGlsl.BUILTIN_MEMBERS order), -1 if absent. */
		int[] builtins;
		final HashMap<String, Long> pipelines = new HashMap<String, Long>();

		Program(int id) {
			this.id = id;
		}
	}

	static Program current;
	private static final HashMap<Integer, Object> objects = new HashMap<Integer, Object>();
	private static int next = 1;
	private static final int SAMPLER_BASE = 100000;

	// ---------------------------------------------------------------- objects

	public static int glCreateShaderObjectARB(int type) {
		objects.put(next, new Shader(type));
		return next++;
	}

	public static int glCreateShader(int type) {
		return glCreateShaderObjectARB(type);
	}

	public static int glCreateProgramObjectARB() {
		objects.put(next, new Program(next));
		return next++;
	}

	public static int glCreateProgram() {
		return glCreateProgramObjectARB();
	}

	public static void glShaderSourceARB(int shader, CharSequence source) {
		Object o = objects.get(shader);
		if (o instanceof Shader) {
			((Shader)o).source = source.toString();
		}
	}

	public static void glShaderSourceARB(int shader, ByteBuffer source) {
		byte[] b = new byte[source.remaining()];
		source.duplicate().get(b);
		glShaderSourceARB(shader, new String(b, java.nio.charset.StandardCharsets.UTF_8));
	}

	public static void glShaderSource(int shader, CharSequence source) {
		glShaderSourceARB(shader, source);
	}

	public static void glShaderSource(int shader, ByteBuffer source) {
		glShaderSourceARB(shader, source);
	}

	public static void glCompileShaderARB(int shader) {
	}

	public static void glCompileShader(int shader) {
	}

	public static void glAttachObjectARB(int program, int shader) {
		Object p = objects.get(program), s = objects.get(shader);
		if (p instanceof Program && s instanceof Shader) {
			((Program)p).shaders.add((Shader)s);
		}
	}

	public static void glAttachShader(int program, int shader) {
		glAttachObjectARB(program, shader);
	}

	public static void glDetachObjectARB(int program, int shader) {
		Object p = objects.get(program), s = objects.get(shader);
		if (p instanceof Program) {
			((Program)p).shaders.remove(s);
		}
	}

	public static void glDetachShader(int program, int shader) {
		glDetachObjectARB(program, shader);
	}

	public static void glDeleteObjectARB(int obj) {
		Object o = objects.remove(obj);
		if (o instanceof Program) {
			Program p = (Program)o;
			if (current == p) {
				MGL.flushBatch();
				current = null;
			}
			for (long pipe : p.pipelines.values()) {
				MGL.releaseObject(pipe);
			}
			MGL.releaseObject(p.vertexFn);
			MGL.releaseObject(p.fragmentFn);
		}
	}

	public static void glDeleteShader(int shader) {
		glDeleteObjectARB(shader);
	}

	public static void glDeleteProgram(int program) {
		glDeleteObjectARB(program);
	}

	public static void glBindAttribLocationARB(int program, int index, CharSequence name) {
		Object p = objects.get(program);
		if (p instanceof Program) {
			((Program)p).attributes.put(name.toString(), index);
		}
	}

	public static void glBindAttribLocation(int program, int index, CharSequence name) {
		glBindAttribLocationARB(program, index, name);
	}

	public static int glGetAttribLocationARB(int program, CharSequence name) {
		Object p = objects.get(program);
		Integer i = p instanceof Program ? ((Program)p).attributes.get(name.toString()) : null;
		return i != null ? i : -1;
	}

	public static int glGetAttribLocation(int program, CharSequence name) {
		return glGetAttribLocationARB(program, name);
	}

	public static void glLinkProgramARB(int program) {
		Object o = objects.get(program);
		if (!(o instanceof Program)) {
			return;
		}
		Program p = (Program)o;
		String vs = null, fs = null;
		for (Shader s : p.shaders) {
			if (s.type == 0x8B31) {
				vs = s.source;
			} else if (s.type == 0x8B30) {
				fs = s.source;
			} else {
				p.log = "Graft: geometry shaders are not supported";
			}
		}
		p.ok = false;
		if (vs == null || fs == null || !p.log.isEmpty()) {
			if (p.log.isEmpty()) {
				p.log = "Graft: a program needs a vertex and a fragment shader";
			}
			return;
		}
		MetalGlsl.Linked l = MetalGlsl.link(vs, fs, p.attributes);
		if (l.error != null) {
			p.log = l.error;
			MGL.LOG.warn("Graft: could not translate a shader program: {}", l.error.length() > 4000 ? l.error.substring(0, 4000) : l.error);
			return;
		}
		long vfn = MGL.metalFunction(l.vertexMsl, l.vertexEntry), ffn = vfn == 0L ? 0L : MGL.metalFunction(l.fragmentMsl, l.fragmentEntry);
		if (vfn == 0L || ffn == 0L) {
			p.log = "Graft: Metal rejected the translated shader (see log)";
			return;
		}
		p.linked = l;
		p.vertexFn = vfn;
		p.fragmentFn = ffn;
		p.block = ByteBuffer.allocateDirect(Math.max(16, l.blockSize)).order(ByteOrder.nativeOrder());
		p.units = new int[l.samplers.size()];
		p.builtins = new int[MetalGlsl.BUILTIN_MEMBERS.length];
		for (int i = 0; i < p.builtins.length; i++) {
			String n = MetalGlsl.BUILTIN_MEMBERS[i][1];
			int br = n.indexOf('[');
			Integer m = l.memberIndex.get(br < 0 ? n : n.substring(0, br));
			p.builtins[i] = m == null ? -1 : l.members.get(m).offset;
		}
		p.ok = true;
		p.log = "";
		if (Integer.getInteger("graft.dumpFrame", -1) >= 0) {
			MGL.LOG.info("Graft: program {} samplers={} block={}", p.id, l.samplers, l.blockSize);
		}
	}

	public static void glLinkProgram(int program) {
		glLinkProgramARB(program);
	}

	public static void glValidateProgramARB(int program) {
	}

	public static void glValidateProgram(int program) {
	}

	public static void glUseProgramObjectARB(int program) {
		Object o = objects.get(program);
		Program p = o instanceof Program && ((Program)o).ok ? (Program)o : null;
		if (p != current) {
			MGL.flushBatch();
			current = p;
		}
	}

	public static void glUseProgram(int program) {
		glUseProgramObjectARB(program);
	}

	// ---------------------------------------------------------------- queries

	public static int glGetObjectParameteriARB(int obj, int pname) {
		Object o = objects.get(obj);
		switch (pname) {
			case 0x8B81: return o instanceof Shader ? 1 : 0; // COMPILE_STATUS: compiled at link time
			case 0x8B82: case 0x8B83: return o instanceof Program && ((Program)o).ok ? 1 : 0; // LINK / VALIDATE_STATUS
			case 0x8B84: { // INFO_LOG_LENGTH
				String log = o instanceof Program ? ((Program)o).log : "";
				return log.isEmpty() ? 0 : log.length() + 1;
			}
			case 0x8B4F: return o instanceof Shader ? ((Shader)o).type : 0x8B40; // OBJECT_TYPE
			default: return 0;
		}
	}

	public static void glGetObjectParameterARB(int obj, int pname, IntBuffer out) {
		out.put(out.position(), glGetObjectParameteriARB(obj, pname));
	}

	public static int glGetShaderi(int shader, int pname) {
		return glGetObjectParameteriARB(shader, pname);
	}

	public static int glGetProgrami(int program, int pname) {
		return glGetObjectParameteriARB(program, pname);
	}

	public static String glGetInfoLogARB(int obj, int maxLength) {
		Object o = objects.get(obj);
		String log = o instanceof Program ? ((Program)o).log : "";
		return log.length() > maxLength ? log.substring(0, maxLength) : log;
	}

	public static void glGetInfoLogARB(int obj, IntBuffer length, ByteBuffer log) {
		byte[] b = glGetInfoLogARB(obj, log.remaining()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int n = Math.min(b.length, log.remaining());
		for (int i = 0; i < n; i++) {
			log.put(log.position() + i, b[i]);
		}
		if (length != null) {
			length.put(length.position(), n);
		}
	}

	public static String glGetShaderInfoLog(int shader, int maxLength) {
		return glGetInfoLogARB(shader, maxLength);
	}

	public static String glGetProgramInfoLog(int program, int maxLength) {
		return glGetInfoLogARB(program, maxLength);
	}

	// ---------------------------------------------------------------- uniforms

	public static int glGetUniformLocationARB(int program, CharSequence nameSeq) {
		Object o = objects.get(program);
		if (!(o instanceof Program) || !((Program)o).ok) {
			return -1;
		}
		MetalGlsl.Linked l = ((Program)o).linked;
		String name = nameSeq.toString();
		int element = 0, br = name.indexOf('[');
		if (br >= 0) {
			try {
				element = Integer.parseInt(name.substring(br + 1, name.indexOf(']', br)).trim());
			} catch (RuntimeException e) {
				return -1;
			}
			name = name.substring(0, br);
		}
		for (String n : new String[] {name, "mc_kw_" + name}) {
			int s = l.samplers.indexOf(n);
			if (s >= 0) {
				return SAMPLER_BASE + s;
			}
			Integer m = l.memberIndex.get(n);
			if (m != null) {
				return m << 8 | element;
			}
		}
		return -1;
	}

	public static int glGetUniformLocation(int program, CharSequence name) {
		return glGetUniformLocationARB(program, name);
	}

	/** Byte offset of a location's element (std140: array elements of 16 bytes, mat4 64, mat3 48), or -1. */
	private static int offset(Program p, int location, int element) {
		MetalGlsl.Member m = p.linked.members.get(location >> 8);
		if (m.offset < 0) {
			return -1;
		}
		int stride = m.type.equals("mat4") ? 64 : m.type.equals("mat3") ? 48 : m.type.equals("mat2") ? 32 : 16;
		int e = (location & 255) + element;
		return m.array > 0 && e < m.array ? m.offset + e * stride : e == 0 ? m.offset : -1;
	}

	private static Program target(int location) {
		Program p = current;
		if (p == null || location < 0) {
			return null;
		}
		MGL.flushBatch();
		if (location >= SAMPLER_BASE) {
			return p;
		}
		return (location >> 8) < p.linked.members.size() ? p : null;
	}

	private static void floats(int location, int components, float... v) {
		Program p = target(location);
		if (p == null || location >= SAMPLER_BASE) {
			return;
		}
		for (int e = 0; e * components < v.length; e++) {
			int at = offset(p, location, e);
			if (at < 0 || at + components * 4 > p.block.capacity()) {
				return;
			}
			for (int c = 0; c < components && e * components + c < v.length; c++) {
				p.block.putFloat(at + c * 4, v[e * components + c]);
			}
		}
	}

	private static void ints(int location, int components, int... v) {
		Program p = target(location);
		if (p == null) {
			return;
		}
		if (location >= SAMPLER_BASE) {
			int s = location - SAMPLER_BASE;
			if (s < p.units.length) {
				p.units[s] = v[0];
			}
			return;
		}
		for (int e = 0; e * components < v.length; e++) {
			int at = offset(p, location, e);
			if (at < 0 || at + components * 4 > p.block.capacity()) {
				return;
			}
			for (int c = 0; c < components && e * components + c < v.length; c++) {
				p.block.putInt(at + c * 4, v[e * components + c]);
			}
		}
	}

	private static float[] read(FloatBuffer b) {
		float[] v = new float[b.remaining()];
		b.duplicate().get(v);
		return v;
	}

	private static int[] read(IntBuffer b) {
		int[] v = new int[b.remaining()];
		b.duplicate().get(v);
		return v;
	}

	public static void glUniform1fARB(int l, float x) {
		floats(l, 1, x);
	}

	public static void glUniform2fARB(int l, float x, float y) {
		floats(l, 2, x, y);
	}

	public static void glUniform3fARB(int l, float x, float y, float z) {
		floats(l, 3, x, y, z);
	}

	public static void glUniform4fARB(int l, float x, float y, float z, float w) {
		floats(l, 4, x, y, z, w);
	}

	public static void glUniform1iARB(int l, int x) {
		ints(l, 1, x);
	}

	public static void glUniform2iARB(int l, int x, int y) {
		ints(l, 2, x, y);
	}

	public static void glUniform3iARB(int l, int x, int y, int z) {
		ints(l, 3, x, y, z);
	}

	public static void glUniform4iARB(int l, int x, int y, int z, int w) {
		ints(l, 4, x, y, z, w);
	}

	public static void glUniform1ARB(int l, FloatBuffer v) {
		floats(l, 1, read(v));
	}

	public static void glUniform2ARB(int l, FloatBuffer v) {
		floats(l, 2, read(v));
	}

	public static void glUniform3ARB(int l, FloatBuffer v) {
		floats(l, 3, read(v));
	}

	public static void glUniform4ARB(int l, FloatBuffer v) {
		floats(l, 4, read(v));
	}

	public static void glUniform1ARB(int l, IntBuffer v) {
		ints(l, 1, read(v));
	}

	public static void glUniform2ARB(int l, IntBuffer v) {
		ints(l, 2, read(v));
	}

	public static void glUniform3ARB(int l, IntBuffer v) {
		ints(l, 3, read(v));
	}

	public static void glUniform4ARB(int l, IntBuffer v) {
		ints(l, 4, read(v));
	}

	public static void glUniformMatrix4ARB(int l, boolean transpose, FloatBuffer v) {
		float[] m = read(v);
		Program p = target(l);
		if (p == null || l >= SAMPLER_BASE) {
			return;
		}
		for (int e = 0; e * 16 < m.length; e++) {
			int at = offset(p, l, e);
			if (at < 0) {
				return;
			}
			for (int i = 0; i < 16; i++) {
				p.block.putFloat(at + i * 4, transpose ? m[e * 16 + (i % 4) * 4 + i / 4] : m[e * 16 + i]);
			}
		}
	}

	public static void glUniformMatrix3ARB(int l, boolean transpose, FloatBuffer v) {
		float[] m = read(v);
		Program p = target(l);
		if (p == null || l >= SAMPLER_BASE || m.length < 9) {
			return;
		}
		int at = offset(p, l, 0);
		if (at < 0) {
			return;
		}
		for (int c = 0; c < 3; c++) { // std140 mat3: three vec4 columns
			for (int r = 0; r < 3; r++) {
				p.block.putFloat(at + c * 16 + r * 4, transpose ? m[r * 3 + c] : m[c * 3 + r]);
			}
		}
	}

	public static void glUniformMatrix2ARB(int l, boolean transpose, FloatBuffer v) {
		float[] m = read(v);
		Program p = target(l);
		if (p == null || l >= SAMPLER_BASE || m.length < 4) {
			return;
		}
		int at = offset(p, l, 0);
		if (at >= 0) {
			for (int c = 0; c < 2; c++) {
				for (int r = 0; r < 2; r++) {
					p.block.putFloat(at + c * 16 + r * 4, transpose ? m[r * 2 + c] : m[c * 2 + r]);
				}
			}
		}
	}

	// GL 2.0 names
	public static void glUniform1f(int l, float x) {
		glUniform1fARB(l, x);
	}

	public static void glUniform2f(int l, float x, float y) {
		glUniform2fARB(l, x, y);
	}

	public static void glUniform3f(int l, float x, float y, float z) {
		glUniform3fARB(l, x, y, z);
	}

	public static void glUniform4f(int l, float x, float y, float z, float w) {
		glUniform4fARB(l, x, y, z, w);
	}

	public static void glUniform1i(int l, int x) {
		glUniform1iARB(l, x);
	}

	public static void glUniform2i(int l, int x, int y) {
		glUniform2iARB(l, x, y);
	}

	public static void glUniform3i(int l, int x, int y, int z) {
		glUniform3iARB(l, x, y, z);
	}

	public static void glUniform4i(int l, int x, int y, int z, int w) {
		glUniform4iARB(l, x, y, z, w);
	}

	public static void glUniform1(int l, FloatBuffer v) {
		glUniform1ARB(l, v);
	}

	public static void glUniform2(int l, FloatBuffer v) {
		glUniform2ARB(l, v);
	}

	public static void glUniform3(int l, FloatBuffer v) {
		glUniform3ARB(l, v);
	}

	public static void glUniform4(int l, FloatBuffer v) {
		glUniform4ARB(l, v);
	}

	public static void glUniform1(int l, IntBuffer v) {
		glUniform1ARB(l, v);
	}

	public static void glUniform2(int l, IntBuffer v) {
		glUniform2ARB(l, v);
	}

	public static void glUniform3(int l, IntBuffer v) {
		glUniform3ARB(l, v);
	}

	public static void glUniform4(int l, IntBuffer v) {
		glUniform4ARB(l, v);
	}

	public static void glUniformMatrix2(int l, boolean t, FloatBuffer v) {
		glUniformMatrix2ARB(l, t, v);
	}

	public static void glUniformMatrix3(int l, boolean t, FloatBuffer v) {
		glUniformMatrix3ARB(l, t, v);
	}

	public static void glUniformMatrix4(int l, boolean t, FloatBuffer v) {
		glUniformMatrix4ARB(l, t, v);
	}

	private Programs() {
	}
}
