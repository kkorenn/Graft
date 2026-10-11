package dev.graft.v1_21.compat;

import com.google.common.collect.ImmutableList;
import dev.graft.metal.MetalConfig;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;

/** Graft's page in Sodium 0.6/0.7's video settings (those have no config API; see SodiumOptionsGuiMixin). */
public final class SodiumPage {
	private SodiumPage() {
	}

	private static final OptionStorage<MetalConfig> STORAGE = new OptionStorage<>() {
		@Override
		public MetalConfig getData() {
			return null; // bindings below use MetalConfig's static fields
		}

		@Override
		public void save() {
			MetalConfig.save();
		}
	};

	public static OptionPage create() {
		return new OptionPage(Component.literal("Graft"), ImmutableList.of(OptionGroup.createBuilder()
			.add(OptionImpl.createBuilder(Integer.class, STORAGE)
				.setName(Component.literal("Render Scale"))
				.setTooltip(Component.literal("Renders the world at a lower resolution and upscales it with Apple MetalFX. "
					+ "Big FPS gains with heavy shader packs; the HUD and menus stay at full resolution.\n100% = native, off."))
				.setControl(option -> new SliderControl(option, 50, 100, 5, v -> Component.literal(v >= 100 ? "Native" : v + "% (MetalFX)")))
				.setBinding((config, value) -> MetalConfig.renderScale = value, config -> MetalConfig.renderScale)
				.build())
			.build()));
	}
}
