package dev.graft.v26_3;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

public class MetalBackend implements GpuBackend {
	/** The "Metal" entry added to the game's graphics API option (see PreferredGraphicsApiMixin). */
	public static PreferredGraphicsApi OPTION;

	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() {
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		try {
			return new FrontendGpuDevice(new MetalGpuDevice());
		} catch (RuntimeException | LinkageError e) {
			throw new BackendCreationException("Metal is not available: " + e, BackendCreationException.Reason.OTHER);
		}
	}
}
