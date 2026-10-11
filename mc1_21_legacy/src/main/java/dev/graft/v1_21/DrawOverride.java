package dev.graft.v1_21;

/**
 * API for mods that draw with their own Metal pipelines inside a vanilla RenderPass (e.g. Graft Shaders shader packs).
 * Called right before each draw; return true after binding a pipeline and resources on {@code pass.metal()}, or false to
 * let the vanilla pipeline set via setPipeline() draw.
 */
@FunctionalInterface
public interface DrawOverride {
	boolean beforeDraw(MetalRenderPassBackend pass);

	/** Installed by at most one mod. */
	static void install(DrawOverride override) {
		MetalRenderPassBackend.drawOverride = override;
	}
}
