package dev.graft.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * "Render Scale" slider in Video Settings (vanilla or OptiFine's, which keeps the class name): it takes the left half
 * of the Done button's row. 50-100 in steps of 5, live, saved to config/graft.properties.
 */
@Mod(modid = "graft", useMetadata = true, clientSideOnly = true, acceptableRemoteVersions = "*")
public final class ScaleSlider implements GuiSlider.ISlider {
	private static final int ID = 0x4D43, DONE = 200;

	public ScaleSlider() {
		MinecraftForge.EVENT_BUS.register(this);
		if (dev.graft.legacy.video.VideoTransformer.active) {
			dev.graft.legacy.video.Game.register();
		}
	}

	@SubscribeEvent
	public void initGui(GuiScreenEvent.InitGuiEvent.Post e) {
		if (!(e.getGui() instanceof GuiVideoSettings)) {
			return;
		}
		for (GuiButton done : e.getButtonList()) {
			if (done.id == DONE && done.width >= 200) { // split the Done row: slider left, Done right
				int cx = done.x + done.width / 2;
				done.x = cx + 5;
				done.width = 150;
				GuiSlider s = new GuiSlider(ID, cx - 155, done.y, 150, 20, "", "", 50, 100, Scale.renderScale, false, true, this);
				onChangeSliderValue(s);
				e.getButtonList().add(s);
				return;
			}
		}
	}

	@Override
	public void onChangeSliderValue(GuiSlider s) {
		int v = Math.max(50, Math.min(100, (int)Math.round(s.getValue() / 5) * 5));
		s.sliderValue = (v - 50) / 50.0;
		s.displayString = v >= 100 ? "Render Scale: Native" : "Render Scale: " + v + "% (MetalFX)";
		Scale.set(v);
	}
}
