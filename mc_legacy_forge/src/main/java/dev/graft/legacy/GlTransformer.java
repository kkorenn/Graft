package dev.graft.legacy;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes the game 100% Metal: hooks the native {@link Terrain} and {@link Tess} paths, and rewrites every static call
 * into LWJGL's OpenGL classes to {@link MGL}, Display to {@link Win}, Keyboard to {@link Keys} and Mouse to {@link Mice}
 * (same name and descriptor). A call none of them implements is replaced by its default return value and a one-time
 * warning, so no OpenGL function or context is ever touched.
 */
public final class GlTransformer implements IClassTransformer {
	private static final String MGL_NAME = "dev/graft/legacy/MGL", PROGRAMS = "dev/graft/legacy/Programs";
	private static final java.util.Map<String, Set<String>> TARGETS = new java.util.HashMap<String, Set<String>>();
	static final boolean METAL = !Boolean.getBoolean("graft.disabled");
	/**
	 * Lunar Client mixes into Minecraft classes at the LWJGL Display/Mouse/Keyboard call sites this transformer
	 * redirects, and fails to boot when they are gone. Under Lunar those call sites stay in the bytecode, behind a branch
	 * that never runs (Win.lunarNever); the redirected call runs instead. Lunar's hooks on them then never fire.
	 */
	static final boolean LUNAR = ClassLoader.getSystemResource("com/moonsworth/lunar/genesis/Genesis.class") != null
		|| GlTransformer.class.getClassLoader().getResource("com/moonsworth/lunar/genesis/Genesis.class") != null;
	private static final boolean BENCH = Boolean.getBoolean("graft.bench");
	private static final String TERRAIN = "dev/graft/legacy/Terrain";
	// Minecraft-version specifics (1.8.9 / 1.12.2): see each module's Version class.
	private static final String UPLOAD_DESC = Version.UPLOAD_DESC, BUFFER = "L" + Version.BUFFER + ";", LAYER = "L" + Version.LAYER + ";";
	private static final byte[] NEEDLE = "org/lwjgl/".getBytes(StandardCharsets.US_ASCII);

	static {
		for (String target : METAL ? new String[] {"MGL", "Programs", "Win", "Keys", "Mice"} : new String[0]) {
			Set<String> methods = new HashSet<String>();
			try {
				for (Method m : Class.forName("dev.graft.legacy." + target, false, Launch.classLoader).getDeclaredMethods()) {
					if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
						methods.add(m.getName() + Type.getMethodDescriptor(m));
					}
				}
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
			TARGETS.put("dev/graft/legacy/" + target, methods);
		}
	}

	/** Pure lookup tables in LWJGL that our Keys implementation itself calls: forwarding them would recurse. */
	private static final Set<String> KEEP_LWJGL = new HashSet<String>(java.util.Arrays.asList(
		"getKeyName(I)Ljava/lang/String;", "getKeyIndex(Ljava/lang/String;)I", "getKeyCount()I"));

