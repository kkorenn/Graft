package dev.graft.legacy.shaders;

import dev.graft.legacy.MGL;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;

/**
 * Minecraft 1.7.10 side of {@link ShaderPipeline}: the calls {@link ShaderTransformer} injects, and the frame state.
 * 1.7.10 chunks are display lists of Tessellator draws (MGL's generic path, no native terrain buffers): the shadow pass
 * replays those lists, and Tessellator quads get per-face normals while a pack is on. The extended chunk attributes
 * (mc_Entity, mc_midTexCoord, at_tangent) are not produced on 1.7.10: packs read them as constants.
 */
public final class Hooks {
	private static boolean initialized;
	private static double camX, camY, camZ;
	private static float partialTicks;
	private static int terrainGeneration;
	private static Field worldRenderers, glRenderList, isInitialized, rawBuffer, vertexCount, drawMode, hasNormals;

	static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			worldRenderers = field(RenderGlobal.class, "worldRenderers", "field_72765_l");
			glRenderList = field(WorldRenderer.class, "glRenderList", "field_78942_y");
			isInitialized = field(WorldRenderer.class, "isInitialized", "field_78915_A");
			rawBuffer = field(Tessellator.class, "rawBuffer", "field_78405_h");
			vertexCount = field(Tessellator.class, "vertexCount", "field_78406_i");
			drawMode = field(Tessellator.class, "drawMode", "field_78409_u");
			hasNormals = field(Tessellator.class, "hasNormals", "field_78413_q");
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("Graft shaders: " + e.getMessage(), e);
		}
		ShaderPipeline.mcVersion = 10710;
		ShaderPipeline.init(Minecraft.getMinecraft().mcDataDir);
		ShaderPipeline.blockTableBuilder = new java.util.function.Function<IdMap, int[]>() {
			@Override
			public int[] apply(IdMap ids) {
				return blockTable(ids);
			}
		};
		ShaderPipeline.shadowTerrain = new Runnable() {
			@Override
			public void run() {
				shadowTerrain(0);
			}
		};
		ShaderPipeline.shadowTranslucent = new Runnable() {
			@Override
			public void run() {
				shadowTerrain(1);
			}
		};
		ShaderPipeline.shadowEntities = new Runnable() {
			@Override
			public void run() {
				shadowEntities();
			}
		};
	}

	private static Field field(Class<?> c, String mcp, String srg) throws NoSuchFieldException {
		for (String n : new String[] {mcp, srg}) {
			try {
				Field f = c.getDeclaredField(n);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(c.getName() + "." + mcp);
	}

	/** EntityRenderer.renderWorld, right after Graft's render-scale target is bound. */
	public static void beginWorld(float pt) {
		init();
		Minecraft mc = Minecraft.getMinecraft();
		EntityLivingBase e = mc.renderViewEntity;
		WorldClient w = mc.theWorld;
		if (e == null || w == null) {
			ShaderPipeline.active = false;
			return;
		}
		FrameState f = ShaderPipeline.frame;
		f.partialTicks = partialTicks = pt;
		f.cameraX = camX = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
		f.cameraY = camY = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
		f.cameraZ = camZ = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
		f.eyeAltitude = (float)(f.cameraY + e.getEyeHeight());
		f.worldTime = w.getWorldTime();
		f.dimension = w.provider.dimensionId;
		f.moonPhase = w.getMoonPhase();
		f.celestialAngle = w.getCelestialAngle(pt);
		f.rainStrength = w.getRainStrength(pt);
		f.renderDistanceChunks = mc.gameSettings.renderDistanceChunks;
		f.screenBrightness = mc.gameSettings.gammaSetting;
		f.hideGUI = mc.gameSettings.hideGUI;
		f.isEyeInWater = e.isInsideOfMaterial(Material.water) ? 1 : e.isInsideOfMaterial(Material.lava) ? 2 : 0;
		int light = e.getBrightnessForRender(pt);
		f.eyeBrightnessBlock = light >> 4 & 15;
		f.eyeBrightnessSky = light >> 20 & 15;
		f.nightVision = f.blindness = 0;
		f.heldItemId = -1;
		f.heldBlockLight = 0;
		if (e.isPotionActive(Potion.nightVision)) {
			int d = e.getActivePotionEffect(Potion.nightVision).getDuration();
			f.nightVision = d > 200 ? 1 : 0.7f + (float)Math.sin((d - pt) * Math.PI * 0.2) * 0.3f;
		}
		if (e.isPotionActive(Potion.blindness)) {
			f.blindness = Math.min(1, e.getActivePotionEffect(Potion.blindness).getDuration() / 20f);
		}
		ItemStack held = e.getHeldItem();
		if (held != null && held.getItem() != null) {
			f.heldItemId = itemId(held);
			Block b = Block.getBlockFromItem(held.getItem());
			f.heldBlockLight = b != null ? b.getLightValue() : 0;
		}
		Vec3 sky = w.getSkyColor(e, pt), fog = w.getFogColor(pt);
		f.skyColor[0] = (float)sky.xCoord;
		f.skyColor[1] = (float)sky.yCoord;
		f.skyColor[2] = (float)sky.zCoord;
		f.fogColor[0] = (float)fog.xCoord;
		f.fogColor[1] = (float)fog.yCoord;
		f.fogColor[2] = (float)fog.zCoord;
		int bx = MathHelper.floor_double(e.posX), by = MathHelper.floor_double(e.posY), bz = MathHelper.floor_double(e.posZ);
		BiomeGenBase biome = w.getBiomeGenForCoords(bx, bz);
		f.biome = biome.biomeID;
		f.temperature = biome.getFloatTemperature(bx, by, bz);
		f.rainfall = biome.getFloatRainfall();
		int atlas = mc.getTextureMapBlocks().getGlTextureId();
		f.atlasWidth = MGL.textureSize(atlas, false);
		f.atlasHeight = MGL.textureSize(atlas, true);
		String boss = BossStatus.statusBarTime > 0 ? BossStatus.bossName : null;
		f.bossBattle = boss == null ? 0 : boss.contains("Dragon") ? 2 : boss.contains("Wither") ? 3 : 1;
		ShaderPipeline.beginWorld();
		if (terrainGeneration != ShaderPipeline.terrainGeneration) { // pack on/off/changed: rebuild chunks (normals)
			terrainGeneration = ShaderPipeline.terrainGeneration;
			entityIdCache.clear();
			if (mc.renderGlobal != null) {
				mc.renderGlobal.loadRenderers();
			}
		}
	}

	/** Profiler.endStartSection inside the world pass. */
	public static void endStartSection(Profiler p, String name) {
		p.endStartSection(name);
		ShaderPipeline.section(name);
	}

	/** Profiler.startSection inside the world pass. */
	public static void startSection(Profiler p, String name) {
		p.startSection(name);
		ShaderPipeline.section(name);
	}

	/** GL11.glClear inside the world pass (MGL.glClear once Graft's transformer ran). */
	public static void clear(int mask) {
		if (ShaderPipeline.clear(mask)) {
			MGL.glClear(mask);
		}
	}

	/** RenderGlobal.renderSky: the celestial angle of the sun/moon rotation, after tilting by the pack's sunPathRotation. */
	public static float skyAngle(WorldClient w, float pt) {
		float r = ShaderPipeline.sunPathRotation();
		if (r != 0) {
			MGL.glRotatef(r, 0, 0, 1);
		}
		return w.getCelestialAngle(pt);
	}

	/** EntityRenderer.renderWorld, before Graft's render scale ends. */
	public static void endWorld() {
		ShaderPipeline.endWorld();
	}

	// ---------------------------------------------------------------- terrain

	/** Tessellator.draw: per-face normals for quads without any while a pack is on (1.7.10 blocks set none). */
	public static void tessellatorDraw(Tessellator t) {
		if (!ShaderPipeline.extendedTerrain) {
			return;
		}
		try {
			if (hasNormals.getBoolean(t) || drawMode.getInt(t) != 7) {
				return;
			}
			int[] raw = (int[])rawBuffer.get(t);
			int n = vertexCount.getInt(t);
			for (int q = 0; q + 3 < n; q += 4) {
				int o = q * 8;
				float ax = f(raw, o + 16) - f(raw, o), ay = f(raw, o + 17) - f(raw, o + 1), az = f(raw, o + 18) - f(raw, o + 2);
				float bx = f(raw, o + 24) - f(raw, o + 8), by = f(raw, o + 25) - f(raw, o + 9), bz = f(raw, o + 26) - f(raw, o + 10);
				float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
				float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
				int packed = len < 1e-12f ? 127 << 8 : (Math.round(nx / len * 127) & 255) | (Math.round(ny / len * 127) & 255) << 8
					| (Math.round(nz / len * 127) & 255) << 16;
				for (int k = 0; k < 4; k++) {
					raw[o + k * 8 + 6] = packed;
				}
			}
			hasNormals.setBoolean(t, true);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static float f(int[] raw, int i) {
		return Float.intBitsToFloat(raw[i]);
	}

	/** Shadow pass: the chunk display lists of one pass (0 opaque, 1 translucent) inside the shadow view. */
	static void shadowTerrain(int pass) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.renderGlobal == null) {
			return;
		}
		mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
		try {
			WorldRenderer[] wrs = (WorldRenderer[])worldRenderers.get(mc.renderGlobal);
			if (wrs == null) {
				return;
			}
			for (WorldRenderer wr : wrs) {
				if (wr == null || wr.skipRenderPass[pass] || !isInitialized.getBoolean(wr)
					|| !ShaderPipeline.inShadowView(wr.posX + 8 - camX, wr.posY + 8 - camY, wr.posZ + 8 - camZ)) {
					continue;
				}
				MGL.glPushMatrix();
				MGL.glTranslatef((float)(wr.posXMinus - camX), (float)(wr.posYMinus - camY), (float)(wr.posZMinus - camZ));
				MGL.glCallList(glRenderList.getInt(wr) + pass);
				MGL.glPopMatrix();
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---------------------------------------------------------------- entities

	private static final IdentityHashMap<Class<?>, Integer> entityIdCache = new IdentityHashMap<Class<?>, Integer>();

	/** RenderGlobal.renderEntities: RenderManager.renderEntitySimple with entityId/entityColor, and the shadow pass's filter. */
	public static boolean renderEntity(RenderManager rm, Entity e, float pt) {
		if (ShaderPipeline.inShadow) {
			boolean player = e == Minecraft.getMinecraft().renderViewEntity;
			boolean entities = ShaderPipeline.flag("shadowEntities", true);
			if (player ? !ShaderPipeline.flag("shadowPlayer", entities) : !entities) {
				return false;
			}
		}
		if (ShaderPipeline.active) {
			float r = 0, g = 0, b = 0, a = 0;
			if (e instanceof EntityLivingBase && (((EntityLivingBase)e).hurtTime > 0 || ((EntityLivingBase)e).deathTime > 0)) {
				r = 1;
				a = 0.3f;
			}
			if (e instanceof EntityCreeper) {
				float f = ((EntityCreeper)e).getCreeperFlashIntensity(pt);
				if ((int)(f * 10) % 2 != 0) {
					r = g = b = 1;
					a = Math.min(1, f * 0.2f);
				}
			}
			ShaderPipeline.setEntity(entityId(e), r, g, b, a);
		}
		return rm.renderEntitySimple(e, pt);
	}

	/** RenderGlobal.renderEntities: TileEntityRendererDispatcher.renderTileEntity with blockEntityId. */
	public static void renderTileEntity(TileEntityRendererDispatcher d, TileEntity te, float pt) {
		if (ShaderPipeline.inShadow && !ShaderPipeline.flag("shadowBlockEntities", true)) {
			return;
		}
		if (ShaderPipeline.active) {
			Block b = te.getBlockType();
			ShaderPipeline.setBlockEntity(b == null ? -1 : blockId(b, te.getBlockMetadata()));
		}
		d.renderTileEntity(te, pt);
	}

	/** block.properties id of a block + metadata (the legacy numeric id where the pack maps none). */
	static int blockId(Block b, int meta) {
		int id = Block.getIdFromBlock(b), key = id << 4 | meta & 15;
		int[] t = ShaderPipeline.blockIdTable;
		return t != null && key >= 0 && key < t.length ? t[key] : id;
	}

	/** (id << 4 | meta) -> pack id for every registered block (loader thread; 1.7.10 blocks have no state properties). */
	static int[] blockTable(IdMap ids) {
		int[] t = new int[4096 << 4];
		for (Object o : Block.blockRegistry) {
			Block b = (Block)o;
			int id = Block.getIdFromBlock(b);
			if (id < 0 || id >= 4096) {
				continue;
			}
			String name = Block.blockRegistry.getNameForObject(b);
			for (int m = 0; m < 16; m++) {
				int v = ids.get(name, id, m, IdMap.NO_PROPS);
				t[id << 4 | m] = v >= 0 ? v : id;
			}
		}
		return t;
	}

	private static int entityId(Entity e) {
		Integer id = entityIdCache.get(e.getClass());
		if (id == null) {
			IdMap ids = ShaderPipeline.entityIds;
			String legacy = e instanceof EntityPlayer ? "player" : e instanceof EntityLightningBolt ? "LightningBolt" : EntityList.getEntityString(e);
			int num = EntityList.getEntityID(e);
			int v = ids == null ? -1 : ids.get(legacy == null ? null : IdMap.modernEntityName(legacy), num > 0 ? num : -1, -1, IdMap.NO_PROPS);
			entityIdCache.put(e.getClass(), id = v >= 0 ? v : ids == null && num > 0 ? num : -1);
		}
		return id;
	}

	private static int itemId(ItemStack s) {
		Item it = s.getItem();
		int raw = Item.getIdFromItem(it);
		IdMap ids = ShaderPipeline.itemIds;
		return ids == null ? raw : ids.get(String.valueOf(Item.itemRegistry.getNameForObject(it)), raw, s.getItemDamage(), IdMap.NO_PROPS);
	}

	private static final ICamera SHADOW_CAMERA = new ICamera() {
		@Override
		public boolean isBoundingBoxInFrustum(AxisAlignedBB bb) {
			return ShaderPipeline.inShadowView((bb.minX + bb.maxX) / 2 - camX, (bb.minY + bb.maxY) / 2 - camY, (bb.minZ + bb.maxZ) / 2 - camZ);
		}

		@Override
		public void setPosition(double x, double y, double z) {
		}
	};

	/** Shadow pass: entities and block entities seen from the sun (1.7.10 has no GL state cache to keep in step). */
	static void shadowEntities() {
		Minecraft mc = Minecraft.getMinecraft();
		EntityLivingBase view = mc.renderViewEntity;
		if (view == null || mc.renderGlobal == null) {
			return;
		}
		MGL.glPushAttrib(0xFFFFF);
		MGL.glEnable(0xB71);
		MGL.glDepthMask(true);
		MGL.glDisable(0xBE2);
		MGL.glEnable(0xBC0);
		MGL.glAlphaFunc(516, 0.1f);
		int pass = MinecraftForgeClient.getRenderPass();
		ForgeHooksClient.setRenderPass(0);
		mc.renderGlobal.renderEntities(view, SHADOW_CAMERA, partialTicks);
		if (mc.gameSettings.thirdPersonView == 0 && !view.isPlayerSleeping()) { // the game skips the player in first person
			renderEntity(RenderManager.instance, view, partialTicks);
		}
		ForgeHooksClient.setRenderPass(pass);
		RenderHelper.disableStandardItemLighting();
		MGL.glPopAttrib();
	}

	private Hooks() {
	}
}
