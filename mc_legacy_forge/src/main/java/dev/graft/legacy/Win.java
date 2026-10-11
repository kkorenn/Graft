package dev.graft.legacy;

import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.PixelFormat;

import java.nio.ByteBuffer;

/**
 * org.lwjgl.opengl.Display, reimplemented on Graft's own window (a CAMetalLayer view, see graft.m). No OpenGL
 * context is ever created. Calls are redirected here by {@link GlTransformer}.
 */
public final class Win {
	/** Always false: guards the original LWJGL call sites kept for Lunar Client's mixins (see GlTransformer.LUNAR). */
	public static boolean lunarNever;

	private static final boolean BENCH = Boolean.getBoolean("graft.bench");
	private static final int[] size = new int[4], events = new int[8 * 1024];
	private static int width = 854, height = 480;
	private static boolean created, resized, fullscreen;
	private static String title = "Minecraft";
	private static DisplayMode mode;
	private static long nextFrame;

	public static void create() {
		if (created) {
			return;
		}
		Objc.initAppKit();
		MGL.setLayer(Objc.winCreate(width, height, title, !BENCH)); // dev benchmarks never take focus
		created = true;
		pump();
	}

	public static void create(PixelFormat format) {
		create();
	}

	public static void create(PixelFormat format, ContextAttribs attribs) {
		create();
	}

	public static void destroy() {
		if (created) {
			Objc.winDestroy();
			created = false;
		}
	}

	public static boolean isCreated() {
		return created;
	}

	public static void update() {
		MGL.present();
		pump();
	}

	public static void processMessages() {
		pump();
	}

	/** Moves native input events to {@link Keys}/{@link Mice} and picks up window size changes. */
	static void pump() {
		if (!created) {
			return;
		}
		pumpInput();
		Objc.winSize(size);
		resized = size[0] != width || size[1] != height;
		if (size[0] > 0 && size[1] > 0) {
			width = size[0];
			height = size[1];
		}
	}

	static void pumpInput() {
		if (!created) {
			return;
		}
		int n;
		while ((n = Objc.pollEvents(events)) > 0) {
			for (int i = 0; i < n; i++) {
				int o = i * 8;
				if (events[o] == 1) {
					Keys.event(events[o + 1], events[o + 2] != 0, (char)events[o + 3], events[o + 4] != 0);
				} else {
					Mice.event(events[o], events[o + 1], events[o + 2] != 0, events[o + 3], events[o + 4], events[o + 5], events[o + 6]);
				}
			}
			if (n < events.length / 8) {
				break;
			}
		}
	}

	public static int getWidth() {
		return width;
	}

	public static int getHeight() {
		return height;
	}

	public static boolean wasResized() {
		return resized;
	}

	public static boolean isActive() {
		return created && Objc.winActive();
	}

	public static boolean isVisible() {
		return created;
	}

	public static boolean isCloseRequested() {
		return created && Objc.winCloseRequested();
	}

	public static DisplayMode getDesktopDisplayMode() {
		Objc.winSize(size);
		return new DisplayMode(Math.max(1, size[2]), Math.max(1, size[3]));
	}

	public static DisplayMode[] getAvailableDisplayModes() {
		return new DisplayMode[] {getDesktopDisplayMode()};
	}

	public static DisplayMode getDisplayMode() {
		return mode != null ? mode : new DisplayMode(width, height);
	}

	public static void setDisplayMode(DisplayMode m) {
		mode = m;
		if (!created) {
			width = m.getWidth();
			height = m.getHeight();
		}
	}

	public static void setFullscreen(boolean f) {
		fullscreen = f;
		if (created) {
			Objc.winSetFullscreen(f);
		}
	}

	public static boolean isFullscreen() {
		return fullscreen;
	}

	public static void setResizable(boolean r) {
		if (created) {
			Objc.winSetResizable(r);
		}
	}

	public static void setTitle(String t) {
		title = t;
		if (created) {
			Objc.winSetTitle(t);
		}
	}

	public static int setIcon(ByteBuffer[] icons) {
		return 0;
	}

	public static void setVSyncEnabled(boolean enabled) {
		MGL.setVSync(enabled);
	}

	/** Display.sync: sleeps so frames are at most 1/fps apart. */
	public static void sync(int fps) {
		if (fps <= 0) {
			return;
		}
		long frame = 1_000_000_000L / fps, now = System.nanoTime();
		if (nextFrame == 0 || now - nextFrame > frame) {
			nextFrame = now;
		}
		while (nextFrame - System.nanoTime() > 1_500_000L) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				break;
			}
		}
		while (nextFrame - System.nanoTime() > 0) {
			Thread.yield();
		}
		nextFrame += frame;
	}

	/** Display.getDrawable: there is no GL drawable (only Forge's splash screen asks, and it is kept off). */
	public static Drawable getDrawable() {
		return null;
	}

	/** SplashProgress reads its "enabled" setting through this: the splash renders with GL on its own thread. */
	public static boolean splashBool(String name, boolean def) {
		return !name.equals("enabled") && def;
	}

	private Win() {
	}
}
