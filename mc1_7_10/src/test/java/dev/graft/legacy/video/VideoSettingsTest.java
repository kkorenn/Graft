package dev.graft.legacy.video;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** graftvideo.txt round trip: values survive save/load, unknown keys are kept, bad lines are ignored. No game needed. */
public final class VideoSettingsTest {
	public static void main(String[] args) throws Exception {
		File dir = Files.createTempDirectory("graftvideo").toFile();
		File f = new File(dir, "graftvideo.txt");
		Files.write(f.toPath(), ("# comment\nfog:2\nfogStart:5\nfutureOption:abc\nentityDistance:9999\ntrees:x\nnot a line\n:3\n"
			+ "renderScale:70\ngraphics:0\n").getBytes(StandardCharsets.UTF_8));
		VideoSettings.load(f);
		check(VideoSettings.FOG.v == 2, "fog read");
		check(VideoSettings.FOG_START.v == 5, "fogStart read");
		check(VideoSettings.ENTITY_DISTANCE.v == 500, "out of range value clamped");
		check(VideoSettings.TREES.v == VideoSettings.TREES.def, "unparsable value falls back to the default");

		VideoSettings.DYNAMIC_LIGHTS.v = 2;
		VideoSettings.CLOUD_HEIGHT.v = 35;
		VideoSettings.WATER_ANIMATED.v = 0;
		VideoSettings.save();
		List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
		check(lines.contains("futureOption:abc"), "unknown key kept");
		check(lines.contains("dynamicLights:2"), "value written");
		check(!lines.contains("renderScale:70") && !lines.contains("graphics:0"), "values owned elsewhere are not written");
		check(!new File(dir, "graftvideo.txt.tmp").exists(), "temporary file renamed");

		VideoSettings.load(new File(dir, "missing.txt"));
		check(VideoSettings.DYNAMIC_LIGHTS.v == VideoSettings.DYNAMIC_LIGHTS.def, "defaults without a file");
		VideoSettings.load(f);
		check(VideoSettings.DYNAMIC_LIGHTS.v == 2 && VideoSettings.CLOUD_HEIGHT.v == 35 && VideoSettings.WATER_ANIMATED.v == 0
			&& VideoSettings.FOG.v == 2 && VideoSettings.ENTITY_DISTANCE.v == 500, "round trip");
		VideoSettings.save();
		check(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).equals(lines), "stable on resave");

		check(Hooks.autosave(900) == 900, "autosave default");
		check(Hooks.noParticle(30) == (VideoSettings.REDSTONE_PARTICLES.v == 0) && !Hooks.noParticle(9), "particle ids");
		System.out.println("VIDEO SETTINGS TEST OK");
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}
}
