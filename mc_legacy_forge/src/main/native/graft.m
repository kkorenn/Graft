// Graft 1.8.9 native bridge: Objective-C message sends, Metal struct calls, and the game window + input that
// replace LWJGL's OpenGL Display. Built universal (arm64 + x86_64) by the mc1_8_9 Gradle build.
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <objc/message.h>
#import <objc/runtime.h>
#include <jni.h>
#include <pthread.h>
#include <mach/mach_time.h>

// Not in public headers, but exported by libobjc (what @autoreleasepool compiles to).
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *pool);

#define FN(name) JNICALL Java_dev_graft_legacy_Objc_##name

static JavaVM *jvm;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	jvm = vm;
	return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------- runtime

JNIEXPORT jlong FN(objc_1getClass)(JNIEnv *env, jclass c, jstring name) {
	const char *s = (*env)->GetStringUTFChars(env, name, NULL);
	jlong r = (jlong)objc_getClass(s);
	(*env)->ReleaseStringUTFChars(env, name, s);
	return r;
}

JNIEXPORT jlong FN(sel_1registerName)(JNIEnv *env, jclass c, jstring name) {
	const char *s = (*env)->GetStringUTFChars(env, name, NULL);
	jlong r = (jlong)sel_registerName(s);
	(*env)->ReleaseStringUTFChars(env, name, s);
	return r;
}

JNIEXPORT jstring FN(object_1getClassName)(JNIEnv *env, jclass c, jlong obj) {
	return (*env)->NewStringUTF(env, object_getClassName((id)obj));
}

JNIEXPORT jlong FN(objc_1autoreleasePoolPush)(JNIEnv *env, jclass c) {
	return (jlong)objc_autoreleasePoolPush();
}

JNIEXPORT void FN(objc_1autoreleasePoolPop)(JNIEnv *env, jclass c, jlong p) {
	objc_autoreleasePoolPop((void *)p);
}

typedef long L;
#define MSG(...) ((L (*)(id, SEL, ##__VA_ARGS__))objc_msgSend)

JNIEXPORT jlong FN(objc_1msgSend__JJ)(JNIEnv *e, jclass c, jlong s, jlong sel) {
	return MSG()((id)s, (SEL)sel);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a) {
	return MSG(L)((id)s, (SEL)sel, a);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a, jlong b) {
	return MSG(L, L)((id)s, (SEL)sel, a, b);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a, jlong b, jlong d) {
	return MSG(L, L, L)((id)s, (SEL)sel, a, b, d);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJJJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a, jlong b, jlong d, jlong f) {
	return MSG(L, L, L, L)((id)s, (SEL)sel, a, b, d, f);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJJJJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a, jlong b, jlong d, jlong f, jlong g) {
	return MSG(L, L, L, L, L)((id)s, (SEL)sel, a, b, d, f, g);
}

JNIEXPORT jlong FN(objc_1msgSend__JJJJJJJJJJ)(JNIEnv *e, jclass c, jlong s, jlong sel, jlong a, jlong b, jlong d, jlong f, jlong g, jlong h, jlong i, jlong j) {
	return MSG(L, L, L, L, L, L, L, L)((id)s, (SEL)sel, a, b, d, f, g, h, i, j);
}

JNIEXPORT jlong FN(objc_1msgSend__JJLjava_lang_String_2)(JNIEnv *e, jclass c, jlong s, jlong sel, jstring str) {
	const char *u = (*e)->GetStringUTFChars(e, str, NULL);
	jlong r = MSG(const char *)((id)s, (SEL)sel, u);
	(*e)->ReleaseStringUTFChars(e, str, u);
	return r;
}

JNIEXPORT jlong FN(objc_1msgSend__JJD)(JNIEnv *e, jclass c, jlong s, jlong sel, jdouble a) {
	return MSG(double)((id)s, (SEL)sel, a);
}

JNIEXPORT jlong FN(objc_1msgSend__JJFFF)(JNIEnv *e, jclass c, jlong s, jlong sel, jfloat a, jfloat b, jfloat d) {
	return MSG(float, float, float)((id)s, (SEL)sel, a, b, d);
}

JNIEXPORT jstring FN(utf8)(JNIEnv *e, jclass c, jlong nsString) {
	return nsString ? (*e)->NewStringUTF(e, [(NSString *)nsString UTF8String]) : NULL;
}

// ---------------------------------------------------------------- memory

JNIEXPORT jlong FN(address)(JNIEnv *e, jclass c, jobject buf) {
	return (jlong)(*e)->GetDirectBufferAddress(e, buf);
}

