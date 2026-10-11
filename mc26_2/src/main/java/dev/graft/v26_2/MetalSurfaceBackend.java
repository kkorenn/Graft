package dev.graft.v26_2;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.graft.metal.MetalSurface;
import dev.graft.metal.ObjC;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** CAMetalLayer installed on the GLFW window's content view (window created with GLFW_NO_API). */
public class MetalSurfaceBackend implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> MODES = EnumSet.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	private final MetalGpuDevice device;
	private final MetalSurface surface;

	MetalSurfaceBackend(MetalGpuDevice device, long glfwWindow) {
		this.device = device;
		long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
		long contentView = ObjC.send(nsWindow, ObjC.sel("contentView"));
		this.surface = MetalSurface.forNSView(device.metal, contentView, MetalSurface.backingScale(nsWindow));
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
		MetalTexture.View view = (MetalTexture.View)textureView;
		surface.blit(device.commands, view.mtl, view.getWidth(0), view.getHeight(0));
	}

	@Override
	public void present() {
		surface.present();
	}

	@Override
	public void close() {
		surface.close();
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return MODES;
	}
}
