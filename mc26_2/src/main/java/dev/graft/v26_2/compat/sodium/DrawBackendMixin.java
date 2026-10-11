package dev.graft.v26_2.compat.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import dev.graft.v26_2.compat.SodiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium sends anything that isn't Mojang's VulkanDevice down its raw-OpenGL path. Use its Blaze3D indirect path instead. */
@Mixin(value = DrawBackend.class, remap = false)
public class DrawBackendMixin {
	@Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true)
	private static void graft$indirectOnMetal(CallbackInfoReturnable<DrawBackend> cir) {
		if (SodiumCompat.onMetal()) {
			cir.setReturnValue(DrawBackend.VK_INDIRECT);
		}
	}
}
