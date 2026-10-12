package dev.graft.legacy.shaders;

import dev.graft.legacy.video.VideoScreens;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stage tagging for the shader pipeline on 1.7.10: in EntityRenderer.renderWorld (and renderCloudsCheck,
 * RenderGlobal.renderEntities) every Profiler section call also reports the section to {@link Hooks}, the pass's
 * glClear calls go through {@link Hooks#clear}, and the pass is bracketed by {@link Hooks#beginWorld} /
 * {@link Hooks#endWorld} inside Graft's render-scale calls (Scale.begin/end, added by GlTransformer, which runs first);
 * RenderGlobal.renderSky tilts the sun and moon by sunPathRotation; Tessellator.draw gets face normals while a pack is on.
 * Names are matched in dev (MCP) and SRG form.
 */
public final class ShaderTransformer implements IClassTransformer {
	private static final String HOOKS = "dev/graft/legacy/shaders/Hooks", PROFILER = "net/minecraft/profiler/Profiler",
		SCALE = "dev/graft/legacy/Scale", RENDER_MANAGER = "net/minecraft/client/renderer/entity/RenderManager", ENTITY = "net/minecraft/entity/Entity",
		TE_DISPATCHER = "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher", TILE_ENTITY = "net/minecraft/tileentity/TileEntity",
		TESSELLATOR = "net/minecraft/client/renderer/Tessellator";

	public ShaderTransformer() {
		if (dev.graft.legacy.video.VideoTransformer.optifine()) { // two shader pipelines cannot share the renderer
			ShaderPipeline.disabledReason = "OptiFine is installed: use OptiFine's shader packs (Graft's are off)";
		}
		// the Video Settings "Shaders..." button; GuiShaderPacks is only loaded when it is pressed
		VideoScreens.shadersScreenFactory = new java.util.function.Function<Object, Object>() {
			@Override
			public Object apply(Object parent) {
				return GuiShaderPacks.create(parent);
			}
		};
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] bytes) {
		if (bytes == null || ShaderPipeline.disabledReason != null) {
			return bytes;
		}
		final boolean entityRenderer = transformedName.equals("net.minecraft.client.renderer.EntityRenderer");
		final boolean tessellator = transformedName.equals("net.minecraft.client.renderer.Tessellator");
		if (!entityRenderer && !tessellator && !transformedName.equals("net.minecraft.client.renderer.RenderGlobal")) {
			return bytes;
		}
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		final boolean[] changed = {false};
		reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
				if (tessellator) {
					if (!desc.equals("()I") || !mname.equals("draw") && !mname.equals("func_78381_a")) {
						return mv;
					}
					changed[0] = true;
					return new MethodVisitor(Opcodes.ASM5, mv) {
						@Override
						public void visitCode() {
							super.visitCode();
							super.visitVarInsn(Opcodes.ALOAD, 0);
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "tessellatorDraw", "(L" + TESSELLATOR + ";)V", false);
						}
					};
				}
				if (!entityRenderer && desc.equals("(F)V") && (mname.equals("renderSky") || mname.equals("func_72714_a"))) {
					changed[0] = true;
					return new MethodVisitor(Opcodes.ASM5, mv) {
						int angles;

						@Override
						public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
							// the second getCelestialAngle is the argument of the sun/moon rotation: tilt by sunPathRotation there
							if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/multiplayer/WorldClient") && d.equals("(F)F")
								&& (n.equals("getCelestialAngle") || n.equals("func_72826_c")) && ++angles == 2) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "skyAngle", "(L" + owner + ";F)F", false);
								return;
							}
							super.visitMethodInsn(opcode, owner, n, d, itf);
						}
					};
				}
				final boolean pass = entityRenderer && desc.equals("(FJ)V") && (mname.equals("renderWorld") || mname.equals("func_78471_a"));
				boolean sections = pass
					|| entityRenderer && mname.matches("renderCloudsCheck|func_82829_a")
					|| !entityRenderer && mname.matches("renderEntities|func_147589_a");
				if (!sections) {
					return mv;
				}
				changed[0] = true;
				return new MethodVisitor(Opcodes.ASM5, mv) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
						if (pass && opcode == Opcodes.INVOKESTATIC && owner.equals(SCALE) && d.equals("()V")) {
							if (n.equals("begin")) { // the world renders into the scaled target: the pipeline starts inside it
								super.visitMethodInsn(opcode, owner, n, d, itf);
								super.visitVarInsn(Opcodes.FLOAD, 1);
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beginWorld", "(F)V", false);
								return;
							}
							if (n.equals("end")) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "endWorld", "()V", false);
							}
						}
						if (!entityRenderer && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(RENDER_MANAGER) && d.equals("(L" + ENTITY + ";F)Z")) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "renderEntity", "(L" + RENDER_MANAGER + ";L" + ENTITY + ";F)Z", false);
							return;
						}
						if (!entityRenderer && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(TE_DISPATCHER) && d.equals("(L" + TILE_ENTITY + ";F)V")) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "renderTileEntity", "(L" + TE_DISPATCHER + ";L" + TILE_ENTITY + ";F)V", false);
							return;
						}
						if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(PROFILER) && d.equals("(Ljava/lang/String;)V")) {
							if (n.equals("endStartSection") || n.equals("func_76318_c")) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "endStartSection", "(L" + PROFILER + ";Ljava/lang/String;)V", false);
								return;
							}
							if (n.equals("startSection") || n.equals("func_76320_a")) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "startSection", "(L" + PROFILER + ";Ljava/lang/String;)V", false);
								return;
							}
						}
						if (pass && opcode == Opcodes.INVOKESTATIC && owner.equals("dev/graft/legacy/MGL") && n.equals("glClear") && d.equals("(I)V")) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "clear", "(I)V", false);
							return;
						}
						super.visitMethodInsn(opcode, owner, n, d, itf);
					}
				};
			}
		}, 0);
		return changed[0] ? writer.toByteArray() : bytes;
	}
}