JNIEXPORT jobject FN(wrap)(JNIEnv *e, jclass c, jlong addr, jlong len) {
	return (*e)->NewDirectByteBuffer(e, (void *)addr, len);
}

JNIEXPORT void FN(memcpy)(JNIEnv *e, jclass c, jlong dst, jlong src, jlong len) {
	memcpy((void *)dst, (const void *)src, (size_t)len);
}

// ---------------------------------------------------------------- Metal struct calls

JNIEXPORT jlong FN(createDevice)(JNIEnv *e, jclass c) {
	return (jlong)MTLCreateSystemDefaultDevice();
}

JNIEXPORT void FN(viewport)(JNIEnv *e, jclass c, jlong enc, jdouble x, jdouble y, jdouble w, jdouble h) {
	[(id<MTLRenderCommandEncoder>)enc setViewport:(MTLViewport){x, y, w, h, 0.0, 1.0}];
}

JNIEXPORT void FN(scissor)(JNIEnv *e, jclass c, jlong enc, jlong x, jlong y, jlong w, jlong h) {
	[(id<MTLRenderCommandEncoder>)enc setScissorRect:(MTLScissorRect){(NSUInteger)x, (NSUInteger)y, (NSUInteger)w, (NSUInteger)h}];
}

JNIEXPORT void FN(clearColor)(JNIEnv *e, jclass c, jlong att, jdouble r, jdouble g, jdouble b, jdouble a) {
	[(MTLRenderPassColorAttachmentDescriptor *)att setClearColor:MTLClearColorMake(r, g, b, a)];
}

JNIEXPORT void FN(drawableSize)(JNIEnv *e, jclass c, jlong layer, jdouble w, jdouble h) {
	((CAMetalLayer *)layer).drawableSize = CGSizeMake(w, h);
}

JNIEXPORT void FN(bufToTex)(JNIEnv *e, jclass c, jlong enc, jlong buf, jlong off, jlong bpr, jlong bpi, jlong w, jlong h, jlong tex, jlong level, jlong x, jlong y) {
	[(id<MTLBlitCommandEncoder>)enc copyFromBuffer:(id<MTLBuffer>)buf sourceOffset:off sourceBytesPerRow:bpr sourceBytesPerImage:bpi
		sourceSize:MTLSizeMake(w, h, 1) toTexture:(id<MTLTexture>)tex destinationSlice:0 destinationLevel:level destinationOrigin:MTLOriginMake(x, y, 0)];
}

JNIEXPORT void FN(texToBuf)(JNIEnv *e, jclass c, jlong enc, jlong tex, jlong level, jlong x, jlong y, jlong w, jlong h, jlong buf, jlong off, jlong bpr, jlong bpi) {
	[(id<MTLBlitCommandEncoder>)enc copyFromTexture:(id<MTLTexture>)tex sourceSlice:0 sourceLevel:level sourceOrigin:MTLOriginMake(x, y, 0)
		sourceSize:MTLSizeMake(w, h, 1) toBuffer:(id<MTLBuffer>)buf destinationOffset:off destinationBytesPerRow:bpr destinationBytesPerImage:bpi];
}

JNIEXPORT void FN(texToTex)(JNIEnv *e, jclass c, jlong enc, jlong src, jlong x, jlong y, jlong w, jlong h, jlong dst, jlong level, jlong dx, jlong dy) {
	[(id<MTLBlitCommandEncoder>)enc copyFromTexture:(id<MTLTexture>)src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(x, y, 0)
		sourceSize:MTLSizeMake(w, h, 1) toTexture:(id<MTLTexture>)dst destinationSlice:0 destinationLevel:level destinationOrigin:MTLOriginMake(dx, dy, 0)];
}

// ---------------------------------------------------------------- main thread

static void onMain(void (^block)(void)) {
	if ([NSThread isMainThread]) {
		block();
	} else {
		dispatch_sync(dispatch_get_main_queue(), block);
	}
}

// ---------------------------------------------------------------- window and input

// Event records handed to Java, 8 ints each: type, a, b, c, d, dx, dy, unused.
// type 1 key (a=lwjgl key, b=down, c=char, d=repeat), 2 button (a=button, b=down, c=x, d=y),
// 3 move (c=x, d=y, dx, dy), 4 wheel (a=dwheel, c=x, d=y)
#define QCAP 4096
static int queue[QCAP][8];
static int qhead, qtail;
static pthread_mutex_t qlock = PTHREAD_MUTEX_INITIALIZER;
static NSWindow *window;
static CAMetalLayer *metalLayer;
static volatile int closeRequested, grabbed, curX, curY, winW, winH, fullscreen;
static double wheelAcc, moveAccX, moveAccY;

