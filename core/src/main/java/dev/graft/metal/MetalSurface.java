package dev.graft.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static dev.graft.metal.ObjC.*;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A CAMetalLayer swapchain. Minecraft's frame is: acquire, blit (records into the frame's command buffer), submit, present.
 * Metal schedules the present on the command buffer, so it is attached during blit and present() only drops the drawable.
 */
public final class MetalSurface implements AutoCloseable {
	private static final SymbolLookup CG = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.global());
	private static final MethodHandle CS_CREATE = fn(CG, "CGColorSpaceCreateWithName", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
	private static final MethodHandle CS_RELEASE = fn(CG, "CGColorSpaceRelease", FunctionDescriptor.ofVoid(JAVA_LONG));
	private final MetalDevice device;
	private final long layer;
	private long drawable;
	private int width;
	private int height;
	/**
	 * -Dgraft.offscreen=true (benchmarks): frames are "presented" into a private texture instead of a window
	 * drawable. macOS throttles drawables of hidden/occluded windows, which would cap a background benchmark's frame rate.
	 */
	public static final boolean OFFSCREEN = Boolean.getBoolean("graft.offscreen");
	private long offscreen;
	private int offscreenWidth;
	private int offscreenHeight;

	static {
		if (OFFSCREEN) {
			// No App Nap / timer throttling for the (background) benchmark process: NSActivityUserInitiated | LatencyCritical.
			long info = send(cls("NSProcessInfo"), sel("processInfo"));
			retain(send(info, sel("beginActivityWithOptions:reason:"), 0x00FFFFFFL | 0xFF00000000L, nsString("Graft offscreen benchmark")));
		}
	}

	/** Wraps an existing CAMetalLayer (retained here). */
	public MetalSurface(MetalDevice device, long layer) {
		this.device = device;
		this.layer = retain(layer);
		send(layer, sel("setDevice:"), device.device);
		send(layer, sel("setPixelFormat:"), Mtl.PIXEL_BGRA8);
		send(layer, sel("setFramebufferOnly:"), 1L);
		send(layer, sel("setMaximumDrawableCount:"), 3L);
		setColorspace(layer, MetalConfig.displayP3 ? "kCGColorSpaceDisplayP3" : "kCGColorSpaceSRGB");
	}

	/** Without an explicit colorspace macOS shows the drawable in the display's native gamut, oversaturating sRGB content. */
	private static void setColorspace(long layer, String name) {
		try {
			long cfName = CG.find(name).orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0).address();
			long cs = (long)CS_CREATE.invokeExact(cfName);
			send(layer, sel("setColorspace:"), cs);
			CS_RELEASE.invokeExact(cs);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Creates a CAMetalLayer and installs it as the backing layer of an NSView. */
	public static MetalSurface forNSView(MetalDevice device, long nsView, double contentsScale) {
		long pool = poolPush();
		try {
			long layer = send(cls("CAMetalLayer"), sel("layer"));
			sendD(layer, sel("setContentsScale:"), contentsScale);
			send(nsView, sel("setLayer:"), layer);
			send(nsView, sel("setWantsLayer:"), 1L);
			return new MetalSurface(device, layer);
		} finally {
			poolPop(pool);
		}
	}

	public static double backingScale(long nsWindow) {
		return Calls.getDouble(nsWindow, "backingScaleFactor");
	}

	public void configure(int width, int height, boolean vsync) {
		this.width = width;
		this.height = height;
		Calls.setDrawableSize(layer, width, height);
		send(layer, sel("setDisplaySyncEnabled:"), vsync ? 1L : 0L);
	}

	/** Returns false if no drawable became available (window hidden or timeout). */
	public boolean acquire() {
		if (OFFSCREEN) {
			if (offscreen == 0L || offscreenWidth != width || offscreenHeight != height) {
				release(offscreen);
				offscreen = device.newTexture(Mtl.PIXEL_BGRA8, false, Math.max(1, width), Math.max(1, height), 1, 1);
				offscreenWidth = width;
				offscreenHeight = height;
			}
			return true;
		}
		long pool = poolPush();
		try {
			drawable = retain(send(layer, sel("nextDrawable")));
			return drawable != 0L;
		} finally {
			poolPop(pool);
		}
	}

	/** Copies the source texture into the drawable with a vertical flip and schedules presentation. */
	public void blit(MetalCommands commands, long sourceTexture, int sourceWidth, int sourceHeight) {
		if (OFFSCREEN) {
			draw(commands, offscreen, sourceTexture, sourceWidth, sourceHeight);
			return;
		}
		if (drawable == 0L) {
			throw new IllegalStateException("No drawable acquired");
		}
		draw(commands, send(drawable, sel("texture")), sourceTexture, sourceWidth, sourceHeight);
		send(commands.commandBuffer(), sel("presentDrawable:"), drawable);
	}

	private void draw(MetalCommands commands, long target, long sourceTexture, int sourceWidth, int sourceHeight) {
		int copyWidth = Math.min(width, sourceWidth);
		int copyHeight = Math.min(height, sourceHeight);
		MetalRenderPass.Attachment[] colors = {new MetalRenderPass.Attachment(target, Mtl.PIXEL_BGRA8, 0, new float[]{0, 0, 0, 1}, Double.NaN)};
		MetalRenderPass pass = commands.beginRenderPass(colors, null, 0, 0, width, height, "graft present");
		pass.drawInternalPresent(device.internalPipeline("mc_present", Mtl.PIXEL_BGRA8, Mtl.PIXEL_INVALID), sourceTexture, copyWidth, copyHeight);
		commands.endRenderPass();
	}

	public void present() {
		release(drawable);
		drawable = 0L;
	}

	@Override
	public void close() {
		present();
		release(offscreen);
		offscreen = 0L;
		release(layer);
	}
}
