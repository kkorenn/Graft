package dev.graft.legacy;

import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.Display;

/** -Dgraft.bench=true: Display.update() lands here in both renderers and logs frames per second every 5 s. */
public final class Bench {
	private static long start = System.nanoTime();
	private static int frames;
	private static final String WORLD = System.getProperty("graft.world");
	private static boolean joined, deactivated, scene;

	public static void update() {
		// -Dgraft.world=<save>: join that world from the title screen without any input (dev benchmarks).
		if (WORLD != null && !joined && net.minecraft.client.Minecraft.getMinecraft().currentScreen instanceof net.minecraft.client.gui.GuiMainMenu) {
			joined = true;
			net.minecraft.client.Minecraft.getMinecraft().launchIntegratedServer(WORLD, WORLD, null);
		}
		if (GlTransformer.METAL) {
			Win.update();
		} else {
			Display.update();
			if (frames == 0 && !deactivated) { // same as MGL.attach in bench mode: give focus back
				deactivated = true;
				Objc.deactivate();
			}
		}
		frames++;
		if (WORLD != null && joined && !scene && frames == 200) {
			scene = true;
			buildScene();
		}
		long now = System.nanoTime();
		if (now - start >= 5_000_000_000L) {
			LogManager.getLogger("Graft").info("GRAFT_BENCH {} fps={} draws/frame={}", GlTransformer.METAL ? "metal" : "vanilla",
				(int)(frames * 1e9 / (now - start)), GlTransformer.METAL ? MGL.drawCalls / frames : -1);
			if (GlTransformer.METAL) {
				MGL.drawCalls = 0;
			}
			frames = 0;
			start = now;
		}
	}

	/** Dev only: puts tile entities, mobs, particles and text in front of the player, run as the server (no cheats needed). */
	private static void buildScene() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
		final net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
		if (server == null || mc.thePlayer == null) {
			return;
		}
		int x = (int)Math.floor(mc.thePlayer.posX), y = (int)Math.floor(mc.thePlayer.posY), z = (int)Math.floor(mc.thePlayer.posZ);
		final String[] cmds = {
			"time set 6000", "weather clear",
			"fill " + (x - 4) + " " + (y - 1) + " " + (z + 1) + " " + (x + 4) + " " + (y + 4) + " " + (z + 7) + " air",
			"fill " + (x - 4) + " " + (y - 1) + " " + (z + 1) + " " + (x + 4) + " " + (y - 1) + " " + (z + 7) + " stone",
			"setblock " + (x - 2) + " " + y + " " + (z + 3) + " chest",
			"setblock " + x + " " + y + " " + (z + 4) + " standing_sign 8 replace {Text1:\"{\\\"text\\\":\\\"Graft\\\"}\",Text2:\"{\\\"text\\\":\\\"1.8.9\\\"}\"}",
			"setblock " + (x + 2) + " " + y + " " + (z + 3) + " enchanting_table",
			"setblock " + (x + 3) + " " + y + " " + (z + 5) + " torch",
			"setblock " + (x - 3) + " " + y + " " + (z + 5) + " standing_banner 8 replace {Base:1}",
			"setblock " + (x + 1) + " " + y + " " + (z + 6) + " glass",
			"summon Sheep " + (x - 1) + " " + y + " " + (z + 5) + " {NoAI:1}",
			"summon Zombie " + (x + 1) + " " + y + " " + (z + 5) + " {NoAI:1,Equipment:[{id:diamond_sword},{},{},{id:iron_chestplate},{id:golden_helmet}]}",
			"summon ArmorStand " + (x + 3) + " " + y + " " + (z + 3) + " {ShowArms:1,Equipment:[{},{},{},{id:diamond_chestplate},{id:pumpkin}]}",
			"tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 0 25",
			"effect @e[type=Sheep] 20 60 0", // takes damage every 2 s: hurt flash
			"say Metal renderer scene"};
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				for (String c : cmds) {
					server.getCommandManager().executeCommand(server, c);
				}
			}
		});
	}

	private Bench() {
	}
}
