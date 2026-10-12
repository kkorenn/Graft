package dev.graft.legacy.video;

import dev.graft.legacy.video.VideoSettings.Opt;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout and behavior of the Video Settings screens, shared by every version's {@link VideoScreen}: two columns of
 * 150 px buttons, sub-screen buttons above Done, extra pages when the options do not fit, tooltips after a hover delay.
 */
public final class VideoUi {
	public static final String SHADERS = "@shaders", DONE = "@done", PREV = "@prev", NEXT = "@next", ALL_ON = "@allOn", ALL_OFF = "@allOff";
	static final String PAGE = "@page:";
	private static final String[] NAV = {"Video...", "Details...", "Quality...", "Animations...", "Performance...", "Other..."};
	private static final int ROW = 21, TOOLTIP_DELAY_MS = 700;

	public static final class Slot {
		public final Object entry;
		public final int x, y, w;
		public final boolean slider;

		Slot(Object entry, int x, int y, int w) {
			this.entry = entry;
			this.x = x;
			this.y = y;
			this.w = w;
			this.slider = entry instanceof Opt && ((Opt)entry).slider && ((Opt)entry).status == null;
		}
	}

	static int versionBit() {
		return Game.V == 7 ? VideoSettings.V7 : Game.V == 8 ? VideoSettings.V8 : VideoSettings.V12;
	}

	static List<Opt> options(int page) {
		List<Opt> out = new ArrayList<Opt>();
		for (Opt o : VideoSettings.PAGES.get(page)) {
			if ((o.versions & versionBit()) != 0) {
				out.add(o);
			}
		}
		return out;
	}

	private static List<String> fixed(int page) {
		List<String> f = new ArrayList<String>();
		if (page == VideoSettings.MAIN) {
			for (String s : new String[] {SHADERS, PAGE + 1, PAGE + 2, PAGE + 3, PAGE + 4, PAGE + 5}) {
				f.add(s);
			}
		} else if (page == VideoSettings.ANIMATIONS_PAGE) {
			f.add(ALL_ON);
			f.add(ALL_OFF);
		}
		return f;
	}

	/** Option rows that fit above the fixed buttons. */
	private static int rowsFit(int page, int height) {
		int fixedRows = (fixed(page).size() + 1) / 2;
		int top = 24, bottom = height - 28 - fixedRows * ROW - (fixedRows > 0 ? 4 : 0);
		return Math.max(1, (bottom - top) / ROW);
	}

	public static int subPages(int page, int height) {
		int per = rowsFit(page, height) * 2;
		return Math.max(1, (options(page).size() + per - 1) / per);
	}

	public static List<Slot> layout(int page, int sub, int width, int height) {
		List<Slot> out = new ArrayList<Slot>();
		List<Opt> opts = options(page);
		int per = rowsFit(page, height) * 2, subs = subPages(page, height);
		sub = Math.max(0, Math.min(subs - 1, sub));
		int left = width / 2 - 155;
		for (int i = sub * per, n = 0; i < Math.min(opts.size(), (sub + 1) * per); i++, n++) {
			out.add(new Slot(opts.get(i), left + n % 2 * 160, 24 + n / 2 * ROW, 150));
		}
		List<String> f = fixed(page);
		int fixedRows = (f.size() + 1) / 2, fy = height - 28 - fixedRows * ROW - 4;
		for (int i = 0; i < f.size(); i++) {
			out.add(new Slot(f.get(i), left + i % 2 * 160, fy + i / 2 * ROW, 150));
		}
		int doneY = height - 27;
		out.add(new Slot(DONE, width / 2 - 100, doneY, 200));
		if (subs > 1) {
			out.add(new Slot(PREV, width / 2 - 126, doneY, 20));
			out.add(new Slot(NEXT, width / 2 + 106, doneY, 20));
		}
		return out;
	}

	public static String title(int page, int sub, int height) {
		int subs = subPages(page, height);
		return VideoSettings.TITLES[page] + (subs > 1 ? " (" + (Math.max(0, Math.min(subs - 1, sub)) + 1) + "/" + subs + ")" : "");
	}

	public static String label(Object entry) {
		if (entry instanceof Opt) {
			return ((Opt)entry).label();
		}
		String s = (String)entry;
		if (s.startsWith(PAGE)) {
			return NAV[Integer.parseInt(s.substring(PAGE.length()))];
		}
		switch (s) {
			case SHADERS: return "Shaders...";
			case DONE: return "Done";
			case PREV: return "<";
			case NEXT: return ">";
			case ALL_ON: return "All ON";
			case ALL_OFF: return "All OFF";
			default: return s;
		}
	}

	public static boolean enabled(Object entry) {
		if (entry instanceof Opt) {
			return ((Opt)entry).status == null;
		}
		return !SHADERS.equals(entry) || VideoScreens.shadersScreenFactory != null;
	}

	/** Target page of a sub-screen button, or -1. */
	public static int pageOf(Object entry) {
		return entry instanceof String && ((String)entry).startsWith(PAGE) ? Integer.parseInt(((String)entry).substring(PAGE.length())) : -1;
	}

	/** A click on an option button (shift = backwards) or the All ON/OFF buttons. */
	public static void click(Object entry, boolean back) {
		if (entry instanceof Opt) {
			Opt o = (Opt)entry;
			if (o.status == null && o.set(o.next(back))) {
				o.applyNow();
			}
		} else if (ALL_ON.equals(entry) || ALL_OFF.equals(entry)) {
			for (Opt o : VideoSettings.ANIMATIONS) {
				o.set(ALL_ON.equals(entry) ? 1 : 0);
			}
		}
	}

	public static float sliderValue(Opt o) {
		return o.max == o.min ? 0f : (o.get() - o.min) / (float)(o.max - o.min);
	}

	/** Slider drag: sets the value live; returns whether it changed. Side effects wait for {@link #release}. */
	public static boolean drag(Opt o, float frac) {
		int raw = o.min + Math.round(Math.max(0f, Math.min(1f, frac)) * (o.max - o.min) / o.step) * o.step;
		if (o.set(raw)) {
			dirty = o;
			return true;
		}
		return false;
	}

	private static Opt dirty;

	public static void release() {
		if (dirty != null) {
			dirty.applyNow();
			dirty = null;
		}
	}

	private static Object hovered;
	private static long hoverStart;

	/** Tooltip lines once the mouse rested on the same entry long enough, else null. */
	public static String[] tooltip(Object entry) {
		if (entry != hovered) {
			hovered = entry;
			hoverStart = System.currentTimeMillis();
			return null;
		}
		if (entry == null || System.currentTimeMillis() - hoverStart < TOOLTIP_DELAY_MS) {
			return null;
		}
		if (entry instanceof Opt) {
			Opt o = (Opt)entry;
			String[] t = o.tip;
			if (o.status != null) {
				String why = o.status.equals(VideoSettings.METAL) ? "Handled by Metal: this setting has no effect under Graft"
					: o.status.equals(VideoSettings.SOON) ? "Coming soon (resource pack feature)" : o.status.equals(VideoSettings.LATER) ? "Not implemented yet" : null;
				if (why != null) {
					String[] n = new String[t.length + 1];
					System.arraycopy(t, 0, n, 0, t.length);
					n[t.length] = why;
					t = n;
				}
			}
			return t.length == 0 ? null : t;
		}
		if (SHADERS.equals(entry)) {
			return VideoScreens.shadersScreenFactory == null ? new String[] {"Shader packs are not available yet"} : null;
		}
		return null;
	}

	private VideoUi() {
	}
}
