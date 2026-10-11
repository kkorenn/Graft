package dev.graft.v1_21_11.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.graft.v1_21_11.MetalGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Swaps only the device: GlDevice is never constructed (no GL context exists) and the Metal device is stored in its
 * place. The rest of initRenderer is vanilla's own, whatever each version does there (1.21.6-1.21.8 also build a quad
 * vertex buffer, 1.21.11 initializes the sampler cache).
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Unique
	private static long graft$window;
	@Unique
	private static ShaderSource graft$shaderSource;

	@WrapOperation(method = "initRenderer", at = @At(value = "NEW", target = "com/mojang/blaze3d/opengl/GlDevice"))
	private static GlDevice graft$skipGlDevice(long window, int debugVerbosity, boolean syncDebug, ShaderSource shaderSource, boolean debugLabels, Operation<GlDevice> original) {
		if (MetalGpuDevice.DISABLED) {
			return original.call(window, debugVerbosity, syncDebug, shaderSource, debugLabels);
		}
		graft$window = window;
		graft$shaderSource = shaderSource;
		return null;
	}

	@WrapOperation(method = "initRenderer", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/systems/RenderSystem;DEVICE:Lcom/mojang/blaze3d/systems/GpuDevice;", opcode = org.objectweb.asm.Opcodes.PUTSTATIC))
	private static void graft$storeMetalDevice(GpuDevice value, Operation<Void> original) {
		original.call(value != null ? value : new MetalGpuDevice(graft$window, graft$shaderSource));
	}

	@Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
	private static void graft$present(long window) {
		if (MetalGpuDevice.DISABLED) {
			org.lwjgl.glfw.GLFW.glfwSwapBuffers(window);
			return;
		}
		MetalGpuDevice.current().swap();
	}
}
