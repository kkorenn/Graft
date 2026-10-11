package dev.graft.v26_2.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dgraft.bench=true: once in a world, wait 20s for chunks, sample FPS once a second for 30s, log, quit. */
@Mixin(Minecraft.class)
public abstract class BenchMixin {
	private static final int WARMUP_TICKS = Integer.getInteger("graft.bench.warmup", 400);
	private static final int SAMPLES = 30;
	private int graft$ticks;
	private boolean graft$summoned;
	private final int[] graft$fps = new int[SAMPLES];
	/** Render-thread CPU time per frame (ms): the CPU-side cost, unaffected by GPU load or window throttling. */
	private final double[] graft$cpuMs = new double[SAMPLES];
	private long graft$lastCpu = -1;
	private final double[] graft$gpuMs = new double[SAMPLES];

	@Shadow
	public abstract int getFps();

	@Shadow
	public abstract void stop();

	@Inject(method = "tick", at = @At("TAIL"))
	private void graft$bench(CallbackInfo ci) {
		// Dev: -Dgraft.debugPitch=<deg> holds the camera pitch (negative = up), for screenshot checks.
		String pitch = System.getProperty("graft.debugPitch");
		var player = ((Minecraft)(Object)this).player;
		if (pitch != null && player != null) {
			player.setXRot(Float.parseFloat(pitch));
			String yaw = System.getProperty("graft.debugYaw");
			if (yaw != null) {
				// debugYawSpeed: degrees per tick, to check temporal effects (TAA reprojection) under camera motion.
				player.setYRot(Float.parseFloat(yaw) + player.tickCount * Float.parseFloat(System.getProperty("graft.debugYawSpeed", "0")));
			}
			String pos = System.getProperty("graft.debugPos"); // "x,y,z": start position (teleported on the server)
			double move = Double.parseDouble(System.getProperty("graft.debugMove", "0")); // blocks/tick along +x
			var server = ((Minecraft)(Object)this).getSingleplayerServer();
			if (pos != null && server != null && player.tickCount > 60 && player.getY() < Double.parseDouble(pos.split(",")[1]) - 8) {
				String[] p = pos.split(",");
				server.execute(() -> {
					var sp = server.getPlayerList().getPlayers().get(0);
					sp.getAbilities().mayfly = true;
					sp.getAbilities().flying = true;
					sp.onUpdateAbilities();
					sp.teleportTo(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
				});
			}
			if (pos != null && server == null && player.tickCount == 60) { // multiplayer test clients are server ops
				String[] p = pos.split(",");
				player.connection.sendCommand("tp @s " + p[0] + " " + p[1] + " " + p[2]);
			}
			if (pos != null && player.tickCount >= 60) {
				player.getAbilities().flying = true; // hover from the teleport on, not only once moving starts
				if (move == 0 || player.tickCount <= 80) {
					player.setDeltaMovement(0, 0, 0);
				}
			}
			if ((pos != null || server == null) && move != 0 && player.tickCount > 80) {
				// Real flight: the client moves itself and the server accepts it, like a player flying in creative.
				// debugMoveOsc=true: back and forth along x (stays in view of other test clients).
				player.getAbilities().flying = true;
				double v = Boolean.getBoolean("graft.debugMoveOsc") ? move * Math.sin(player.tickCount * 0.08) : move;
				player.setDeltaMovement(v, 0, 0);
			}
		}
		// Dev: -Dgraft.debugThirdPerson=true shows the player model (F5).
		if (player != null && Boolean.getBoolean("graft.debugThirdPerson")) {
			((Minecraft)(Object)this).options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		}
		// Dev: -Dgraft.debugSummon=true summons a few walking mobs in front of the player once (entity checks).
		if (player != null && Boolean.getBoolean("graft.debugSummon") && !graft$summoned && player.tickCount > 40) {
			graft$summoned = true;
			for (String mob : System.getProperty("graft.debugMobs", "cow,pig,villager,zombie,sheep,chicken,skeleton,horse").split(",")) {
				// Absolute coordinates from the client camera (the server may not agree on where a pinned player is).
				var look = player.getLookAngle();
				double d = 4 + Math.random() * 8;
				double side = Math.random() * 3 - 1.5;
				double x = player.getX() + look.x * d - look.z * side;
				double z = player.getZ() + look.z * d + look.x * side;
				double y = player.getEyeY() - 1.2;
				String cmd = String.format(java.util.Locale.ROOT, "summon %s %.2f %.2f %.2f {NoGravity:1b,NoAI:1b}", mob, x, y, z);
				var server = ((Minecraft)(Object)this).getSingleplayerServer();
				if (server != null) { // run on the integrated server: test worlds have cheats off
					server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
				}
			}
		}
		if (!Boolean.getBoolean("graft.bench") || ((Minecraft)(Object)this).level == null) {
			return;
		}
		int t = ++graft$ticks - WARMUP_TICKS;
		if (t <= 0 || t % 20 != 0) {
			return;
		}
		int i = t / 20 - 1;
		long cpu = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
		if (i < SAMPLES) {
			graft$fps[i] = getFps();
			graft$cpuMs[i] = graft$lastCpu >= 0 && graft$fps[i] > 0 ? (cpu - graft$lastCpu) / 1e6 / graft$fps[i] : 0;
			graft$lastCpu = cpu;
			// GPU busy time per frame over the last second (vs 1000/fps total frame time).
			double gpu = dev.graft.v26_2.MetalGpuDevice.current().commands().takeGpuSeconds();
			graft$gpuMs[i] = graft$fps[i] > 0 ? gpu * 1000.0 / graft$fps[i] : 0;
			return;
		}
		double[] g = graft$gpuMs.clone();
		java.util.Arrays.sort(g);
		LogUtils.getLogger().info("GRAFT_BENCH gpu ms/frame median={} min={} max={}", String.format("%.2f", g[SAMPLES / 2]), String.format("%.2f", g[0]), String.format("%.2f", g[SAMPLES - 1]));
		double[] c = java.util.Arrays.copyOfRange(graft$cpuMs, 1, SAMPLES);
		java.util.Arrays.sort(c);
		LogUtils.getLogger().info("GRAFT_BENCH cpu ms/frame median={} min={} max={}", String.format("%.3f", c[c.length / 2]), String.format("%.3f", c[0]), String.format("%.3f", c[c.length - 1]));
		int[] s = graft$fps.clone();
		java.util.Arrays.sort(s);
		LogUtils.getLogger().info("GRAFT_BENCH avg={} median={} min={} max={} samples={}",
			java.util.Arrays.stream(s).average().orElse(0), s[SAMPLES / 2], s[0], s[SAMPLES - 1], java.util.Arrays.toString(graft$fps));
		stop();
	}
}
