package dev.graft.v26_2;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.minecraft.client.PreferredGraphicsApi;
import org.lwjgl.glfw.GLFW;

public class MetalBackend implements GpuBackend {
	/** The "Metal" entry added to the game's graphics API option (see PreferredGraphicsApiMixin). */
	public static PreferredGraphicsApi OPTION;

	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
		if (Boolean.getBoolean("graft.noFocus")) { // dev: test runs open unfocused, behind other windows
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		}
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
		String detail = error != null ? String.format("GLFW_ERROR: 0x%X", error.error()) : "Failed to create window for Metal";
		throw new BackendCreationException(detail, BackendCreationException.Reason.GLFW_ERROR);
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions, Runnable criticalShaderLoader)
		throws BackendCreationException {
		try {
			return new GpuDevice(new MetalGpuDevice(defaultShaderSource), criticalShaderLoader);
		} catch (RuntimeException | LinkageError e) {
			throw new BackendCreationException("Metal is not available: " + e, BackendCreationException.Reason.OTHER);
		}
	}
}