	/**
	 * Under Lunar, LWJGL's Display/Mouse/Keyboard themselves forward to ours: Lunar's own code (and methods its mixins add
	 * to Minecraft after this transformer ran) calls them directly, e.g. Display.isActive() for its FPS limiter.
	 */
	private static byte[] forwardLwjgl(String name, byte[] bytes) {
		final String target = name.equals("org.lwjgl.opengl.Display") ? "dev/graft/legacy/Win"
			: name.equals("org.lwjgl.input.Mouse") ? "dev/graft/legacy/Mice"
			: name.equals("org.lwjgl.input.Keyboard") ? "dev/graft/legacy/Keys" : null;
		if (target == null) {
			return bytes;
		}
		final Set<String> ours = TARGETS.get(target);
		System.out.println("[Graft] Lunar: " + name + " forwards to " + target.substring(target.lastIndexOf('/') + 1));
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, mname, mdesc, signature, exceptions);
				boolean forward = (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_PUBLIC) != 0
					&& ours.contains(mname + mdesc) && !KEEP_LWJGL.contains(mname + mdesc);
				if (!forward) {
					return mv;
				}
				// New body: return Target.name(args); the original body is dropped (no visitor below receives it).
				mv.visitCode();
				int slot = 0;
				for (Type arg : Type.getArgumentTypes(mdesc)) {
					mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
					slot += arg.getSize();
				}
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, target, mname, mdesc, false);
				mv.visitInsn(Type.getReturnType(mdesc).getOpcode(Opcodes.IRETURN));
				mv.visitMaxs(0, 0);
				mv.visitEnd();
				return null;
			}
		}, 0);
		return writer.toByteArray();
	}

	/** AnalyzerAdapter state to frame entries: long/double take one entry there (it adds a TOP after them). */
	private static Object[] frameTypes(List<Object> types) {
		List<Object> out = new ArrayList<Object>(types.size());
		for (int i = 0; i < types.size(); i++) {
			Object type = types.get(i);
			out.add(type);
			if (type == Opcodes.LONG || type == Opcodes.DOUBLE) {
				i++;
			}
		}
		return out.toArray();
	}

	/** Our class for an LWJGL owner, or null if the owner is not replaced. */
	private static String target(String owner) {
		if (owner.startsWith("org/lwjgl/opengl/")) {
			String c = owner.substring(17);
			if (c.equals("Display")) {
				return "dev/graft/legacy/Win";
			}
			if (c.startsWith("GL1") || c.startsWith("GL2") || c.startsWith("GL3") || c.startsWith("GL4") || c.startsWith("ARB") || c.startsWith("EXT")
				|| c.startsWith("NV") || c.startsWith("AMD") || c.startsWith("APPLE") || c.equals("GLContext") || c.equals("Util")) {
				return MGL_NAME;
			}
			return null;
		}
		if (owner.equals("org/lwjgl/input/Keyboard")) {
			return "dev/graft/legacy/Keys";
		}
		if (owner.equals("org/lwjgl/input/Mouse")) {
			return "dev/graft/legacy/Mice";
		}
		return owner.equals("org/lwjgl/util/glu/Project") || owner.equals("org/lwjgl/util/glu/GLU") ? MGL_NAME : null;
	}

	/** GLU helpers that are plain Java math in LWJGL and stay as they are. */
	private static boolean pureGlu(String owner, String name) {
		return owner.startsWith("org/lwjgl/util/glu/") && !name.equals("gluPerspective");
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] bytes) {
		if (bytes == null || transformedName.startsWith("dev.graft.")) {
			return bytes;
		}
		if (transformedName.startsWith("org.lwjgl.")) {
			return METAL && LUNAR ? forwardLwjgl(transformedName, bytes) : bytes;
		}
		final boolean splash = METAL && (transformedName.equals("net.minecraftforge.fml.client.SplashProgress")
			|| transformedName.equals("cpw.mods.fml.client.SplashProgress")); // 1.7.10
		final boolean dispatcher = METAL && transformedName.equals("net.minecraft.client.renderer.chunk.ChunkRenderDispatcher");
		final boolean renderChunk = METAL && transformedName.equals("net.minecraft.client.renderer.chunk.RenderChunk");
		final boolean renderGlobal = METAL && transformedName.equals("net.minecraft.client.renderer.RenderGlobal");
		final boolean uploader = METAL && transformedName.equals("net.minecraft.client.renderer.WorldVertexBufferUploader");
		final boolean entityRenderer = METAL && transformedName.equals("net.minecraft.client.renderer.EntityRenderer");
		if (!dispatcher && !renderChunk && !contains(bytes)) {
			return bytes;
		}
		ClassReader reader = new ClassReader(bytes);
		// Lunar hooks LWJGL call sites throughout Minecraft (Minecraft, GuiContainer...). Needs StackMapTable classes (v50+).
		final boolean lunarKeep = METAL && LUNAR && transformedName.startsWith("net.minecraft.") && reader.readUnsignedShort(6) >= 50
			&& !dispatcher && !uploader; // those two get compressed frames of ours (and have no input calls)
		final String className = reader.getClassName();
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		final boolean[] changed = {false};
		reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
				final boolean upload = dispatcher && mdesc.equals(UPLOAD_DESC);
				final boolean tess = uploader && mdesc.equals("(" + BUFFER + ")V");
				final boolean renderWorld = entityRenderer && mdesc.equals("(FJ)V") && (mname.equals("renderWorld") || mname.equals("func_78471_a"));
				final boolean delete = renderChunk && mdesc.equals("()V") && (mname.equals("deleteGlResources") || mname.equals("func_178566_a"));
				MethodVisitor base = super.visitMethod(access, mname, mdesc, signature, exceptions);
				// Under Lunar, frames for the kept call sites come from the exact locals/stack at each call.
				final AnalyzerAdapter frames = lunarKeep && base != null ? new AnalyzerAdapter(className, access, mname, mdesc, base) : null;
				return new MethodVisitor(Opcodes.ASM5, frames != null ? frames : base) {
					@Override
					public void visitCode() {
						super.visitCode();
						if (upload) { // return Terrain.upload(<uploadChunk's arguments>);
							int slot = 1;
							for (Type t : Type.getArgumentTypes(UPLOAD_DESC)) { // (layer, buffer, chunk, compiled[, distance])
								super.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
								slot += t.getSize();
							}
							super.visitMethodInsn(Opcodes.INVOKESTATIC, TERRAIN, "upload", UPLOAD_DESC, false);
							super.visitInsn(Opcodes.ARETURN);
							// the vanilla body is now unreachable; the verifier needs a frame after a return
							super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
							changed[0] = true;
						} else if (tess) { // Tess.draw(worldRenderer); return;
							super.visitVarInsn(Opcodes.ALOAD, 1);
							super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/graft/legacy/Tess", "draw", "(" + BUFFER + ")V", false);
							super.visitInsn(Opcodes.RETURN);
							super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
							changed[0] = true;
						} else if (renderWorld) { // render scale: the world goes to a smaller target (Scale.end at each return)
							super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/graft/legacy/Scale", "begin", "()V", false);
							changed[0] = true;
						} else if (delete) { // Terrain.delete(this);
							super.visitVarInsn(Opcodes.ALOAD, 0);
							super.visitMethodInsn(Opcodes.INVOKESTATIC, TERRAIN, "delete", "(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V", false);
							changed[0] = true;
						}
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String n, String desc, boolean itf) {
						if (renderGlobal && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/renderer/ChunkRenderContainer")
							&& desc.equals("(" + LAYER + ")V")) { // renderChunkLayer
							changed[0] = true;
							opcode = Opcodes.INVOKESTATIC;
							owner = TERRAIN;
							n = "render";
							desc = "(Lnet/minecraft/client/renderer/ChunkRenderContainer;" + LAYER + ")V";
						} else if (opcode == Opcodes.INVOKESTATIC && BENCH && owner.equals("org/lwjgl/opengl/Display") && n.equals("update") && desc.equals("()V")) {
							changed[0] = true;
							owner = "dev/graft/legacy/Bench";
						} else if (splash && opcode == Opcodes.INVOKESTATIC && n.equals("getBool") && desc.equals("(Ljava/lang/String;Z)Z")) {
							changed[0] = true; // the GL splash thread stays off
							owner = "dev/graft/legacy/Win";
							n = "splashBool";
						} else if (METAL && opcode == Opcodes.INVOKESTATIC && target(owner) != null && !pureGlu(owner, n)) {
							changed[0] = true;
							String t = target(owner);
							if (!TARGETS.get(t).contains(n + desc) && t.equals(MGL_NAME) && TARGETS.get(PROGRAMS).contains(n + desc)) {
								t = PROGRAMS; // shader program objects live in Programs
							}
							if (!TARGETS.get(t).contains(n + desc)) {
								stub(owner, n, desc);
								return;
							}
							if (frames != null && frames.stack != null && (owner.equals("org/lwjgl/opengl/Display") || owner.equals("org/lwjgl/input/Mouse")
								|| owner.equals("org/lwjgl/input/Keyboard"))) {
								keepForLunar(owner, n, desc, t);
								return;
							}
							owner = t;
							itf = false;
						}
						super.visitMethodInsn(opcode, owner, n, desc, itf);
					}

					/** if (Win.lunarNever) { original LWJGL call } else { our call }: same arguments and result on both paths. */
					private void keepForLunar(String owner, String n, String desc, String t) {
						Object[] locals = frameTypes(frames.locals);
						Object[] before = frameTypes(frames.stack);
						Label original = new Label();
						Label end = new Label();
						super.visitFieldInsn(Opcodes.GETSTATIC, "dev/graft/legacy/Win", "lunarNever", "Z");
						super.visitJumpInsn(Opcodes.IFNE, original);
						super.visitMethodInsn(Opcodes.INVOKESTATIC, t, n, desc, false);
						Object[] after = frameTypes(frames.stack);
						super.visitJumpInsn(Opcodes.GOTO, end);
						super.visitLabel(original);
						super.visitFrame(Opcodes.F_NEW, locals.length, locals, before.length, before);
						super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, n, desc, false);
						super.visitLabel(end);
						super.visitFrame(Opcodes.F_NEW, locals.length, locals, after.length, after);
						super.visitInsn(Opcodes.NOP); // keeps a following original frame off this offset
					}

					@Override
					public void visitInsn(int opcode) {
						if (renderWorld && opcode == Opcodes.RETURN) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/graft/legacy/Scale", "end", "()V", false);
						}
						super.visitInsn(opcode);
					}

					/** Drops the arguments, reports the call once, and pushes the return type's default value. */
					private void stub(String owner, String n, String desc) {
						Type[] args = Type.getArgumentTypes(desc);
						for (int i = args.length - 1; i >= 0; i--) {
							super.visitInsn(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
						}
						super.visitLdcInsn(owner.substring(owner.lastIndexOf('/') + 1) + "." + n + desc);
						super.visitMethodInsn(Opcodes.INVOKESTATIC, MGL_NAME, "unsupported", "(Ljava/lang/String;)V", false);
						switch (Type.getReturnType(desc).getSort()) {
							case Type.VOID: break;
							case Type.LONG: super.visitInsn(Opcodes.LCONST_0); break;
							case Type.FLOAT: super.visitInsn(Opcodes.FCONST_0); break;
							case Type.DOUBLE: super.visitInsn(Opcodes.DCONST_0); break;
							case Type.OBJECT: case Type.ARRAY: super.visitInsn(Opcodes.ACONST_NULL); break;
							default: super.visitInsn(Opcodes.ICONST_0);
						}
					}
				};
			}
		}, lunarKeep ? ClassReader.EXPAND_FRAMES : 0);
		return changed[0] ? writer.toByteArray() : bytes;
	}

	private static boolean contains(byte[] b) {
		outer:
		for (int i = 0, n = b.length - NEEDLE.length; i <= n; i++) {
			for (int j = 0; j < NEEDLE.length; j++) {
				if (b[i + j] != NEEDLE[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}
}
