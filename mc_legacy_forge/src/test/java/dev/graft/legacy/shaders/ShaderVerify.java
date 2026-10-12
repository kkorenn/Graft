package dev.graft.legacy.shaders;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Headless check of this version's {@link ShaderTransformer} on the SRG-named Minecraft classes the game runs: every hook
 * must land, and the transformed classes must pass ASM's CheckClassAdapter (bytecode verifier). For 1.7.10 the calls
 * Graft's GlTransformer adds first (Scale.begin/end, GL11.glClear -> MGL.glClear) are emulated.
 * ./gradlew :mc<version>:shaderVerify
 */
public final class ShaderVerify {
	public static void main(String[] args) throws Exception {
		ZipFile jar = new ZipFile(args[0]);
		String[] expected = args[1].split(",");
		ShaderTransformer t = new ShaderTransformer();
		Map<String, Integer> hooks = new TreeMap<String, Integer>();
		int failures = 0;
		for (String c : new String[] {"net.minecraft.client.renderer.EntityRenderer", "net.minecraft.client.renderer.RenderGlobal",
			"net.minecraft.client.renderer.chunk.RenderChunk", "net.minecraft.client.renderer.Tessellator"}) {
			ZipEntry e = jar.getEntry(c.replace('.', '/') + ".class");
			if (e == null) {
				continue;
			}
			byte[] in = read(jar.getInputStream(e));
			if (Boolean.getBoolean("verify.emulateGl")) {
				in = emulateGlTransformer(in);
			}
			byte[] out = t.transform(c, c, in);
			count(out, hooks);
			StringWriter w = new StringWriter();
			CheckClassAdapter.verify(new ClassReader(out), ShaderVerify.class.getClassLoader(), false, new PrintWriter(w));
			String problems = w.toString().trim();
			System.out.println(c + ": " + (out == in ? "unchanged" : "transformed") + (problems.isEmpty() ? ", verifier OK" : ", VERIFIER ERRORS"));
			if (!problems.isEmpty()) {
				failures++;
				System.out.println(problems.length() > 4000 ? problems.substring(0, 4000) : problems);
			}
		}
		System.out.println("hooks: " + hooks);
		for (String h : expected) {
			if (!hooks.containsKey(h)) {
				failures++;
				System.out.println("MISSING hook " + h);
			}
		}
		if (failures > 0) {
			throw new AssertionError(failures + " problems");
		}
		System.out.println("SHADER VERIFY OK");
	}

	private static void count(byte[] b, final Map<String, Integer> hooks) {
		new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM5) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM5) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
						if (owner.equals("dev/graft/legacy/shaders/Hooks")) {
							Integer c = hooks.get(n);
							hooks.put(n, c == null ? 1 : c + 1);
						}
					}
				};
			}
		}, 0);
	}

	/** What GlTransformer does to EntityRenderer.renderWorld on 1.7.10 before the shader transformer sees it. */
	private static byte[] emulateGlTransformer(byte[] bytes) {
		ClassReader r = new ClassReader(bytes);
		ClassWriter w = new ClassWriter(r, ClassWriter.COMPUTE_MAXS);
		r.accept(new ClassVisitor(Opcodes.ASM5, w) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				final boolean world = desc.equals("(FJ)V") && (name.equals("renderWorld") || name.equals("func_78471_a"));
				return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions)) {
					@Override
					public void visitCode() {
						super.visitCode();
						if (world) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/graft/legacy/Scale", "begin", "()V", false);
						}
					}

					@Override
					public void visitInsn(int opcode) {
						if (world && opcode == Opcodes.RETURN) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/graft/legacy/Scale", "end", "()V", false);
						}
						super.visitInsn(opcode);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
						if (owner.equals("org/lwjgl/opengl/GL11") && n.equals("glClear")) {
							owner = "dev/graft/legacy/MGL";
						}
						super.visitMethodInsn(opcode, owner, n, d, itf);
					}
				};
			}
		}, 0);
		return w.toByteArray();
	}

	private static byte[] read(InputStream in) throws java.io.IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] b = new byte[8192];
		for (int n; (n = in.read(b)) > 0; ) {
			out.write(b, 0, n);
		}
		in.close();
		return out.toByteArray();
	}
}