static void push(int type, int a, int b, int c, int d, int dx, int dy) {
	pthread_mutex_lock(&qlock);
	int next = (qtail + 1) % QCAP;
	if (next != qhead) {
		int *r = queue[qtail];
		r[0] = type; r[1] = a; r[2] = b; r[3] = c; r[4] = d; r[5] = dx; r[6] = dy; r[7] = 0;
		qtail = next;
	}
	pthread_mutex_unlock(&qlock);
}

// macOS virtual key code -> LWJGL 2 Keyboard.KEY_*
static const unsigned char KEYMAP[128] = {
	[0x00] = 30, [0x01] = 31, [0x02] = 32, [0x03] = 33, [0x04] = 35, [0x05] = 34, [0x06] = 44, [0x07] = 45, [0x08] = 46, [0x09] = 47,
	[0x0B] = 48, [0x0C] = 16, [0x0D] = 17, [0x0E] = 18, [0x0F] = 19, [0x10] = 21, [0x11] = 20, [0x12] = 2, [0x13] = 3, [0x14] = 4,
	[0x15] = 5, [0x16] = 7, [0x17] = 6, [0x18] = 13, [0x19] = 10, [0x1A] = 8, [0x1B] = 12, [0x1C] = 9, [0x1D] = 11, [0x1E] = 27,
	[0x1F] = 24, [0x20] = 22, [0x21] = 26, [0x22] = 23, [0x23] = 25, [0x24] = 28, [0x25] = 38, [0x26] = 36, [0x27] = 40, [0x28] = 37,
	[0x29] = 39, [0x2A] = 43, [0x2B] = 51, [0x2C] = 53, [0x2D] = 49, [0x2E] = 50, [0x2F] = 52, [0x30] = 15, [0x31] = 57, [0x32] = 41,
	[0x33] = 14, [0x35] = 1, [0x36] = 220, [0x37] = 219, [0x38] = 42, [0x39] = 58, [0x3A] = 56, [0x3B] = 29, [0x3C] = 54, [0x3D] = 184,
	[0x3E] = 157, [0x41] = 83, [0x43] = 55, [0x45] = 78, [0x47] = 69, [0x4B] = 181, [0x4C] = 156, [0x4E] = 74, [0x51] = 141, [0x52] = 82,
	[0x53] = 79, [0x54] = 80, [0x55] = 81, [0x56] = 75, [0x57] = 76, [0x58] = 77, [0x59] = 71, [0x5B] = 72, [0x5C] = 73, [0x60] = 63,
	[0x61] = 64, [0x62] = 65, [0x63] = 61, [0x64] = 66, [0x65] = 67, [0x67] = 87, [0x69] = 100, [0x6B] = 101, [0x6D] = 68, [0x6F] = 88,
	[0x71] = 102, [0x72] = 210, [0x73] = 199, [0x74] = 201, [0x75] = 211, [0x76] = 62, [0x77] = 207, [0x78] = 60, [0x79] = 209, [0x7A] = 59,
	[0x7B] = 203, [0x7C] = 205, [0x7D] = 208, [0x7E] = 200,
};
static unsigned char modDown[128];

@interface MCView : NSView
@end

@implementation MCView
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)e { return YES; }
- (BOOL)wantsUpdateLayer { return YES; }
- (CALayer *)makeBackingLayer { return metalLayer; }

- (void)key:(NSEvent *)e down:(int)down {
	int code = KEYMAP[[e keyCode] & 127];
	unichar ch = 0;
	if (down) {
		NSString *s = [e characters];
		if ([s length] > 0) {
			ch = [s characterAtIndex:0];
			if (ch >= 0xF700 && ch <= 0xF8FF) ch = 0; // function-key private use area
			if (ch == 127) ch = 8; // backspace
			if (ch == 3) ch = 13; // keypad enter
		}
	}
	push(1, code, down, ch, down && [e isARepeat], 0, 0);
}
- (void)keyDown:(NSEvent *)e { [self key:e down:1]; }
- (void)keyUp:(NSEvent *)e { [self key:e down:0]; }
- (void)flagsChanged:(NSEvent *)e {
	int k = [e keyCode] & 127, code = KEYMAP[k];
	if (code == 0) return;
	modDown[k] = !modDown[k];
	push(1, code, modDown[k], 0, 0, 0, 0);
}

