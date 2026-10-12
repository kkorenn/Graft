package dev.graft.legacy.shaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Values of the standard uniforms (OptiFine's documented set, as far as 1.8.9 has them) and the pack's custom
 * uniforms/variables, computed once per frame into stable arrays that programs copy from when first bound in a frame.
 */
final class Uniforms {
	final Map<String, double[]> values = new HashMap<String, double[]>();
	final float[] modelView = Mat.identity(new float[16]), projection = Mat.identity(new float[16]), shadowModelView = Mat.identity(new float[16]),
		shadowProjection = Mat.identity(new float[16]);
	private final float[] prevModelView = Mat.identity(new float[16]), prevProjection = Mat.identity(new float[16]), tmp = new float[16],
		vec = new float[3];
	private final List<Object[]> custom = new ArrayList<Object[]>(); // {name, Expr, isUniform}
	private long start = System.nanoTime(), last;
	private int frameCounter;
	private double prevX, prevY, prevZ;
	private double wetness, eyeBlock, eyeSky, centerSmooth = 1;
	/** Raw depth at the screen centre, read back at the end of the previous frame. */
	float centerDepth = 1;
	private boolean first = true;
	float frameTime, sunPathRotation, shadowAngle;

	Uniforms(PackConfig config) {
		for (String[] u : config.customUniforms) {
			try {
				custom.add(new Object[] {u[2], Expr.parse(u[3]), u[0].equals("uniform")});
			} catch (RuntimeException e) {
				ShaderPipeline.log("custom " + u[0] + " " + u[2] + ": " + e.getMessage());
			}
		}
	}

	double[] get(String name) {
		return values.get(name);
	}

	private double[] v(String name, int n) {
		double[] a = values.get(name);
		if (a == null || a.length != n) {
			values.put(name, a = new double[n]);
		}
		return a;
	}

	private void set(String name, double x) {
		v(name, 1)[0] = x;
	}

	private void set(String name, double x, double y) {
		double[] a = v(name, 2);
		a[0] = x;
		a[1] = y;
	}

	private void set(String name, double x, double y, double z) {
		double[] a = v(name, 3);
		a[0] = x;
		a[1] = y;
		a[2] = z;
	}

	private void mat(String name, float[] m) {
		double[] a = v(name, 16);
		for (int i = 0; i < 16; i++) {
			a[i] = m[i];
		}
	}

	private void matAndInverse(String name, float[] m) {
		mat(name, m);
		mat(name + "Inverse", Mat.invert(m, tmp) ? tmp : Mat.identity(tmp));
	}

	/** Called at the start of the frame's world rendering, after the camera matrices were captured. */
	void update(FrameState f, ProgramSet set, int width, int height) {
		long now = System.nanoTime();
		frameTime = first ? 0 : Math.min(1f, (now - last) / 1e9f);
		last = now;
		frameCounter = (frameCounter + 1) % 720720;
		set("frameCounter", frameCounter);
		set("frameTime", frameTime);
		set("frameTimeCounter", ((now - start) / 1e9) % 3600);
		set("worldTime", f.worldTime % 24000);
		set("worldDay", f.worldTime / 24000);
		set("moonPhase", f.moonPhase);
		set("rainStrength", f.rainStrength);
		set("viewWidth", width);
		set("viewHeight", height);
		set("aspectRatio", width / (double)Math.max(1, height));
		set("near", 0.05);
		set("far", f.renderDistanceChunks * 16);
		set("cameraPosition", f.cameraX, f.cameraY, f.cameraZ);
		set("previousCameraPosition", first ? f.cameraX : prevX, first ? f.cameraY : prevY, first ? f.cameraZ : prevZ);
		prevX = f.cameraX;
		prevY = f.cameraY;
		prevZ = f.cameraZ;
		set("eyeAltitude", f.eyeAltitude);
		set("isEyeInWater", f.isEyeInWater);
		set("nightVision", f.nightVision);
		set("blindness", f.blindness);
		set("screenBrightness", f.screenBrightness);
		set("hideGUI", f.hideGUI ? 1 : 0);
		set("heldItemId", f.heldItemId);
		set("heldBlockLightValue", f.heldBlockLight);
		set("heldItemId2", f.heldItemId2);
		set("heldBlockLightValue2", f.heldBlockLight2);
		set("bossBattle", f.bossBattle);
		set("cameraPositionInt", Math.floor(f.cameraX), Math.floor(f.cameraY), Math.floor(f.cameraZ));
		set("cameraPositionFract", f.cameraX - Math.floor(f.cameraX), f.cameraY - Math.floor(f.cameraY), f.cameraZ - Math.floor(f.cameraZ));
		set("fogColor", f.fogColor[0], f.fogColor[1], f.fogColor[2]);
		set("skyColor", f.skyColor[0], f.skyColor[1], f.skyColor[2]);
		set("eyeBrightness", f.eyeBrightnessBlock * 16, f.eyeBrightnessSky * 16);
		float eyeHl = set.settingFloat("eyeBrightnessHalflife", 10) / 20f;
		double k = first ? 1 : 1 - Math.pow(0.5, frameTime / Math.max(1e-3, eyeHl));
		eyeBlock += (f.eyeBrightnessBlock * 16 - eyeBlock) * k;
		eyeSky += (f.eyeBrightnessSky * 16 - eyeSky) * k;
		set("eyeBrightnessSmooth", Math.round(eyeBlock), Math.round(eyeSky));
		float hl = (f.rainStrength > wetness ? set.settingFloat("wetnessHalflife", 600) : set.settingFloat("drynessHalflife", 200)) / 20f;
		wetness += (f.rainStrength - wetness) * (first ? 1 : 1 - Math.pow(0.5, frameTime / Math.max(1e-3, hl)));
		set("wetness", wetness);
		float cdHl = set.settingFloat("centerDepthHalflife", 1) / 20f;
		centerSmooth += (centerDepth - centerSmooth) * (first ? 1 : 1 - Math.pow(0.5, frameTime / Math.max(1e-3, cdHl)));
		set("centerDepthSmooth", centerSmooth);
		set("playerMood", 0);
		set("renderStage", 0);
		set("entityId", -1);
		set("blockEntityId", -1);
		double[] ec = v("entityColor", 4);
		ec[0] = ec[1] = ec[2] = ec[3] = 0;
		set("atlasSize", f.atlasWidth, f.atlasHeight);
		set("terrainTextureSize", f.atlasWidth, f.atlasHeight);
		set("terrainIconSize", 16);

		// sun, moon, shadow light (eye space)
		sunPathRotation = set.settingFloat("sunPathRotation", 0);
		float sunAngle = f.celestialAngle < 0.75f ? f.celestialAngle + 0.25f : f.celestialAngle - 0.75f;
		shadowAngle = sunAngle <= 0.5f ? sunAngle : sunAngle - 0.5f;
		set("sunAngle", sunAngle);
		set("shadowAngle", shadowAngle);
		System.arraycopy(modelView, 0, tmp, 0, 16);
		Mat.rotate(tmp, -90, 0, 1, 0);
		Mat.transform(vec, tmp, 0, 100, 0, 0);
		set("upPosition", vec[0], vec[1], vec[2]);
		Mat.rotate(tmp, sunPathRotation, 0, 0, 1);
		Mat.rotate(tmp, f.celestialAngle * 360, 1, 0, 0);
		Mat.transform(vec, tmp, 0, 100, 0, 0);
		set("sunPosition", vec[0], vec[1], vec[2]);
		set("moonPosition", -vec[0], -vec[1], -vec[2]);
		boolean day = sunAngle <= 0.5f;
		set("shadowLightPosition", day ? vec[0] : -vec[0], day ? vec[1] : -vec[1], day ? vec[2] : -vec[2]);

		matAndInverse("gbufferModelView", modelView);
		matAndInverse("gbufferProjection", projection);
		mat("gbufferPreviousModelView", first ? modelView : prevModelView);
		mat("gbufferPreviousProjection", first ? projection : prevProjection);
		System.arraycopy(modelView, 0, prevModelView, 0, 16);
		System.arraycopy(projection, 0, prevProjection, 0, 16);
		first = false;
		updateCustom(f);
	}

	/** After the shadow matrices are known (shadow pass). */
	void updateShadow() {
		matAndInverse("shadowModelView", shadowModelView);
		matAndInverse("shadowProjection", shadowProjection);
	}

	private void updateCustom(final FrameState f) {
		if (custom.isEmpty()) {
			return;
		}
		Expr.Env env = new Expr.Env() {
			@Override
			public double[] get(String name) {
				double[] v = values.get(name);
				if (v != null) {
					return v;
				}
				switch (name) {
					case "biome": return new double[] {f.biome};
					case "temperature": return new double[] {f.temperature};
					case "rainfall": return new double[] {f.rainfall};
					case "biome_precipitation": return new double[] {f.rainfall <= 0 ? 0 : f.temperature < 0.15f ? 2 : 1};
					default: return null; // BIOME_*, CAT_*, is_* flags: 0 (ponytail: biome/entity-state constants not mapped yet)
				}
			}

			@Override
			public float frameTime() {
				return frameTime;
			}
		};
		for (Object[] c : custom) {
			double[] r = ((Expr)c[1]).eval(env);
			double[] dst = v((String)c[0], r.length);
			System.arraycopy(r, 0, dst, 0, r.length);
		}
	}
}
