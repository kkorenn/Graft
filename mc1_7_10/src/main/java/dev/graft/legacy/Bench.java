package dev.graft.legacy;

import org.apache.logging.log4j.LogManager;

/** -Dgraft.bench=true (dev): logs frames per second every 5 s; -Dgraft.world=<save> joins that save. */
public final class Bench {
	private static long start = System.nanoTime();
	private static int frames;
	private static final String WORLD = System.getProperty("graft.world");
	private static boolean joined;

	public static void update() {
		if (WORLD != null && !joined) {
			try {
				Object mc = Reflect.method("net.minecraft.client.Minecraft", "getMinecraft", "func_71410_x").invoke(null);
				Object screen = Reflect.field("net.minecraft.client.Minecraft", "currentScreen", "field_71462_r").get(mc);
				if (screen != null && screen.getClass().getName().equals("net.minecraft.client.gui.GuiMainMenu")) {
					joined = true;
					Class<?> settings = Class.forName("net.minecraft.world.WorldSettings");
					Object saves = Reflect.method("net.minecraft.client.Minecraft", "getSaveLoader", "func_71359_d").invoke(mc);
					boolean exists = (Boolean)Reflect.method("net.minecraft.world.storage.ISaveFormat", "canLoadWorld", "func_90033_f", String.class).invoke(saves, WORLD);
					Object ws = null;
					if (!exists) {
						Class<?> gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
						Class<?> worldType = Class.forName("net.minecraft.world.WorldType");
						Object survival = gameType.getEnumConstants()[1];
						Object def = Reflect.field("net.minecraft.world.WorldType", "DEFAULT", "field_77137_b").get(null);
						ws = settings.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType).newInstance(1L, survival, true, false, def);
					}
					Reflect.method("net.minecraft.client.Minecraft", "launchIntegratedServer", "func_71371_a", String.class, String.class, settings)
						.invoke(mc, WORLD, WORLD, ws);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				joined = true;
				LogManager.getLogger("Graft").warn("Graft: bench world join failed", e);
			}
		}
		Win.update();
		frames++;
		long now = System.nanoTime();
		if (now - start >= 5_000_000_000L) {
			LogManager.getLogger("Graft").info("GRAFT_BENCH {} fps={} draws/frame={}", GlTransformer.METAL ? "metal" : "vanilla",
				(int)(frames * 1e9 / (now - start)), GlTransformer.METAL ? MGL.drawCalls / frames : -1);
			MGL.drawCalls = 0;
			frames = 0;
			start = now;
		}
	}

	private Bench() {
	}
}
