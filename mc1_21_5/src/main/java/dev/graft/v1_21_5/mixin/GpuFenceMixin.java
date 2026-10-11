package dev.graft.v1_21_5.mixin;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.opengl.GlStateManager;
import dev.graft.v1_21_5.MetalGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 1.21.5's GpuFence is a GL sync object: on Metal it waits for the command buffers submitted so far instead. */
@Mixin(GpuFence.class)
public abstract class GpuFenceMixin {
	@Unique
	private long graft$index;

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glFenceSync(II)J"))
	private long graft$fence(int condition, int flags) {
		if (MetalGpuDevice.DISABLED) {
			return GlStateManager._glFenceSync(condition, flags);
		}
		graft$index = MetalGpuDevice.current().fenceIndex();
		return 0L;
	}

	@Inject(method = "awaitCompletion", at = @At("HEAD"), cancellable = true)
	private void graft$await(long timeoutNs, CallbackInfoReturnable<Boolean> cir) {
		if (!MetalGpuDevice.DISABLED) {
			cir.setReturnValue(MetalGpuDevice.current().awaitFence(graft$index, timeoutNs));
		}
	}

	@Inject(method = "close", at = @At("HEAD"), cancellable = true)
	private void graft$close(CallbackInfo ci) {
		if (!MetalGpuDevice.DISABLED) {
			ci.cancel();
		}
	}
}
