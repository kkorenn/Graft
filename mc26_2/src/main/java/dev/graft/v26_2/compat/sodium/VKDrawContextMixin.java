package dev.graft.v26_2.compat.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.graft.v26_2.MetalRenderPassBackend;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext;
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium's per-region data goes through vkCmdPushConstants on Mojang's Vulkan internals; route it to Metal push constants. */
@Mixin(value = VKDrawContext.class, remap = false)
public abstract class VKDrawContextMixin extends DrawContext {
	@Inject(method = "setContext", at = @At("HEAD"), cancellable = true)
	private void graft$setContext(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
		if (((RenderPassAccessor)pass).getBackend() instanceof MetalRenderPassBackend) {
			this.pass = pass;
			ci.cancel();
		}
	}

	@Redirect(method = "updateData", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK13;nvkCmdPushConstants(Lorg/lwjgl/vulkan/VkCommandBuffer;JIIIJ)V"))
	private void graft$pushConstants(VkCommandBuffer cmd, long layout, int stages, int offset, int size, long address) {
		if (((RenderPassAccessor)this.pass).getBackend() instanceof MetalRenderPassBackend metal) {
			metal.pushConstants(address, size);
		} else {
			VK13.nvkCmdPushConstants(cmd, layout, stages, offset, size, address);
		}
	}
}
