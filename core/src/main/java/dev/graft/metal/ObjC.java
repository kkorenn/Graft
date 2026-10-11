package dev.graft.metal;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * Minimal Objective-C runtime bridge over the Java FFM API. Every pointer/id/SEL/NSUInteger is a Java long.
 * objc_msgSend is called through a downcall handle whose descriptor matches the method's real signature
 * (on arm64 objc_msgSend must be called with the exact prototype; it is not variadic).
 */
public final class ObjC {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
	public static final SymbolLookup METAL = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global());
	// Loaded for its side effect: registers CAMetalLayer with the runtime.
	public static final SymbolLookup QUARTZ = SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", Arena.global());
	private static final MemorySegment MSG_SEND = OBJC.find("objc_msgSend").orElseThrow();

	private static final MethodHandle GET_CLASS = fn(OBJC, "objc_getClass", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
	private static final MethodHandle SEL_REGISTER = fn(OBJC, "sel_registerName", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
	private static final MethodHandle POOL_PUSH = fn(OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(JAVA_LONG));
	private static final MethodHandle POOL_POP = fn(OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(JAVA_LONG));

	private static final MethodHandle J0 = send(JAVA_LONG);
	private static final MethodHandle J1 = send(JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J2 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J3 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J4 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J5 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J6 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J7 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle J8 = send(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle D1 = sendVoid(JAVA_DOUBLE);
	private static final MethodHandle F3 = sendVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);

	private static final ConcurrentHashMap<String, Long> SELECTORS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Long> CLASSES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> CUSTOM = new ConcurrentHashMap<>();

	private ObjC() {
	}

	static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor desc) {
		return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), desc);
	}

	private static MethodHandle send(MemoryLayout ret, MemoryLayout... args) {
		MemoryLayout[] all = new MemoryLayout[args.length + 2];
		all[0] = JAVA_LONG;
		all[1] = JAVA_LONG;
		System.arraycopy(args, 0, all, 2, args.length);
		return LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.of(ret, all));
	}

	private static MethodHandle sendVoid(MemoryLayout... args) {
		MemoryLayout[] all = new MemoryLayout[args.length + 2];
		all[0] = JAVA_LONG;
		all[1] = JAVA_LONG;
		System.arraycopy(args, 0, all, 2, args.length);
		return LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(all));
	}

	/** objc_msgSend with an arbitrary descriptor (receiver and selector must be the first two JAVA_LONG params). */
	public static MethodHandle custom(FunctionDescriptor desc) {
		return CUSTOM.computeIfAbsent(desc, d -> LINKER.downcallHandle(MSG_SEND, d));
	}

	public static long sel(String name) {
		return SELECTORS.computeIfAbsent(name, n -> {
			try (Arena arena = Arena.ofConfined()) {
				return (long)SEL_REGISTER.invokeExact(arena.allocateFrom(n));
			} catch (Throwable t) {
				throw rethrow(t);
			}
		});
	}

	public static long cls(String name) {
		return CLASSES.computeIfAbsent(name, n -> {
			try (Arena arena = Arena.ofConfined()) {
				long c = (long)GET_CLASS.invokeExact(arena.allocateFrom(n));
				if (c == 0L) {
					throw new IllegalStateException("Objective-C class not found: " + n);
				}
				return c;
			} catch (Throwable t) {
				throw rethrow(t);
			}
		});
	}

	public static long send(long obj, long sel) {
		try {
			return (long)J0.invokeExact(obj, sel);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a) {
		try {
			return (long)J1.invokeExact(obj, sel, a);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b) {
		try {
			return (long)J2.invokeExact(obj, sel, a, b);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c) {
		try {
			return (long)J3.invokeExact(obj, sel, a, b, c);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c, long d) {
		try {
			return (long)J4.invokeExact(obj, sel, a, b, c, d);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c, long d, long e) {
		try {
			return (long)J5.invokeExact(obj, sel, a, b, c, d, e);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c, long d, long e, long f) {
		try {
			return (long)J6.invokeExact(obj, sel, a, b, c, d, e, f);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c, long d, long e, long f, long g, long h) {
		try {
			return (long)J8.invokeExact(obj, sel, a, b, c, d, e, f, g, h);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long send(long obj, long sel, long a, long b, long c, long d, long e, long f, long g) {
		try {
			return (long)J7.invokeExact(obj, sel, a, b, c, d, e, f, g);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void sendD(long obj, long sel, double a) {
		try {
			D1.invokeExact(obj, sel, a);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void sendF3(long obj, long sel, float a, float b, float c) {
		try {
			F3.invokeExact(obj, sel, a, b, c);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** BOOL results: only the low byte is defined. */
	public static boolean sendBool(long obj, long sel) {
		return (send(obj, sel) & 0xFF) != 0;
	}

	public static long alloc(String className) {
		return send(send(cls(className), sel("alloc")), sel("init"));
	}

	public static void release(long obj) {
		if (obj != 0L) {
			send(obj, sel("release"));
		}
	}

	public static long retain(long obj) {
		return obj == 0L ? 0L : send(obj, sel("retain"));
	}

	public static long poolPush() {
		try {
			return (long)POOL_PUSH.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void poolPop(long pool) {
		try {
			POOL_POP.invokeExact(pool);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Autoreleased NSString. */
	public static long nsString(String s) {
		try (Arena arena = Arena.ofConfined()) {
			return send(cls("NSString"), sel("stringWithUTF8String:"), arena.allocateFrom(s).address());
		}
	}

	public static String toJava(long nsString) {
		if (nsString == 0L) {
			return null;
		}
		long utf8 = send(nsString, sel("UTF8String"));
		return utf8 == 0L ? null : MemorySegment.ofAddress(utf8).reinterpret(Long.MAX_VALUE).getString(0);
	}

	public static String errorString(long nsError) {
		return nsError == 0L ? "unknown error" : toJava(send(nsError, sel("localizedDescription")));
	}

	public static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) {
			return r;
		}
		if (t instanceof Error e) {
			throw e;
		}
		return new RuntimeException(t);
	}
}
