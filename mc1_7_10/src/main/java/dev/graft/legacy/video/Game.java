package dev.graft.legacy.video;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;

/** Minecraft 1.7.10 side of the video settings: vanilla options, world access and Forge events. */
public final class Game {
	static final int V = 7;
	private static Field fogR, fogG, fogB;

	private static Minecraft mc() {
		return Minecraft.getMinecraft();
	}

	/** Called by the mod: the replaced Video Settings screen, ticks, HUD and FOV. */
	public static void register() {
		Game g = new Game();
		MinecraftForge.EVENT_BUS.register(g);
		FMLCommonHandler.instance().bus().register(g);
	}

	static int get(String k) {
		GameSettings s = mc().gameSettings;
		switch (k) {
			case "graphics": return s.fancyGraphics ? 1 : 0;
			case "renderDistance": return s.renderDistanceChunks;
			case "ao": return s.ambientOcclusion;
			case "fps": return s.enableVsync ? 0 : s.limitFramerate >= 260 ? 26 : Math.max(1, s.limitFramerate / 10);
			case "guiScale": return s.guiScale;
			case "bobbing": return s.viewBobbing ? 1 : 0;
			case "gamma": return Math.round(s.gammaSetting * 100f);
			case "fullscreen": return mc().isFullScreen() ? 1 : 0;
			case "mipmap": return s.mipmapLevels;
			case "particles": return s.particleSetting;
			case "heldTooltips": return s.heldItemTooltips ? 1 : 0;
			case "advTooltips": return s.advancedItemTooltips ? 1 : 0;
			default: return 0;
		}
	}