- (void)pos:(NSEvent *)e {
	NSPoint p = [self convertPoint:[e locationInWindow] fromView:nil];
	if (!grabbed) {
		curX = (int)p.x;
		curY = (int)p.y;
	}
}
- (void)button:(NSEvent *)e down:(int)down {
	[self pos:e];
	push(2, (int)[e buttonNumber], down, curX, curY, 0, 0);
}
- (void)moved:(NSEvent *)e {
	[self pos:e];
	// keep the fractional part (high-resolution mice and trackpads report sub-pixel deltas)
	moveAccX += [e deltaX];
	moveAccY -= [e deltaY];
	int dx = (int)moveAccX, dy = (int)moveAccY;
	moveAccX -= dx;
	moveAccY -= dy;
	push(3, 0, 0, curX, curY, dx, dy);
}
- (void)mouseDown:(NSEvent *)e { [self button:e down:1]; }
- (void)mouseUp:(NSEvent *)e { [self button:e down:0]; }
- (void)rightMouseDown:(NSEvent *)e { [self button:e down:1]; }
- (void)rightMouseUp:(NSEvent *)e { [self button:e down:0]; }
- (void)otherMouseDown:(NSEvent *)e { [self button:e down:1]; }
- (void)otherMouseUp:(NSEvent *)e { [self button:e down:0]; }
- (void)mouseMoved:(NSEvent *)e { [self moved:e]; }
- (void)mouseDragged:(NSEvent *)e { [self moved:e]; }
- (void)rightMouseDragged:(NSEvent *)e { [self moved:e]; }
- (void)otherMouseDragged:(NSEvent *)e { [self moved:e]; }
- (void)scrollWheel:(NSEvent *)e {
	[self pos:e];
	double d = [e hasPreciseScrollingDeltas] ? [e scrollingDeltaY] / 10.0 : [e scrollingDeltaY];
	wheelAcc += d;
	int steps = (int)wheelAcc;
	if (steps != 0) {
		wheelAcc -= steps;
		push(4, steps * 120, 0, curX, curY, 0, 0);
	}
}
@end

@interface MCDelegate : NSObject <NSWindowDelegate>
@end

@implementation MCDelegate
- (BOOL)windowShouldClose:(NSWindow *)w { closeRequested = 1; return NO; }
- (void)windowDidResize:(NSNotification *)n {
	NSSize s = [[window contentView] frame].size;
	winW = (int)s.width;
	winH = (int)s.height;
}
- (void)windowDidEnterFullScreen:(NSNotification *)n { fullscreen = 1; }
- (void)windowDidExitFullScreen:(NSNotification *)n { fullscreen = 0; }
- (void)windowDidResignKey:(NSNotification *)n {
	memset(modDown, 0, sizeof modDown);
}
@end

static MCDelegate *delegate;

JNIEXPORT jlong FN(winCreate)(JNIEnv *env, jclass c, jint w, jint h, jstring title, jboolean activate) {
	const char *t = (*env)->GetStringUTFChars(env, title, NULL);
	NSString *nsTitle = [NSString stringWithUTF8String:t];
	(*env)->ReleaseStringUTFChars(env, title, t);
	__block jlong result = 0;
	onMain(^{
		[NSApplication sharedApplication];
		[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
		CGEventSourceRef source = CGEventSourceCreate(kCGEventSourceStateCombinedSessionState);
		if (source) { // no input suppression after cursor warps (GUI open/close)
			CGEventSourceSetLocalEventsSuppressionInterval(source, 0.0);
			CFRelease(source);
		}
		metalLayer = [[CAMetalLayer layer] retain];
		window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, w, h)
			styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable
			backing:NSBackingStoreBuffered defer:NO];
		MCView *view = [[MCView alloc] initWithFrame:NSMakeRect(0, 0, w, h)];
		[view setWantsLayer:YES];
		[window setContentView:view];
		[window setTitle:nsTitle];
		[window setAcceptsMouseMovedEvents:YES];
		[window setCollectionBehavior:NSWindowCollectionBehaviorFullScreenPrimary];
		delegate = [[MCDelegate alloc] init];
		[window setDelegate:delegate];
		[window center];
		if (activate) {
			[window makeKeyAndOrderFront:nil];
			[NSApp activateIgnoringOtherApps:YES];
		} else {
			[window orderFront:nil];
		}
		[window makeFirstResponder:view];
		winW = w;
		winH = h;
		result = (jlong)metalLayer;
	});
	return result;
}

JNIEXPORT void FN(winDestroy)(JNIEnv *e, jclass c) {
	onMain(^{ [window orderOut:nil]; });
}

