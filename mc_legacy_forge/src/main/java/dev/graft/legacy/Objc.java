package dev.graft.legacy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * JNI bridge to libgraft (src/main/native/graft.m, universal arm64 + x86_64): Objective-C sends, Metal
 * calls that take structs, raw memory, and the window/input that replace LWJGL's Display.
 */
final class Objc {
	static {
		try (InputStream in = Objc.class.getResourceAsStream("/natives/libgraft.dylib")) {
			if (in == null) {
				throw new IllegalStateException("Graft: libgraft.dylib missing from the jar");
			}
			File f = File.createTempFile("libgraft", ".dylib");
			f.deleteOnExit();
			try (OutputStream out = new FileOutputStream(f)) {
				byte[] b = new byte[65536];
				for (int n; (n = in.read(b)) > 0; ) {
					out.write(b, 0, n);
				}
			}
			System.load(f.getAbsolutePath());
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Graft: could not load libgraft", e);
		}
	}

	private Objc() {
	}

	// ---- Objective-C runtime

	static native long objc_getClass(String name);

	static native long sel_registerName(String name);

	static native String object_getClassName(long obj);

	static native long objc_autoreleasePoolPush();

	static native void objc_autoreleasePoolPop(long pool);

	static native long objc_msgSend(long self, long sel);

	static native long objc_msgSend(long self, long sel, long a);

	static native long objc_msgSend(long self, long sel, long a, long b);

	static native long objc_msgSend(long self, long sel, long a, long b, long c);

	static native long objc_msgSend(long self, long sel, long a, long b, long c, long d);

	static native long objc_msgSend(long self, long sel, long a, long b, long c, long d, long e);

	static native long objc_msgSend(long self, long sel, long a, long b, long c, long d, long e, long f, long g, long h);

	static native long objc_msgSend(long self, long sel, String s);

	static native long objc_msgSend(long self, long sel, double a);

	static native long objc_msgSend(long self, long sel, float a, float b, float c);

	static native String utf8(long nsString);

	// ---- memory

	static native long address(Buffer directBuffer);

	static native ByteBuffer wrap(long address, long length);

	static native void memcpy(long dst, long src, long length);

	static ByteBuffer view(long address, long length) {
		return wrap(address, length).order(ByteOrder.nativeOrder());
	}

	// ---- Metal (struct arguments built natively)

	static native long createDevice();

	static native void viewport(long encoder, double x, double y, double w, double h);

	static native void scissor(long encoder, long x, long y, long w, long h);

	static native void clearColor(long attachment, double r, double g, double b, double a);

	static native void drawableSize(long layer, double w, double h);

	static native void bufToTex(long blit, long buf, long off, long bytesPerRow, long bytesPerImage, long w, long h, long tex, long level, long x, long y);

	static native void texToBuf(long blit, long tex, long level, long x, long y, long w, long h, long buf, long off, long bytesPerRow, long bytesPerImage);

	static native void texToTex(long blit, long src, long x, long y, long w, long h, long dst, long level, long dx, long dy);

	// ---- shaders

	/**
	 * GLSL 450 (Vulkan rules, MetalGlsl's binding scheme) -> MSL. Returns "ERR\n" + log, or "OK\n", then "U name offset size"
	 * lines for mc_Uniforms members, "E entryPoint", "%%MSL%%" and the MSL source.
	 */
	static native String glslToMsl(String source, boolean fragment);

	/** Preprocessor only (resolves #if/#define of a #version 1xx compatibility shader). "ERR\n" + log or "OK\n" + code. */
	static native String glslPreprocess(String source, boolean fragment);

	// ---- window and input

	static native void initAppKit();

	/** Creates the game window (on the AppKit thread) and returns its CAMetalLayer. */
	static native long winCreate(int w, int h, String title, boolean activate);

	static native void winDestroy();

	static native void winSetTitle(String title);

	static native void winSetResizable(boolean resizable);

	static native void winSetFullscreen(boolean fullscreen);

	/** {content width, content height, screen width, screen height} in points. */
	static native void winSize(int[] out);

	static native boolean winActive();

	static native boolean winCloseRequested();

	static native void deactivate();

	/** Drains queued input events, 8 ints each (see graft.m). Returns the count. */
	static native int pollEvents(int[] out);

	static native void mousePos(int[] out);

	static native void setCursorPosition(int x, int y);

	static native void setGrabbed(boolean grabbed);

	static native boolean mouseInside();

	// ---- helpers

	private static final Map<String, Long> SELS = new HashMap<String, Long>();

	static long sel(String name) {
		Long s = SELS.get(name);
		if (s == null) {
			SELS.put(name, s = sel_registerName(name));
		}
		return s;
	}

	static long cls(String name) {
		long c = objc_getClass(name);
		if (c == 0L) {
			throw new IllegalStateException("No Objective-C class " + name);
		}
		return c;
	}

	static long send(long self, String sel) {
		return objc_msgSend(self, sel(sel));
	}

	static long send(long self, String sel, long a) {
		return objc_msgSend(self, sel(sel), a);
	}

	static long send(long self, String sel, long a, long b) {
		return objc_msgSend(self, sel(sel), a, b);
	}

	static long nsString(String s) {
		return objc_msgSend(cls("NSString"), sel("stringWithUTF8String:"), s);
	}

	static String string(long nsString) {
		return utf8(nsString);
	}
}