	static void set(String k, int v) {
		Minecraft mc = mc();
		GameSettings s = mc.gameSettings;
		switch (k) {
			case "graphics": s.fancyGraphics = v != 0; reloadRenderers(); break;
			case "renderDistance": s.renderDistanceChunks = v; break;
			case "ao": s.ambientOcclusion = v; reloadRenderers(); break;
			case "fps":
				if (s.enableVsync != (v == 0)) {
					s.enableVsync = v == 0;
					Hooks.vsync(s.enableVsync);
				}
				s.limitFramerate = v == 0 || v >= 26 ? 260 : v * 10;
				break;
			case "guiScale": s.guiScale = v; break;
			case "bobbing": s.viewBobbing = v != 0; break;
			case "gamma": s.gammaSetting = v / 100f; break;
			case "fullscreen":
				s.fullScreen = v != 0;
				if (mc.isFullScreen() != s.fullScreen) {
					mc.toggleFullscreen();
				}
				break;
			case "mipmap": s.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, v); break;
			case "particles": s.particleSetting = v; break;
			case "heldTooltips": s.heldItemTooltips = v != 0; break;
			case "advTooltips": s.advancedItemTooltips = v != 0; break;
			default:
		}
	}

	static void saveVanilla() {
		mc().gameSettings.saveOptions();
	}

	static void reloadRenderers() {
		if (mc().renderGlobal != null && mc().theWorld != null) {
			mc().renderGlobal.loadRenderers();
		}
	}

	static void refreshResources() {
		mc().scheduleResourcesRefresh();
	}

	static boolean inWorld() {
		return mc().theWorld != null;
	}

	static int x(Object pos) {
		return 0;
	}

	static int y(Object pos) {
		return 0;
	}

	static int z(Object pos) {
		return 0;
	}

	static float farPlane() {
		return mc().gameSettings.renderDistanceChunks * 16f;
	}

	static boolean isRemote(Object world) {
		return ((World)world).isRemote;
	}

	static String spriteName(Object sprite) {
		return ((TextureAtlasSprite)sprite).getIconName();
	}

	static void bindTexture(Object textureManager, Object location) {
		((TextureManager)textureManager).bindTexture((ResourceLocation)location);
	}

	static void hideSunMoon() {
		Hooks.color(1f, 1f, 1f, 0f);
	}

	static Object fogColor(Object skyColor) {
		try {
			if (fogR == null) {
				fogR = ReflectionHelper.findField(EntityRenderer.class, "fogColorRed", "field_78518_n");
				fogG = ReflectionHelper.findField(EntityRenderer.class, "fogColorGreen", "field_78519_o");
				fogB = ReflectionHelper.findField(EntityRenderer.class, "fogColorBlue", "field_78533_p");
			}
			EntityRenderer r = mc().entityRenderer;
			return Vec3.createVectorHelper(fogR.getFloat(r), fogG.getFloat(r), fogB.getFloat(r));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return skyColor;
		}
	}

	static boolean playerPos(double[] out) {
		Entity p = mc().thePlayer;
		if (p == null) {
			return false;
		}
		out[0] = p.posX;
		out[1] = p.posY;
		out[2] = p.posZ;
		return true;
	}

	static void waterOpacity(int opacity) {
		Blocks.water.setLightOpacity(opacity);
		Blocks.flowing_water.setLightOpacity(opacity);
	}

	private static int itemLight(ItemStack stack) {
		if (stack == null || stack.getItem() == null) {
			return 0;
		}
		Item item = stack.getItem();
		Block block = Block.getBlockFromItem(item);
		Object name = Item.itemRegistry.getNameForObject(item);
		return Hooks.itemLight(name == null ? null : name.toString(), block == null ? 0 : block.getLightValue());
	}

	/** Light sources near the camera as x, y, z, level quadruples; returns the count. */
	static int scanLights(int[] out, int max) {
		Minecraft mc = mc();
		Entity view = mc.renderViewEntity;
		if (mc.theWorld == null || view == null) {
			return 0;
		}
		int n = 0;
		for (Object o : mc.theWorld.loadedEntityList) {
			Entity e = (Entity)o;
			if (n >= max) {
				break;
			}
			if (e.getDistanceSqToEntity(view) > 64 * 64) {
				continue;
			}
			int level = e.isBurning() ? 15 : 0;
			if (e instanceof EntityItem) {
				level = Math.max(level, itemLight(((EntityItem)e).getEntityItem()));
			} else if (e instanceof EntityLivingBase) {
				level = Math.max(level, itemLight(((EntityLivingBase)e).getHeldItem()));
			}
			if (level > 0) {
				out[n * 4] = MathHelper.floor_double(e.posX);
				out[n * 4 + 1] = MathHelper.floor_double(e.posY + e.getEyeHeight());
				out[n * 4 + 2] = MathHelper.floor_double(e.posZ);
				out[n * 4 + 3] = level;
				n++;
			}
		}
		return n;
	}

	static void markDirty(int x1, int y1, int z1, int x2, int y2, int z2) {
		if (mc().renderGlobal != null) {
			mc().renderGlobal.markBlockRangeForRenderUpdate(x1, y1, z1, x2, y2, z2);
		}
	}

	static void rect(int x1, int y1, int x2, int y2, int color) {
		Gui.drawRect(x1, y1, x2, y2, color);
	}

	static void text(String s, int x, int y, int color) {
		mc().fontRenderer.drawStringWithShadow(s, x, y, color);
	}

	static Object betterGrass(Object model, Object world, Object state, Object pos, boolean fancy) {
		return model;
	}

	@SubscribeEvent
	public void open(GuiOpenEvent e) {
		if (e.gui instanceof GuiVideoSettings) {
			e.gui = new VideoScreen(mc().currentScreen, VideoSettings.MAIN);
		}
	}

	@SubscribeEvent
	public void tick(TickEvent.ClientTickEvent e) {
		if (e.phase == TickEvent.Phase.END) {
			Hooks.clientTick();
		}
	}

	@SubscribeEvent
	public void frame(TickEvent.RenderTickEvent e) {
		if (e.phase == TickEvent.Phase.START) {
			Hooks.frame();
		}
	}

	@SubscribeEvent
	public void overlay(RenderGameOverlayEvent.Post e) {
		if (e.type == RenderGameOverlayEvent.ElementType.ALL) {
			Hooks.overlay(e.resolution.getScaledWidth(), e.resolution.getScaledHeight(), mc().gameSettings.showDebugInfo);
		}
	}

	@SubscribeEvent
	public void fov(FOVUpdateEvent e) {
		if (VideoSettings.DYNAMIC_FOV.v == 0 && !e.entity.isUsingItem()) {
			e.newfov = 1f;
		}
	}
}