JNIEXPORT void FN(winSetTitle)(JNIEnv *env, jclass c, jstring title) {
	const char *t = (*env)->GetStringUTFChars(env, title, NULL);
	NSString *s = [NSString stringWithUTF8String:t];
	(*env)->ReleaseStringUTFChars(env, title, t);
	dispatch_async(dispatch_get_main_queue(), ^{ [window setTitle:s]; });
}

JNIEXPORT void FN(winSetResizable)(JNIEnv *e, jclass c, jboolean r) {
	dispatch_async(dispatch_get_main_queue(), ^{
		NSWindowStyleMask m = [window styleMask];
		[window setStyleMask:r ? m | NSWindowStyleMaskResizable : m & ~NSWindowStyleMaskResizable];
	});
}

JNIEXPORT void FN(winSetFullscreen)(JNIEnv *e, jclass c, jboolean f) {
	dispatch_async(dispatch_get_main_queue(), ^{
		if ((([window styleMask] & NSWindowStyleMaskFullScreen) != 0) != (f != 0)) [window toggleFullScreen:nil];
	});
}

JNIEXPORT void FN(winSize)(JNIEnv *e, jclass c, jintArray out) {
	jint v[4] = {winW, winH, (jint)[[NSScreen mainScreen] frame].size.width, (jint)[[NSScreen mainScreen] frame].size.height};
	(*e)->SetIntArrayRegion(e, out, 0, 4, v);
}

JNIEXPORT jboolean FN(winActive)(JNIEnv *e, jclass c) {
	return [window isKeyWindow] && [NSApp isActive];
}

JNIEXPORT jboolean FN(winCloseRequested)(JNIEnv *e, jclass c) {
	return closeRequested;
}

JNIEXPORT void FN(deactivate)(JNIEnv *e, jclass c) {
	dispatch_async(dispatch_get_main_queue(), ^{ [NSApp deactivate]; });
}

JNIEXPORT jint FN(pollEvents)(JNIEnv *e, jclass c, jintArray out) {
	jint max = (*e)->GetArrayLength(e, out) / 8, n = 0;
	pthread_mutex_lock(&qlock);
	while (qhead != qtail && n < max) {
		(*e)->SetIntArrayRegion(e, out, n * 8, 8, queue[qhead]);
		qhead = (qhead + 1) % QCAP;
		n++;
	}
	pthread_mutex_unlock(&qlock);
	return n;
}

JNIEXPORT void FN(mousePos)(JNIEnv *e, jclass c, jintArray out) {
	jint v[2] = {curX, curY};
	(*e)->SetIntArrayRegion(e, out, 0, 2, v);
}

static void warpTo(int x, int y) {
	NSRect r = [window convertRectToScreen:NSMakeRect(x, y, 0, 0)];
	CGFloat screenH = [[[NSScreen screens] objectAtIndex:0] frame].size.height;
	CGWarpMouseCursorPosition(CGPointMake(r.origin.x, screenH - r.origin.y));
	// re-associating right after the warp avoids macOS freezing real mouse input for ~0.25 s
	CGAssociateMouseAndMouseCursorPosition(!grabbed);
	curX = x;
	curY = y;
}

JNIEXPORT void FN(setCursorPosition)(JNIEnv *e, jclass c, jint x, jint y) {
	onMain(^{ warpTo(x, y); });
}

JNIEXPORT void FN(setGrabbed)(JNIEnv *e, jclass c, jboolean g) {
	onMain(^{
		if ((g != 0) == (grabbed != 0)) return;
		grabbed = g;
		if (g) {
			[NSCursor hide];
			warpTo(winW / 2, winH / 2);
			CGAssociateMouseAndMouseCursorPosition(false);
		} else {
			CGAssociateMouseAndMouseCursorPosition(true);
			warpTo(winW / 2, winH / 2);
			[NSCursor unhide];
		}
	});
}

JNIEXPORT jboolean FN(mouseInside)(JNIEnv *e, jclass c) {
	NSPoint p = [window mouseLocationOutsideOfEventStream];
	return p.x >= 0 && p.y >= 0 && p.x < winW && p.y < winH;
}

JNIEXPORT void FN(initAppKit)(JNIEnv *e, jclass c) {
	// AWT starts the AppKit event loop on the process's first thread (as LWJGL 2 relies on); our window lives on it.
	jclass tk = (*e)->FindClass(e, "java/awt/Toolkit");
	jmethodID get = (*e)->GetStaticMethodID(e, tk, "getDefaultToolkit", "()Ljava/awt/Toolkit;");
	(*e)->CallStaticObjectMethod(e, tk, get);
}
