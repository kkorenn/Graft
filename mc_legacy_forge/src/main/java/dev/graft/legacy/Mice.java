package dev.graft.legacy;

import java.util.ArrayDeque;

/** org.lwjgl.input.Mouse on Graft's window (events arrive through {@link Win#pump}). */
public final class Mice {
	private static final boolean[] buttons = new boolean[16];
	private static final ArrayDeque<long[]> queue = new ArrayDeque<long[]>();
	private static final int[] pos = new int[2];
	private static int x, y, dx, dy, dwheel;
	private static boolean grabbed;
	// button, state, x, y, dx, dy, dwheel, nanos
	private static long[] current = new long[8];

	static void event(int type, int a, boolean state, int ex, int ey, int edx, int edy) {
		int button = -1, wheel = 0;
		if (type == 2) {
			button = a;
			if (a >= 0 && a < buttons.length) {
				buttons[a] = state;
			}
		} else if (type == 4) {
			wheel = a;
			dwheel += a;
		}
		if (type == 3) {
			dx += edx;
			dy += edy;
		}
		if (!grabbed) {
			x = ex;
			y = ey;
		}
		queue.add(new long[] {button, state ? 1 : 0, x, y, type == 3 ? edx : 0, type == 3 ? edy : 0, wheel, System.nanoTime()});
	}

	public static void create() {
	}

	public static void destroy() {
	}

	public static boolean isCreated() {
		return Win.isCreated();
	}

	public static void poll() {
		Win.pump();
	}

	public static boolean next() {
		long[] e = queue.poll();
		if (e == null) {
			return false;
		}
		current = e;
		return true;
	}

	public static int getEventButton() {
		return (int)current[0];
	}

	public static boolean getEventButtonState() {
		return current[1] != 0;
	}

	public static int getEventX() {
		return (int)current[2];
	}

	public static int getEventY() {
		return (int)current[3];
	}

	public static int getEventDX() {
		return (int)current[4];
	}

	public static int getEventDY() {
		return (int)current[5];
	}

	public static int getEventDWheel() {
		return (int)current[6];
	}

	public static long getEventNanoseconds() {
		return current[7];
	}

	public static int getX() {
		return x;
	}

	public static int getY() {
		return y;
	}

	public static int getDX() {
		Win.pumpInput(); // freshest movement, not just what arrived before the last Display.update
		int r = dx;
		dx = 0;
		return r;
	}

	public static int getDY() {
		Win.pumpInput();
		int r = dy;
		dy = 0;
		return r;
	}

	public static int getDWheel() {
		int r = dwheel;
		dwheel = 0;
		return r;
	}

	public static boolean isButtonDown(int button) {
		return button >= 0 && button < buttons.length && buttons[button];
	}

	public static int getButtonCount() {
		return 3;
	}

	public static boolean isInsideWindow() {
		return Win.isCreated() && Objc.mouseInside();
	}

	public static void setGrabbed(boolean grab) {
		if (grab == grabbed || !Win.isCreated()) {
			grabbed = grab;
			return;
		}
		grabbed = grab;
		Objc.setGrabbed(grab);
		Objc.mousePos(pos);
		x = pos[0];
		y = pos[1];
		dx = dy = 0;
	}

	public static boolean isGrabbed() {
		return grabbed;
	}

	public static void setCursorPosition(int nx, int ny) {
		if (Win.isCreated()) {
			Objc.setCursorPosition(nx, ny);
		}
		x = nx;
		y = ny;
	}

	private Mice() {
	}
}
