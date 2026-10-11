package dev.graft.v26_3;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.graft.metal.MetalSurface;
import org.lwjgl.sdl.SDLMetal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** CAMetalLayer from SDL's Metal view (window created with SDL_WINDOW_METAL). */
public class MetalSurfaceBackend implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> MODES = EnumSet.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	private final MetalGpuDevice device;
	private final long view;
	private final MetalSurface surface;

	MetalSurfaceBackend(MetalGpuDevice device, long sdlWindow) {
		this.device = device;
		this.view = SDLMetal.SDL_Metal_CreateView(sdlWindow);
		if (view == 0L) {
			throw new IllegalStateException("SDL_Metal_CreateView failed");
		}
		this.surface = new MetalSurface(device.metal, SDLMetal.SDL_Metal_GetLayer(view));
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		surface.configure(config.width(), config.height(), config.presentMode() != GpuSurface.PresentMode.IMMEDIATE);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (!surface.acquire()) {
			throw new SurfaceException("CAMetalLayer returned no drawable");
		}
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		MetalTexture.View v = (MetalTexture.View)textureView;
		surface.blit(device.commands, v.mtl, v.getWidth(0), v.getHeight(0));
	}

	@Override
	public void present() {
		surface.present();
	}

	@Override
	public void close() {
		surface.close();
		SDLMetal.SDL_Metal_DestroyView(view);
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return MODES;
	}
}
