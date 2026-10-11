package dev.graft.v26_3.compat;

import dev.graft.metal.MetalConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Graft's page in Sodium's video settings. Only loaded when Sodium is installed. */
public final class SodiumOptions implements ConfigEntryPoint {
	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions().addPage(builder.createOptionPage()
			.setName(Component.literal("Display"))
			.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("graft", "display_p3"))
				.setName(Component.literal("Display P3"))
				.setTooltip(Component.literal("On: saturated Display P3 colors (how Minecraft looked on Macs before 26.3).\nOff: accurate sRGB colors."))
				.setDefaultValue(false)
				.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
				.setBinding(value -> MetalConfig.displayP3 = value, () -> MetalConfig.displayP3)
				.setStorageHandler(MetalConfig::save))
			.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("graft", "render_scale"))
				.setName(Component.literal("Render Scale"))
				.setTooltip(Component.literal("Renders the world at a lower resolution and upscales it with Apple MetalFX. "
					+ "Big FPS gains with heavy shader packs; the HUD and menus stay at full resolution.\n100% = native, off."))
				.setRange(50, 100, 5)
				.setValueFormatter(v -> Component.literal(v >= 100 ? "Native" : v + "% (MetalFX)"))
				.setDefaultValue(100)
				.setBinding(value -> MetalConfig.renderScale = value, () -> MetalConfig.renderScale)
				.setStorageHandler(MetalConfig::save)));
	}
}
