package dev.graft.v26_3.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.graft.v26_3.MetalGpuDevice;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * On Metal, runs vanilla's MappableRingBuffer rotation instead of whatever other mods injected into it: Lunar Client's
 * rotate hook only knows Mojang's GL/Vulkan backends and throws on any other ("Unexpected backend class"). The array
 * length is used instead of vanilla's 3, so it stays right with any buffer count (RingBufferMixin, or Lunar's own).
 */
@Mixin(MappableRingBuffer.class)
public abstract class RingBufferRotateMixin {
	@Shadow
	@Final
	private GpuBuffer[] buffers;
	@Shadow
	@Final
	private GpuFence[] fences;
	@Shadow
	private int current;

	@WrapMethod(method = "rotate")
	private void graft$rotate(Operation<Void> original) {
		if (MetalGpuDevice.current() == null) {
			original.call();
			return;
		}
		if (this.fences[this.current] != null) {
			this.fences[this.current].close();
		}
		this.fences[this.current] = RenderSystem.getDevice().createCommandEncoder().createFence();
		this.current = (this.current + 1) % this.fences.length;
	}

	@WrapMethod(method = "currentBuffer")
	private GpuBuffer graft$currentBuffer(Operation<GpuBuffer> original) {
		if (MetalGpuDevice.current() == null) {
			return original.call();
		}
		GpuFence fence = this.fences[this.current];
		if (fence != null) {
			fence.awaitCompletion(-1L);
			fence.close();
			this.fences[this.current] = null;
		}
		return this.buffers[this.current];
	}
}
