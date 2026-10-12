package dev.graft.legacy.shaders;

import dev.graft.legacy.MetalGlsl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack program -> MSL through Graft's translator, with OptiFine's attribute slots and a tolerance OptiFine does not
 * have: a shader that uses a standard uniform without declaring it (packs tested on newer versions, where some are
 * implicit) gets the declaration added and is compiled again.
 */
public final class PackCompiler {
	/** Generic attribute slots of the extra vertex attributes (same as OptiFine's, which MGL's terrain path follows). */
	public static final Map<String, Integer> ATTRIBUTES = new HashMap<String, Integer>();
	/** Standard uniforms that may be declared on the pack's behalf: name -> GLSL declaration or #define. */
	private static final Map<String, String> IMPLICIT = new HashMap<String, String>();
	private static final Pattern UNDECLARED = Pattern.compile("'(\\w+)' : undeclared identifier");

	static {
		ATTRIBUTES.put("mc_Entity", 10);
		ATTRIBUTES.put("mc_midTexCoord", 11);
		ATTRIBUTES.put("at_tangent", 12);
		String[] decls = {"vec3 fogColor", "vec3 skyColor", "float fogStart", "float fogEnd", "float fogDensity", "int fogMode", "int fogShape",
			"float rainStrength", "float wetness", "float frameTimeCounter", "float frameTime", "int frameCounter", "int worldTime", "int worldDay",
			"int moonPhase", "float sunAngle", "float shadowAngle", "float viewWidth", "float viewHeight", "float aspectRatio", "float near",
			"float far", "vec3 sunPosition", "vec3 moonPosition", "vec3 shadowLightPosition", "vec3 upPosition", "vec3 cameraPosition",
			"vec3 previousCameraPosition", "mat4 gbufferModelView", "mat4 gbufferModelViewInverse", "mat4 gbufferProjection",
			"mat4 gbufferProjectionInverse", "mat4 gbufferPreviousModelView", "mat4 gbufferPreviousProjection", "mat4 shadowProjection",
			"mat4 shadowProjectionInverse", "mat4 shadowModelView", "mat4 shadowModelViewInverse", "float eyeAltitude", "ivec2 eyeBrightness",
			"ivec2 eyeBrightnessSmooth", "int isEyeInWater", "float nightVision", "float blindness", "float screenBrightness", "int hideGUI",
			"float centerDepthSmooth", "ivec2 atlasSize", "vec4 entityColor", "int entityId", "int blockEntityId", "int heldItemId",
			"int heldBlockLightValue", "int heldItemId2", "int heldBlockLightValue2", "int renderStage", "float playerMood", "int bossBattle",
			"float darknessFactor", "float darknessLightFactor", "float alphaTestRef", "vec4 colorModulator"};
		for (String d : decls) {
			IMPLICIT.put(d.substring(d.indexOf(' ') + 1), "uniform " + d + ";");
		}
		String[][] aliases = {{"modelViewMatrix", "gl_ModelViewMatrix"}, {"projectionMatrix", "gl_ProjectionMatrix"},
			{"modelViewMatrixInverse", "gl_ModelViewMatrixInverse"}, {"projectionMatrixInverse", "gl_ProjectionMatrixInverse"},
			{"normalMatrix", "gl_NormalMatrix"}, {"textureMatrix", "gl_TextureMatrix[0]"}};
		for (String[] a : aliases) {
			IMPLICIT.put(a[0], "#define " + a[0] + " " + a[1]);
		}
	}

	public static MetalGlsl.Linked translate(ProgramSet.Source s, File cacheDir) {
		String vs = s.vertex, fs = s.fragment;
		MetalGlsl.Linked l = null;
		for (int attempt = 0; attempt < 6; attempt++) {
			l = cacheDir != null ? MetalGlsl.linkCached(vs, fs, ATTRIBUTES, cacheDir) : MetalGlsl.link(vs, fs, ATTRIBUTES);
			if (l.error == null) {
				return l;
			}
			Matcher m = UNDECLARED.matcher(l.error);
			String decl = m.find() ? IMPLICIT.get(m.group(1)) : null;
			if (decl == null || vs.contains(decl)) {
				return l;
			}
			vs = afterVersion(vs, decl);
			fs = afterVersion(fs, decl);
		}
		return l;
	}

	private static String afterVersion(String src, String line) {
		int nl = src.indexOf('\n');
		return src.substring(0, nl + 1) + line + "\n" + src.substring(nl + 1);
	}

	private PackCompiler() {
	}
}
