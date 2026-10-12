package dev.graft.legacy.video;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hooks the OptiFine-style video settings into Minecraft 1.7.10 / 1.8.9 / 1.12.2: every patch filters a value or
 * guards a method head with a call into {@link Hooks}. Methods match by MCP name (dev) or SRG name (production).
 */
public final class VideoTransformer implements IClassTransformer {
	/** Set once FML registered this transformer: the Video Settings screen is replaced only then. */
	public static volatile boolean active;
	/** Patches applied, by "Class.patch" (read by the headless verifier). */
	public static final Map<String, Integer> HITS = new TreeMap<String, Integer>();

	private static final String H = "dev/graft/legacy/video/Hooks", OBJ = "Ljava/lang/Object;";
	private static final Map<String, Patch> PATCHES = new HashMap<String, Patch>();

	interface Patch {
		void apply(ClassNode c);
	}

	public VideoTransformer() {
		active = true;
		File home = null;
		try {
			home = Launch.minecraftHome;
		} catch (LinkageError ignored) {
		}
		VideoSettings.load(new File(home != null ? home : new File("."), "graftvideo.txt"));
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] bytes) {
		Patch p = bytes == null ? null : PATCHES.get(transformedName);
		if (p == null) {
			return bytes;
		}
		if (optifine()) { // OptiFine brings its own video settings: Graft's stay out of the way
			active = false;
			return bytes;
		}
		ClassNode c = new ClassNode();
		new ClassReader(bytes).accept(c, 0);
		int[] count = COUNT.get();
		count[0] = 0;
		p.apply(c);
		if (count[0] == 0) {
			return bytes;
		}
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		c.accept(w);
		return w.toByteArray();
	}

	private static Boolean optifine;

	public static boolean optifine() {
		if (optifine == null) {
			ClassLoader l = VideoTransformer.class.getClassLoader();
			optifine = l != null && (l.getResource("optifine/OptiFineClassTransformer.class") != null || l.getResource("optifine/OptiFineForgeTweaker.class") != null);
		}
		return optifine;
	}

	private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

	private static synchronized void hit(ClassNode c, String what) {
		COUNT.get()[0]++;
		String k = c.name.substring(c.name.lastIndexOf('/') + 1) + "." + what;
		Integer n = HITS.get(k);
		HITS.put(k, n == null ? 1 : n + 1);
	}

	// ---------------------------------------------------------------- matching

	private static boolean named(MethodNode m, String... names) {
		return Arrays.asList(names).contains(m.name);
	}

	private static boolean call(AbstractInsnNode i, String... names) {
		return i instanceof MethodInsnNode && Arrays.asList(names).contains(((MethodInsnNode)i).name);
	}

	private static boolean field(AbstractInsnNode i, String desc, String... names) {
		return i.getOpcode() == Opcodes.GETFIELD && ((FieldInsnNode)i).desc.equals(desc)
			&& (names.length == 0 || Arrays.asList(names).contains(((FieldInsnNode)i).name));
	}

	private static MethodInsnNode hook(String name, String desc) {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, H, name, desc, false);
	}

	// ---------------------------------------------------------------- edits

	/** Passes the value an instruction leaves on the stack through Hooks.name(T)T. */
	private static void filterAfter(ClassNode c, MethodNode m, AbstractInsnNode i, String name, String desc, String what) {
		InsnList l = new InsnList();
		l.add(hook(name, desc));
		String ret = Type.getReturnType(desc).getDescriptor();
		if (ret.equals(OBJ) && i instanceof MethodInsnNode) { // back to the call's own return type
			l.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(((MethodInsnNode)i).desc).getInternalName()));
		}
		m.instructions.insert(i, l);
		hit(c, what);
	}

	/** Filters every return value of m through Hooks.name, after pushing `extra` loads (arguments of the hook). */
	private static void filterReturns(ClassNode c, MethodNode m, String name, String desc, String what, int... extraLoads) {
		for (AbstractInsnNode i : m.instructions.toArray()) {
			int op = i.getOpcode();
			if (op >= Opcodes.IRETURN && op <= Opcodes.ARETURN) {
				InsnList l = new InsnList();
				for (int k = 0; k < extraLoads.length; k += 2) {
					l.add(new VarInsnNode(extraLoads[k], extraLoads[k + 1]));
				}
				l.add(hook(name, desc));
				m.instructions.insertBefore(i, l);
				hit(c, what);
			}
		}
	}

	/** At the method start: if (cond) { body (ends in a return) }. */
	private static void guard(ClassNode c, MethodNode m, InsnList cond, InsnList body, String what) {
		LabelNode skip = new LabelNode();
		InsnList l = new InsnList();
		l.add(cond);
		l.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		l.add(body);
		l.add(skip);
		l.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		l.add(new InsnNode(Opcodes.NOP)); // keeps an original frame at the first instruction off this offset
		m.instructions.insert(l);
		hit(c, what);
	}

	private static InsnList list(AbstractInsnNode... insns) {
		InsnList l = new InsnList();
		for (AbstractInsnNode i : insns) {
			l.add(i);
		}
		return l;
	}

	private static InsnNode op(int opcode) {
		return new InsnNode(opcode);
	}

	private static VarInsnNode load(int opcode, int slot) {
		return new VarInsnNode(opcode, slot);
	}

	/** Loads all arguments of m (and this) in order, for a call with the same descriptor. */
	private static InsnList loadArgs(MethodNode m) {
		InsnList l = new InsnList();
		int slot = 0;
		if ((m.access & Opcodes.ACC_STATIC) == 0) {
			l.add(load(Opcodes.ALOAD, slot++));
		}
		for (Type t : Type.getArgumentTypes(m.desc)) {
			l.add(load(t.getOpcode(Opcodes.ILOAD), slot));
			slot += t.getSize();
		}
		return l;
	}

	private static boolean isConst(AbstractInsnNode i, int value) {
		return i instanceof IntInsnNode && (i.getOpcode() == Opcodes.BIPUSH || i.getOpcode() == Opcodes.SIPUSH) && ((IntInsnNode)i).operand == value
			|| value >= -1 && value <= 5 && i.getOpcode() == Opcodes.ICONST_0 + value;
	}

	// ---------------------------------------------------------------- patches

	private static void on(Patch p, String... classes) {
		for (String c : classes) {
			PATCHES.put(c, p);
		}
	}

	static {
		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getAmbientOcclusionLightValue", "func_149685_I", "func_185485_f") && m.desc.endsWith(")F")) {
					filterReturns(c, m, "aoLight", "(F)F", "aoLight");
				}
			}
		}, "net.minecraft.block.Block");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getLightBrightnessForSkyBlocks", "func_72802_i") && m.desc.equals("(IIII)I")) {
					filterReturns(c, m, "light", "(IIII)I", "light", Opcodes.ILOAD, 1, Opcodes.ILOAD, 2, Opcodes.ILOAD, 3);
				} else if (named(m, "getCombinedLight", "func_175626_b") && m.desc.endsWith(";I)I")) {
					filterReturns(c, m, "light", "(I" + OBJ + ")I", "light", Opcodes.ALOAD, 1);
				} else if (named(m, "getRainStrength", "func_72867_j", "getThunderStrength", "getWeightedThunderStrength", "func_72819_i")
					&& m.desc.equals("(F)F")) {
					guard(c, m, list(load(Opcodes.ALOAD, 0), hook("noWeather", "(" + OBJ + ")Z")), list(op(Opcodes.FCONST_0), op(Opcodes.FRETURN)), "weather");
				} else if (named(m, "getCelestialAngle", "func_72826_c") && m.desc.equals("(F)F")) {
					guard(c, m, list(load(Opcodes.ALOAD, 0), hook("fixedTime", "(" + OBJ + ")Z")), list(hook("fixedAngle", "()F"), op(Opcodes.FRETURN)), "time");
				}
			}
		}, "net.minecraft.world.World", "net.minecraft.world.ChunkCache");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "setupFog", "func_78468_a") && m.desc.equals("(IF)V")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (i.getOpcode() == Opcodes.INVOKESTATIC && call(i, "glFogf") && ((MethodInsnNode)i).desc.equals("(IF)V")) {
							MethodInsnNode mi = (MethodInsnNode)i;
							mi.owner = H;
							mi.name = "glFogf";
							mi.itf = false;
							hit(c, "fog");
						} else if (call(i, "setFogStart", "func_179102_b")) {
							m.instructions.insertBefore(i, hook("fogStart", "(F)F"));
							hit(c, "fogStart");
						} else if (call(i, "setFogEnd", "func_179153_c")) {
							m.instructions.insertBefore(i, hook("fogEnd", "(F)F"));
							hit(c, "fogEnd");
						}
					}
				} else if (named(m, "renderRainSnow", "func_78474_d") || named(m, "addRainParticles", "func_78484_h")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (field(i, "Z", "fancyGraphics", "field_74347_j")) {
							filterAfter(c, m, i, "rainFancy", "(Z)Z", "rainFancy");
						}
					}
					if (m.name.equals("renderRainSnow") || m.name.equals("func_78474_d")) {
						guard(c, m, list(hook("noRain", "()Z")), list(op(Opcodes.RETURN)), "noRain");
					}
				}
			}
		}, "net.minecraft.client.renderer.EntityRenderer");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "shouldRenderClouds", "func_181147_e", "func_74309_c")) {
					filterReturns(c, m, "clouds", m.desc.equals("()Z") ? "(Z)Z" : "(I)I", "clouds");
				}
			}
		}, "net.minecraft.client.settings.GameSettings");

		on(c -> {
			for (MethodNode m : c.methods) {
				boolean clouds = named(m, "renderClouds", "func_72718_b", "func_180447_b");
				boolean fancyClouds = named(m, "renderCloudsFancy", "func_72736_c", "func_180445_c");
				boolean sky = named(m, "renderSky", "func_72714_a", "func_174976_a") && (m.desc.equals("(F)V") || m.desc.equals("(FI)V"));
				for (AbstractInsnNode i : m.instructions.toArray()) {
					if ((clouds || fancyClouds) && call(i, "getCloudHeight", "func_76571_f")) {
						filterAfter(c, m, i, "cloudHeight", "(F)F", "cloudHeight");
					} else if (clouds && field(i, "Z", "fancyGraphics", "field_74347_j")) {
						filterAfter(c, m, i, "cloudsFancy", "(Z)Z", "cloudsFancy");
					} else if (named(m, "loadRenderers", "func_72712_a") && call(i, "setGraphicsLevel", "func_150122_b")) {
						m.instructions.insertBefore(i, hook("treesFancy", "(Z)Z"));
						hit(c, "trees");
					} else if (sky && call(i, "getSkyColor", "func_72833_a")) {
						filterAfter(c, m, i, "skyColor", "(" + OBJ + ")" + OBJ, "skyColor");
					} else if (sky && call(i, "calcSunriseSunsetColors", "func_76560_a")) {
						filterAfter(c, m, i, "sunrise", "([F)[F", "sunrise");
					} else if (sky && call(i, "getStarBrightness", "func_72880_h")) {
						filterAfter(c, m, i, "stars", "(F)F", "stars");
					} else if (sky && call(i, "bindTexture", "func_110577_a")) {
						MethodInsnNode mi = (MethodInsnNode)i;
						mi.setOpcode(Opcodes.INVOKESTATIC);
						mi.owner = H;
						mi.name = "bindSky";
						mi.desc = "(" + OBJ + OBJ + ")V";
						mi.itf = false;
						hit(c, "sunMoon");
					} else if (named(m, "updateChunks", "func_174967_a") && call(i, "nanoTime")) {
						filterAfter(c, m, i, "chunkNanoTime", "(J)J", "chunkUpdates");
					} else if (named(m, "renderBlockLayer", "func_174977_a") && m.desc.contains("DI") && isConst(i, 15)) {
						filterAfter(c, m, i, "resorts", "(I)I", "translucent");
					}
				}
				if (named(m, "updateChunks", "func_174967_a") && m.desc.equals("(J)V")) {
					m.instructions.insert(hook("chunksBegin", "()V"));
				} else if (named(m, "updateRenderers", "func_72716_a")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (isConst(i, 2)) {
							filterAfter(c, m, i, "farChunks", "(I)I", "chunkUpdates");
							break;
						}
					}
				} else if (named(m, "doSpawnParticle", "func_72726_b") && m.desc.startsWith("(Ljava/lang/String;")) {
					guard(c, m, list(load(Opcodes.ALOAD, 1), hook("noParticle", "(Ljava/lang/String;)Z")), list(op(Opcodes.ACONST_NULL), op(Opcodes.ARETURN)), "particles");
				}
			}
		}, "net.minecraft.client.renderer.RenderGlobal");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "shouldSideBeRendered", "func_149646_a", "func_176225_a")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (field(i, "Z") && ((FieldInsnNode)i).owner.startsWith("net/minecraft/block/BlockLeaves")) {
							filterAfter(c, m, i, "treesCull", "(Z)Z", "treesCull");
						}
					}
				}
			}
		}, "net.minecraft.block.BlockLeaves", "net.minecraft.block.BlockLeavesBase");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "doRenderLayer", "func_177141_a") && m.desc.contains("AbstractClientPlayer")) {
					guard(c, m, list(hook("noCapes", "()Z")), list(op(Opcodes.RETURN)), "capes");
				}
			}
		}, "net.minecraft.client.renderer.entity.layers.LayerCape");

		on(c -> {
			for (MethodNode m : c.methods) {
				for (AbstractInsnNode i : m.instructions.toArray()) {
					if (named(m, "renderEquippedItems", "func_77029_c") && call(i, "func_152122_n")) {
						filterAfter(c, m, i, "capes", "(Z)Z", "capes");
					}
				}
			}
		}, "net.minecraft.client.renderer.entity.RenderPlayer");

		on(c -> {
			for (MethodNode m : c.methods) {
				for (AbstractInsnNode i : m.instructions.toArray()) {
					if (named(m, "renderDroppedItem", "func_77020_a") && field(i, "Z", "fancyGraphics", "field_74347_j")) {
						filterAfter(c, m, i, "droppedFancy", "(Z)Z", "droppedItems");
					}
				}
			}
		}, "net.minecraft.client.renderer.entity.RenderItem");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "func_177078_a", "getModelCount") && m.desc.endsWith(")I")) {
					filterReturns(c, m, "droppedCount", "(I)I", "droppedItems");
				}
			}
		}, "net.minecraft.client.renderer.entity.RenderEntityItem");

		on(c -> {
			for (MethodNode m : c.methods) {
				for (AbstractInsnNode i : m.instructions.toArray()) {
					if (named(m, "doRenderShadowAndFire", "func_76979_b") && field(i, "Z", "fancyGraphics", "field_74347_j")) {
						filterAfter(c, m, i, "shadows", "(Z)Z", "shadows");
					}
				}
			}
		}, "net.minecraft.client.renderer.entity.Render");

		on(c -> {
			for (MethodNode m : c.methods) {
				for (AbstractInsnNode i : m.instructions.toArray()) {
					if (named(m, "renderGameOverlay", "func_73830_a", "func_175180_a") && call(i, "isFancyGraphicsEnabled", "func_71375_t")) {
						filterAfter(c, m, i, "vignette", "(Z)Z", "vignette");
					}
				}
			}
		}, "net.minecraft.client.gui.GuiIngame", "net.minecraftforge.client.GuiIngameForge");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getBiomeGrassColor", "func_150558_b", "getBiomeFoliageColor", "func_150571_c", "getGrassColorAtPos", "func_180627_b",
					"getFoliageColorAtPos", "func_180625_c") && m.desc.endsWith(")I")) {
					InsnList body = loadArgs(m);
					body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, c.superName, m.name, m.desc, false));
					body.add(op(Opcodes.IRETURN));
					guard(c, m, list(hook("noSwamp", "()Z")), body, "swamp");
				}
			}
		}, "net.minecraft.world.biome.BiomeGenSwamp", "net.minecraft.world.biome.BiomeSwamp");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getColorAtPos", "func_180285_a") && (m.access & Opcodes.ACC_STATIC) != 0) {
					MethodInsnNode biome = null, resolve = null;
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (i instanceof MethodInsnNode && ((MethodInsnNode)i).owner.equals("net/minecraft/world/IBlockAccess") && biome == null) {
							biome = (MethodInsnNode)i;
						} else if (i instanceof MethodInsnNode && ((MethodInsnNode)i).owner.endsWith("BiomeColorHelper$ColorResolver") && resolve == null) {
							resolve = (MethodInsnNode)i;
						}
					}
					if (biome != null && resolve != null) { // return resolver.getColorAtPos(world.getBiome(pos), pos);
						guard(c, m, list(hook("noBiomeBlend", "()Z")), list(load(Opcodes.ALOAD, 2), load(Opcodes.ALOAD, 0), load(Opcodes.ALOAD, 1),
							biome.clone(null), load(Opcodes.ALOAD, 1), resolve.clone(null), op(Opcodes.IRETURN)), "smoothBiomes");
					}
				}
			}
		}, "net.minecraft.world.biome.BiomeColorHelper");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "isInRangeToRenderDist", "func_70112_a") && m.desc.equals("(D)Z")) {
					m.instructions.insert(list(load(Opcodes.DLOAD, 1), hook("entityDist", "(D)D"), load(Opcodes.DSTORE, 1)));
					hit(c, "entityDistance");
				}
			}
		}, "net.minecraft.entity.Entity");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getMaxRenderDistanceSquared", "func_145833_n") && m.desc.equals("()D")) {
					filterReturns(c, m, "tileDist", "(D)D", "entityDistance");
				}
			}
		}, "net.minecraft.tileentity.TileEntity");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "setTextureBlurMipmap", "func_147954_b", "setBlurMipmapDirect", "func_174937_a") && m.desc.equals("(ZZ)V")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (i.getOpcode() == Opcodes.INVOKESTATIC && call(i, "glTexParameteri", "func_187421_b") && ((MethodInsnNode)i).desc.equals("(III)V")) {
							MethodInsnNode mi = (MethodInsnNode)i;
							mi.owner = H;
							mi.name = "texParameteri";
							mi.itf = false;
							hit(c, "mipmapType");
						}
					}
				}
			}
		}, "net.minecraft.client.renderer.texture.TextureUtil", "net.minecraft.client.renderer.texture.AbstractTexture");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "updateAnimation", "func_94219_l") && m.desc.equals("()V")) {
					guard(c, m, list(load(Opcodes.ALOAD, 0), hook("skipSprite", "(" + OBJ + ")Z")), list(op(Opcodes.RETURN)), "animation");
				}
			}
		}, "net.minecraft.client.renderer.texture.TextureAtlasSprite", "net.minecraft.client.renderer.texture.TextureClock",
			"net.minecraft.client.renderer.texture.TextureCompass");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "addEffect", "func_78873_a")) {
					guard(c, m, list(load(Opcodes.ALOAD, 1), hook("noEffect", "(" + OBJ + ")Z")), list(op(Opcodes.RETURN)), "particles");
				} else if (named(m, "spawnEffectParticle", "func_178927_a") && m.desc.startsWith("(I")) {
					guard(c, m, list(load(Opcodes.ILOAD, 1), hook("noParticle", "(I)Z")), list(op(Opcodes.ACONST_NULL), op(Opcodes.ARETURN)), "particles");
				}
			}
		}, "net.minecraft.client.particle.EffectRenderer", "net.minecraft.client.particle.ParticleManager");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "tick", "func_71217_p") && m.desc.equals("()V")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (isConst(i, 900)) {
							filterAfter(c, m, i, "autosave", "(I)I", "autosave");
						}
					}
				}
			}
		}, "net.minecraft.server.MinecraftServer");

		on(c -> {
			for (MethodNode m : c.methods) {
				boolean sin = named(m, "sin", "func_76126_a"), cos = named(m, "cos", "func_76134_b");
				if ((sin || cos) && m.desc.equals("(F)F")) {
					guard(c, m, list(hook("fastMath", "()Z")), list(load(Opcodes.FLOAD, 0), hook(sin ? "sin" : "cos", "(F)F"), op(Opcodes.FRETURN)), "fastMath");
				}
			}
		}, "net.minecraft.util.MathHelper", "net.minecraft.util.math.MathHelper");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "func_152622_a") && m.desc.equals("(I)V")) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (isConst(i, 20)) {
							filterAfter(c, m, i, "viewDistanceMax", "(I)I", "renderDistance");
						}
					}
				}
			}
		}, "net.minecraft.server.management.PlayerManager");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "renderModel", "func_178267_a") && m.desc.endsWith("Z)Z")) {
					m.instructions.insert(list(load(Opcodes.ALOAD, 2), load(Opcodes.ALOAD, 1), load(Opcodes.ALOAD, 3), load(Opcodes.ALOAD, 4),
						hook("blockModel", "(" + OBJ + OBJ + OBJ + OBJ + ")" + OBJ),
						new TypeInsnNode(Opcodes.CHECKCAST, Type.getArgumentTypes(m.desc)[1].getInternalName()), load(Opcodes.ASTORE, 2)));
					hit(c, "betterGrass");
				}
			}
		}, "net.minecraft.client.renderer.BlockModelRenderer");

		on(c -> {
			for (MethodNode m : c.methods) {
				if (named(m, "getRandomModel", "func_188627_a") && m.desc.startsWith("(J)")) {
					m.instructions.insert(list(load(Opcodes.LLOAD, 1), hook("altRand", "(J)J"), load(Opcodes.LSTORE, 1)));
					hit(c, "alternateBlocks");
				}
			}
		}, "net.minecraft.client.renderer.block.model.WeightedBakedModel");
	}
}
