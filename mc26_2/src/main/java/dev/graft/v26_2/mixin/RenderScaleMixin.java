package dev.graft.v26_2.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.graft.metal.MetalConfig;
import dev.graft.v26_2.MetalGpuDevice;
import dev.graft.v26_2.MetalTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render scale: the world (everything in renderLevel, shader packs included) renders into a smaller target that
 * temporarily stands in for the main target, then MetalFX upscales it into the real one. The GUI is drawn afterwards
 * at full resolution, so text and HUD stay sharp.
 */
@Mixin(GameRenderer.class)
public abstract class RenderScaleMixin {
	@Shadow
	@Final
	@Mutable
	private RenderTarget mainRenderTarget;

	@Unique
	private RenderTarget graft$full;
	@Unique
	private TextureTarget graft$scaled;


	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void graft$scaleDown(DeltaTracker deltaTracker, CallbackInfo ci) {
		int percent = MetalConfig.renderScale;
		if (percent >= 100 || graft$full != null || !MetalGpuDevice.current().commands().metalFxAvailable()) {
			return;
		}
		RenderTarget full = mainRenderTarget;
		int w = Math.max(1, full.width * percent / 100);
		int h = Math.max(1, full.height * percent / 100);
		if (graft$scaled == null) {
			graft$scaled = new TextureTarget("Graft scaled world", w, h, true, full.getColorTexture().getFormat());
		} else if (graft$scaled.width != w || graft$scaled.height != h) {
			graft$scaled.resize(w, h);
		}
		// Same clear as GameRenderer.render does for the main target (reverse-Z: depth 0).
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
			graft$scaled.getColorTexture(), new Vector4f(0, 0, 0, 1), graft$scaled.getDepthTexture(), 0.0
		);
		graft$full = full;
		mainRenderTarget = graft$scaled;
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void graft$upscale(DeltaTracker deltaTracker, CallbackInfo ci) {
		RenderTarget full = graft$full;
		if (full == null) {
			return;
		}
		graft$full = null;
		mainRenderTarget = full;
		MetalTexture src = (MetalTexture)graft$scaled.getColorTexture();
		MetalTexture dst = (MetalTexture)full.getColorTexture();
		MetalGpuDevice.current().commands().upscale(
			src.mtl(), graft$scaled.width, graft$scaled.height, src.pixelFormat(), dst.mtl(), full.width, full.height, dst.pixelFormat()
		);
	}
}
