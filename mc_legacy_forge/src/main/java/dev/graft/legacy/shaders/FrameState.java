package dev.graft.legacy.shaders;

/** What the version module reports about the game each frame (filled before {@link ShaderPipeline#beginWorld}). */
public final class FrameState {
	public float partialTicks;
	public double cameraX, cameraY, cameraZ;
	public long worldTime;
	public int dimension, moonPhase, renderDistanceChunks, isEyeInWater, eyeBrightnessBlock, eyeBrightnessSky, heldItemId = -1, heldBlockLight;
	public float celestialAngle, rainStrength, nightVision, blindness, screenBrightness = 0.5f, eyeAltitude;
	public float[] skyColor = new float[3], fogColor = new float[3];
	/** Off-hand (1.9+), blocks atlas size, boss bar (0 none, 1 other, 2 ender dragon, 3 wither). */
	public int heldItemId2 = -1, heldBlockLight2, atlasWidth, atlasHeight, bossBattle;
	public boolean hideGUI;
	/** Biome id, temperature and rainfall at the camera (custom uniforms). */
	public int biome;
	public float temperature, rainfall;
}
