package dev.graft.v26_2.compat.shine;

import com.mojang.blaze3d.systems.DeviceInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shine turns its effects off on backends it doesn't recognise. It only uses Blaze3D, so report Metal as Vulkan. */
@Pseudo
@Mixin(targets = "com.bloom.client.render.ShineRenderBackend", remap = false)
public class ShineRenderBackendMixin {
	@Redirect(method = "identify", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/DeviceInfo;backendName()Ljava/lang/String;"))
	private static String graft$metalAsVulkan(DeviceInfo info) {
		String name = info.backendName();
		return dev.graft.v26_2.MetalGpuDevice.BACKEND_NAME.equals(name) ? "Vulkan" : name;
	}
}
