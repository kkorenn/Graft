package dev.graft.v26_2.compat.sodium;

import com.mojang.blaze3d.systems.DeviceFeatures;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.MojangStagingBuffer;
import dev.graft.v26_2.compat.SodiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Sodium's persistent-mapping staging buffer flushes through GlDevice; use its generic writeToBuffer path on Metal. */
@Mixin(value = MojangStagingBuffer.class, remap = false)
public class MojangStagingBufferMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/DeviceFeatures;persistentMapping()Z"))
	private boolean graft$noGlMapping(DeviceFeatures features) {
		return features.persistentMapping() && !SodiumCompat.onMetal();
	}
}
