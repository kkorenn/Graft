package dev.graft.legacy.shaders;

import dev.graft.legacy.video.VideoScreens;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stage tagging for the shader pipeline on 1.12.2: in EntityRenderer.renderWorldPass (and renderCloudsCheck,
 * RenderGlobal.renderEntities) every Profiler section call also reports the section to {@link Hooks} (sky, terrain,
 * entities, blockentities, translucent, hand...), the pass's clears go through {@link Hooks#clear}, and the pass is
 * bracketed by {@link Hooks#beginWorld} / {@link Hooks#endWorld}; RenderGlobal.renderSky tilts the sun and moon by the
 * pack's sunPathRotation. Names are matched in dev (MCP) and SRG form.
 */
public final class ShaderTransformer implements IClassTransformer {
	private static final String HOOKS = "dev/graft/legacy/shaders/Hooks", PROFILER = "net/minecraft/profiler/Profiler",
		DISPATCHER = "net/minecraft/client/renderer/BlockRendererDispatcher", RENDER_BLOCK = "(Lnet/minecraft/block/state/IBlockState;"
		+ "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/BufferBuilder;)Z",
		RENDER_MANAGER = "net/minecraft/client/renderer/entity/RenderManager", ENTITY = "net/minecraft/entity/Entity",
		TE_DISPATCHER = "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher", TILE_ENTITY = "net/minecraft/tileentity/TileEntity";

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
		if (bytes == null) {
			return null;
		}
		if (ShaderPipeline.disabledReason != null) {
			return bytes;
		}
		final boolean entityRenderer = transformedName.equals("net.minecraft.client.renderer.EntityRenderer");
		final boolean renderChunk = transformedName.equals("net.minecraft.client.renderer.chunk.RenderChunk");
		if (!entityRenderer && !renderChunk && !transformedName.equals("net.minecraft.client.renderer.RenderGlobal")) {
			return bytes;
		}
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		final boolean[] changed = {false};
		reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
				if (renderChunk) {
					if (!mname.equals("rebuildChunk") && !mname.equals("func_178581_b")) {
						return mv;
					}
					return new MethodVisitor(Opcodes.ASM5, mv) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
							// block ids for the shader vertex layout: Hooks.renderBlock(dispatcher, state, pos, world, buffer)
							if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(DISPATCHER) && d.equals(RENDER_BLOCK)) {
								changed[0] = true;
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "renderBlock", "(L" + DISPATCHER + ";" + RENDER_BLOCK.substring(1), false);
								return;
							}
							super.visitMethodInsn(opcode, owner, n, d, itf);
						}
					};
				}
				if (!entityRenderer && desc.equals("(FI)V") && (mname.equals("renderSky") || mname.equals("func_174976_a"))) {
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
				final boolean pass = entityRenderer && desc.equals("(IFJ)V") && (mname.equals("renderWorldPass") || mname.equals("func_175068_a"));
				boolean sections = pass
					|| entityRenderer && mname.matches("renderCloudsCheck|func_180437_a")
					|| !entityRenderer && mname.matches("renderEntities|func_180446_a");
				if (!sections) {
					return mv;
				}
				changed[0] = true;
				return new MethodVisitor(Opcodes.ASM5, mv) {
					@Override
					public void visitCode() {
						super.visitCode();
						if (pass) {
							super.visitVarInsn(Opcodes.FLOAD, 2);
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beginWorld", "(F)V", false);
						}
					}

					@Override
					public void visitInsn(int opcode) {
						if (pass && opcode == Opcodes.RETURN) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "endWorld", "()V", false);
						}
						super.visitInsn(opcode);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
						// per-draw entityId / blockEntityId and the shadow pass's entity filter
						if (!entityRenderer && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(RENDER_MANAGER) && d.equals("(L" + ENTITY + ";FZ)V")) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "renderEntity", "(L" + RENDER_MANAGER + ";L" + ENTITY + ";FZ)V", false);
							return;
						}
						if (!entityRenderer && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(TE_DISPATCHER) && d.equals("(L" + TILE_ENTITY + ";FI)V")) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "renderTileEntity", "(L" + TE_DISPATCHER + ";L" + TILE_ENTITY + ";FI)V", false);
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
						if (pass && opcode == Opcodes.INVOKESTATIC && owner.equals("net/minecraft/client/renderer/GlStateManager") && d.equals("(I)V")
							&& (n.equals("clear") || n.equals("func_179086_m"))) {
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
