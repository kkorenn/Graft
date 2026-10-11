package dev.graft.v26_3.compat;

import dev.graft.metal.MetalConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Render Scale slider for vanilla Video Settings (Sodium's settings screen has its own). */
public final class RenderScaleOption {
	private RenderScaleOption() {
	}

	public static OptionInstance<Integer> create() {
		return new OptionInstance<>(
			"Render Scale",
			OptionInstance.cachedConstantTooltip(Component.literal("Renders the world at a lower resolution and upscales it with Apple MetalFX. "
				+ "Big FPS gains with heavy shader packs; the HUD and menus stay at full resolution.\n100% = native, off.")),
			// Steps of 5%: the slider value is percent / 5.
			(caption, step) -> Options.genericValueLabel(caption, Component.literal(step >= 20 ? "Native" : step * 5 + "% (MetalFX)")),
			new OptionInstance.IntRange(10, 20),
			Math.clamp(MetalConfig.renderScale / 5, 10, 20),
			step -> {
				MetalConfig.renderScale = step * 5;
				MetalConfig.save();
			}
		);
	}
}
