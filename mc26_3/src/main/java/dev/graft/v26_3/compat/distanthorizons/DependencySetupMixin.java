package dev.graft.v26_3.compat.distanthorizons;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH's "Rendering Engine" setting can be pinned to OpenGL, whose raw GL calls have no context on Metal; DH then refuses to
 * render. On Metal, read an OpenGL choice as Blaze3D, the engine that runs on any backend.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.DependencySetup", remap = false)
public class DependencySetupMixin {
	@ModifyExpressionValue(method = "setRenderingApiBindings", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/config/types/ConfigEntry;get()Ljava/lang/Object;"))
	private static Object graft$blazeOnMetal(Object engine) {
		if (engine instanceof Enum<?> e && e.name().equals("OPEN_GL") && dev.graft.v26_3.MetalGpuDevice.BACKEND_NAME.equals(RenderSystem.getDevice().getDeviceInfo().backendName())) {
			return graft$blaze(e);
		}
		return engine;
	}

	private static <E extends Enum<E>> E graft$blaze(Enum<?> e) {
		@SuppressWarnings("unchecked")
		Class<E> type = (Class<E>)e.getDeclaringClass();
		return Enum.valueOf(type, "BLAZE_3D");
	}
}
