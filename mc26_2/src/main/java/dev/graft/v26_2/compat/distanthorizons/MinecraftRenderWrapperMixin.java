package dev.graft.v26_2.compat.distanthorizons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Distant Horizons picks its renderer from the backend name: "Vulkan" gets the Blaze3D renderer, anything else gets raw
 * OpenGL calls, which have no context on Metal. The Blaze3D renderer runs on any backend, so take it on Metal too.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
public class MinecraftRenderWrapperMixin {
	@Redirect(method = "getMcRenderingApi", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"))
	private boolean graft$blazeOnMetal(String backendName, String expected) {
		return backendName.equalsIgnoreCase(expected) || dev.graft.v26_2.MetalGpuDevice.BACKEND_NAME.equals(backendName);
	}
}
