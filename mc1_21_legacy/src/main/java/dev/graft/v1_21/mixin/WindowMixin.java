package dev.graft.v1_21.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.graft.v1_21.MetalGpuDevice;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** No OpenGL context on the window (Metal draws through a CAMetalLayer); vsync goes to the Metal surface. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V", remap = false))
	private void graft$noGlContext(int hint, int value) {
		GLFW.glfwWindowHint(hint, hint == GLFW.GLFW_CLIENT_API && !MetalGpuDevice.DISABLED ? GLFW.GLFW_NO_API : value);
	}

	@Redirect(method = "updateVsync", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V", remap = false))
	private void graft$vsync(int interval) {
		if (MetalGpuDevice.DISABLED) {
			GLFW.glfwSwapInterval(interval);
			return;
		}
		MetalGpuDevice.vsync = interval != 0;
	}
}
