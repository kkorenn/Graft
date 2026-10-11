package dev.graft.v1_21_11.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.graft.metal.MetalConfig;
import dev.graft.v1_21_11.MetalGpuDevice;
import dev.graft.v1_21_11.MetalTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render scale: the world (everything in renderLevel, shader packs included) renders into a smaller target that
 * temporarily stands in for Minecraft's main target, then MetalFX upscales it into the real one. The GUI is drawn
 * afterwards at full resolution, so text and HUD stay sharp.
 */
@Mixin(GameRenderer.class)
public abstract class RenderScaleMixin {
	@Unique
	private RenderTarget graft$full;
	@Unique
	private TextureTarget graft$scaled;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void graft$scaleDown(DeltaTracker deltaTracker, CallbackInfo ci) {
		int percent = MetalConfig.renderScale;
		if (percent >= 100 || graft$full != null || MetalGpuDevice.current() == null || !MetalGpuDevice.current().commands().metalFxAvailable()) {
			return;
		}
		MainTargetAccessor minecraft = (MainTargetAccessor)Minecraft.getInstance();
		RenderTarget full = minecraft.graft$getMainRenderTarget();
		int w = Math.max(1, full.width * percent / 100);
		int h = Math.max(1, full.height * percent / 100);
		if (graft$scaled == null) {
			graft$scaled = new TextureTarget("Graft scaled world", w, h, true);
		} else if (graft$scaled.width != w || graft$scaled.height != h) {
			graft$scaled.resize(w, h);
		}
		// Same clear as Minecraft does for the main target each frame (1.21.x: standard depth, 1.0 = far).
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
			graft$scaled.getColorTexture(), 0, graft$scaled.getDepthTexture(), 1.0
		);
		graft$full = full;
		minecraft.graft$setMainRenderTarget(graft$scaled);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void graft$upscale(DeltaTracker deltaTracker, CallbackInfo ci) {
		RenderTarget full = graft$full;
		if (full == null) {
			return;
		}
		graft$full = null;
		((MainTargetAccessor)Minecraft.getInstance()).graft$setMainRenderTarget(full);
		MetalTexture src = (MetalTexture)graft$scaled.getColorTexture();
		MetalTexture dst = (MetalTexture)full.getColorTexture();
		MetalGpuDevice.current().commands().upscale(
			src.mtl(), graft$scaled.width, graft$scaled.height, src.pixelFormat(), dst.mtl(), full.width, full.height, dst.pixelFormat()
		);
	}
}
