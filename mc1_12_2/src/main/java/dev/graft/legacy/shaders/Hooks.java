package dev.graft.legacy.shaders;

import dev.graft.legacy.MGL;
import dev.graft.legacy.Terrain;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.MobEffects;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Minecraft 1.12.2 side of {@link ShaderPipeline}: the calls {@link ShaderTransformer} injects, and the frame state. */
public final class Hooks {
	private static boolean initialized;
	private static double camX, camY, camZ;
	private static float partialTicks;
	private static int terrainGeneration;

	static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ShaderPipeline.mcVersion = 11202;
		ShaderPipeline.init(Minecraft.getMinecraft().gameDir);
		ShaderPipeline.blockTableBuilder = new java.util.function.Function<IdMap, int[]>() {
			@Override
			public int[] apply(IdMap ids) {
				return blockTable(ids);
			}
		};
		ShaderPipeline.shadowTerrain = new Runnable() {
			@Override
			public void run() {
				Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
				Terrain.drawShadow(camX, camY, camZ, false);
			}
		};
		ShaderPipeline.shadowTranslucent = new Runnable() {
			@Override
			public void run() {
				Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
				Terrain.drawShadow(camX, camY, camZ, true);
			}
		};
		ShaderPipeline.shadowEntities = new Runnable() {
			@Override
			public void run() {
				shadowEntities();
			}
		};
	}

	/** EntityRenderer.renderWorldPass, first instruction. */
	public static void beginWorld(float pt) {
		init();
		Minecraft mc = Minecraft.getMinecraft();
		Entity e = mc.getRenderViewEntity();
		WorldClient w = mc.world;
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
		f.dimension = w.provider.getDimension();
		f.moonPhase = w.getMoonPhase();
		f.celestialAngle = w.getCelestialAngle(pt);
		f.rainStrength = w.getRainStrength(pt);
		f.renderDistanceChunks = mc.gameSettings.renderDistanceChunks;
		f.screenBrightness = mc.gameSettings.gammaSetting;
		f.hideGUI = mc.gameSettings.hideGUI;
		f.isEyeInWater = e.isInsideOfMaterial(Material.WATER) ? 1 : e.isInsideOfMaterial(Material.LAVA) ? 2 : 0;
		int light = e.getBrightnessForRender();
		f.eyeBrightnessBlock = light >> 4 & 15;
		f.eyeBrightnessSky = light >> 20 & 15;
		f.nightVision = f.blindness = 0;
		f.heldItemId = -1;
		f.heldBlockLight = 0;
		if (e instanceof EntityLivingBase) {
			EntityLivingBase le = (EntityLivingBase)e;
			if (le.isPotionActive(MobEffects.NIGHT_VISION)) { // flickers out over the last 10 seconds, as the game's own night vision
				int d = le.getActivePotionEffect(MobEffects.NIGHT_VISION).getDuration();
				f.nightVision = d > 200 ? 1 : 0.7f + (float)Math.sin((d - pt) * Math.PI * 0.2) * 0.3f;
			}
			if (le.isPotionActive(MobEffects.BLINDNESS)) {
				f.blindness = Math.min(1, le.getActivePotionEffect(MobEffects.BLINDNESS).getDuration() / 20f);
			}
			ItemStack held = le.getHeldItemMainhand(), off = le.getHeldItemOffhand();
			f.heldItemId2 = -1;
			f.heldBlockLight2 = 0;
			if (!held.isEmpty()) {
				f.heldItemId = itemId(held);
				f.heldBlockLight = Block.getBlockFromItem(held.getItem()).getDefaultState().getLightValue();
			}
			if (!off.isEmpty()) {
				f.heldItemId2 = itemId(off);
				f.heldBlockLight2 = Block.getBlockFromItem(off.getItem()).getDefaultState().getLightValue();
			}
		}
		Vec3d sky = w.getSkyColor(e, pt), fog = w.getFogColor(pt);
		f.skyColor[0] = (float)sky.x;
		f.skyColor[1] = (float)sky.y;
		f.skyColor[2] = (float)sky.z;
		f.fogColor[0] = (float)fog.x;
		f.fogColor[1] = (float)fog.y;
		f.fogColor[2] = (float)fog.z;
		BlockPos pos = new BlockPos(e);
		Biome biome = w.getBiome(pos);
		f.biome = Biome.getIdForBiome(biome);
		f.temperature = biome.getTemperature(pos);
		f.rainfall = biome.getRainfall();
		int atlas = mc.getTextureMapBlocks().getGlTextureId();
		f.atlasWidth = MGL.textureSize(atlas, false);
		f.atlasHeight = MGL.textureSize(atlas, true);
		f.bossBattle = mc.ingameGUI.getBossOverlay().shouldPlayEndBossMusic() ? 2 : 0; // ponytail: other bosses need the overlay's private map
		ShaderPipeline.beginWorld();
		if (terrainGeneration != ShaderPipeline.terrainGeneration) { // pack on/off/changed: chunks in the other vertex layout
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

	/** GlStateManager.clear inside the world pass. */
	public static void clear(int mask) {
		if (ShaderPipeline.clear(mask)) {
			GlStateManager.clear(mask);
		}
	}

	/** RenderGlobal.renderSky: the celestial angle of the sun/moon rotation, after tilting by the pack's sunPathRotation. */
	public static float skyAngle(WorldClient w, float pt) {
		float r = ShaderPipeline.sunPathRotation();
		if (r != 0) {
			GlStateManager.rotate(r, 0, 0, 1);
		}
		return w.getCelestialAngle(pt);
	}

	/** EntityRenderer.renderWorldPass, before each return. */
	public static void endWorld() {
		ShaderPipeline.endWorld();
	}

	// ---------------------------------------------------------------- chunk building (worker threads)

	/** RenderChunk.rebuildChunk: BlockRendererDispatcher.renderBlock, tagging the block's vertices with its pack id. */
	public static boolean renderBlock(BlockRendererDispatcher d, IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder wr) {
		if (!ShaderPipeline.extendedTerrain) {
			return d.renderBlock(state, pos, world, wr);
		}
		int before = wr.getVertexCount();
		boolean drawn = d.renderBlock(state, pos, world, wr);
		int after = wr.getVertexCount();
		if (after > before && wr.getVertexFormat().getSize() == TerrainFormat.VANILLA) {
			Block b = state.getBlock();
			TerrainFormat.tag(wr.getByteBuffer(), before, after, blockId(b, b.getMetaFromState(state)));
		}
		return drawn;
	}

	/** block.properties id of a block + metadata (the legacy numeric id where the pack maps none). */
	static int blockId(Block b, int meta) {
		int id = Block.getIdFromBlock(b), key = id << 4 | meta & 15;
		int[] t = ShaderPipeline.blockIdTable;
		return t != null && key >= 0 && key < t.length ? t[key] : id;
	}

	/** (id << 4 | meta) -> pack id for every registered block state (loader thread). */
	@SuppressWarnings({"rawtypes", "unchecked"})
	static int[] blockTable(IdMap ids) {
		int[] t = new int[4096 << 4];
		Map<String, String> props = new HashMap<String, String>();
		for (Block b : Block.REGISTRY) {
			int id = Block.getIdFromBlock(b);
			if (id < 0 || id >= 4096) {
				continue;
			}
			String name = String.valueOf(Block.REGISTRY.getNameForObject(b));
			for (int m = 0; m < 16; m++) {
				props.clear();
				try {
					IBlockState st = b.getStateFromMeta(m);
					for (Object o : st.getProperties().entrySet()) {
						Map.Entry<IProperty, Comparable> e = (Map.Entry<IProperty, Comparable>)o;
						props.put(e.getKey().getName(), e.getKey().getName(e.getValue()));
					}
				} catch (RuntimeException ignored) {
					// a block without that metadata: match by name and id only
				}
				int v = ids.get(name, id, m, props);
				t[id << 4 | m] = v >= 0 ? v : id;
			}
		}
		return t;
	}

	// ---------------------------------------------------------------- entities (render thread)

	private static final IdentityHashMap<Class<?>, Integer> entityIdCache = new IdentityHashMap<Class<?>, Integer>();

	/** RenderGlobal.renderEntities: RenderManager.renderEntityStatic with entityId/entityColor, and the shadow pass's filter. */
	public static void renderEntity(RenderManager rm, Entity e, float pt, boolean debug) {
		if (ShaderPipeline.inShadow) {
			boolean player = e == Minecraft.getMinecraft().getRenderViewEntity();
			boolean entities = ShaderPipeline.flag("shadowEntities", true);
			if (player ? !ShaderPipeline.flag("shadowPlayer", entities) : !entities) {
				return;
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
		rm.renderEntityStatic(e, pt, debug);
	}

	/** RenderGlobal.renderEntities: TileEntityRendererDispatcher.render with blockEntityId. */
	public static void renderTileEntity(TileEntityRendererDispatcher d, TileEntity te, float pt, int destroyStage) {
		if (ShaderPipeline.inShadow && !ShaderPipeline.flag("shadowBlockEntities", true)) {
			return;
		}
		if (ShaderPipeline.active) {
			Block b = te.getBlockType();
			ShaderPipeline.setBlockEntity(b == null ? -1 : blockId(b, te.getBlockMetadata()));
		}
		d.render(te, pt, destroyStage);
	}

	private static int entityId(Entity e) {
		Integer id = entityIdCache.get(e.getClass());
		if (id == null) {
			IdMap ids = ShaderPipeline.entityIds;
			ResourceLocation key = EntityList.getKey(e);
			String name = e instanceof EntityPlayer ? "minecraft:player" : key != null ? key.toString() : null;
			int num = EntityList.getID(e.getClass());
			int v = ids == null ? -1 : ids.get(name, num > 0 ? num : -1, -1, IdMap.NO_PROPS);
			entityIdCache.put(e.getClass(), id = v >= 0 ? v : ids == null && num > 0 ? num : -1);
		}
		return id;
	}

	private static int itemId(ItemStack s) {
		Item it = s.getItem();
		int raw = Item.getIdFromItem(it);
		IdMap ids = ShaderPipeline.itemIds;
		return ids == null ? raw : ids.get(String.valueOf(Item.REGISTRY.getNameForObject(it)), raw, s.getMetadata(), IdMap.NO_PROPS);
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

	/** Shadow pass: entities and block entities seen from the sun (state set through GlStateManager so its cache stays true). */
	static void shadowEntities() {
		Minecraft mc = Minecraft.getMinecraft();
		Entity view = mc.getRenderViewEntity();
		if (view == null || mc.renderGlobal == null) {
			return;
		}
		GlStateManager.enableDepth();
		GlStateManager.depthMask(true);
		GlStateManager.disableBlend();
		GlStateManager.enableAlpha();
		GlStateManager.alphaFunc(516, 0.1f);
		GlStateManager.colorMask(true, true, true, true);
		int pass = MinecraftForgeClient.getRenderPass();
		ForgeHooksClient.setRenderPass(0);
		mc.renderGlobal.renderEntities(view, SHADOW_CAMERA, partialTicks);
		// first person: the game skips the player; its shadow still belongs in the map
		if (mc.gameSettings.thirdPersonView == 0 && !(view instanceof EntityLivingBase && ((EntityLivingBase)view).isPlayerSleeping())) {
			renderEntity(mc.getRenderManager(), view, partialTicks, false);
		}
		ForgeHooksClient.setRenderPass(pass);
		RenderHelper.disableStandardItemLighting();
	}

	private Hooks() {
	}
}
