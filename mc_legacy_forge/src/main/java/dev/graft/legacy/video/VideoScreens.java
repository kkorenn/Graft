package dev.graft.legacy.video;

import java.util.function.Function;

/** Hooks for screens other features provide. */
public final class VideoScreens {
	/**
	 * Opens the shader pack screen: called with the Video Settings screen (a GuiScreen, to return to) and returns the
	 * GuiScreen to show. The "Shaders..." button is disabled while this is null.
	 */
	public static volatile Function<Object, Object> shadersScreenFactory;

	private VideoScreens() {
	}
}
