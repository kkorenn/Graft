package dev.graft.v26_3.mixin;

import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Minecraft's MappableRingBuffer has 3 buffers. Sodium's indirect-draw ring rotates once per terrain pass, and with
 * shader packs that is several times a frame (shadow + main), so with 3 it reuses a buffer the GPU is still reading
 * and waits on its fence: CPU and GPU never overlap (75% of the render thread was spent in that wait). 16 buffers
 * cover several frames of passes. Every use of the constant (arrays, loop, wrap-around) changes together.
 */
@Mixin(MappableRingBuffer.class)
public abstract class RingBufferMixin {
	private static final int GRAFT_BUFFERS = 16;

	@ModifyConstant(method = {"<init>", "rotate", "close"}, constant = @Constant(intValue = 3), require = 1)
	private int graft$bufferCount(int original) {
		return GRAFT_BUFFERS;
	}
}
