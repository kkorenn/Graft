package dev.graft.legacy;

import org.lwjgl.input.Keyboard;

import java.util.ArrayDeque;

/** org.lwjgl.input.Keyboard on Graft's window (events arrive through {@link Win#pump}). */
public final class Keys {
	private static final boolean[] down = new boolean[256];
	private static final ArrayDeque<long[]> queue = new ArrayDeque<long[]>();
	private static boolean repeat;
	private static long[] current = new long[5];

	static void event(int key, boolean state, char ch, boolean isRepeat) {
		if (isRepeat && !repeat) {
			return;
		}
		down[key & 255] = state;
		queue.add(new long[] {key, state ? 1 : 0, ch, isRepeat ? 1 : 0, System.nanoTime()});
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

	public static int getNumKeyboardEvents() {
		return queue.size();
	}

	public static int getEventKey() {
		return (int)current[0];
	}

	public static boolean getEventKeyState() {
		return current[1] != 0;
	}

	public static char getEventCharacter() {
		return (char)current[2];
	}

	public static boolean isRepeatEvent() {
		return current[3] != 0;
	}

	public static long getEventNanoseconds() {
		return current[4];
	}

	public static boolean isKeyDown(int key) {
		return key >= 0 && key < 256 && down[key];
	}

	public static void enableRepeatEvents(boolean enable) {
		repeat = enable;
	}

	public static boolean areRepeatEventsEnabled() {
		return repeat;
	}

	public static String getKeyName(int key) {
		return Keyboard.getKeyName(key); // pure Java table in LWJGL
	}

	public static int getKeyIndex(String name) {
		return Keyboard.getKeyIndex(name);
	}

	public static int getKeyCount() {
		return Keyboard.getKeyCount();
	}

	private Keys() {
	}
}
