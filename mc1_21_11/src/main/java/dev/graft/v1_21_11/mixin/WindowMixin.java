package dev.graft.v1_21_11.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.graft.v1_21_11.MetalGpuDevice;
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

	/** Dev: -Dgraft.noFocus=true opens the window unfocused, behind other windows (test runs don't steal focus). */
	@org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private void graft$noFocus(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (Boolean.getBoolean("graft.noFocus")) {
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		}
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
