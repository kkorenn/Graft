package dev.graft.legacy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * "Render Scale" slider in Video Settings (vanilla or OptiFine's, which keeps the class name): FML's GuiSlider takes
 * the left half of the Done button's row. 50-100 in steps of 5, live, saved to config/graft.properties.
 * The 1.7.10 Forge jar is compiled against notch names, so GuiSlider and Minecraft's GuiButton are reached only by
 * reflection (MCP or SRG names), as everywhere in 1.7.10.
 */
@Mod(modid = "graft", useMetadata = true, acceptableRemoteVersions = "*")
public final class ScaleSlider {
	private static final int ID = 0x4D43, DONE = 200;
	private static final String BUTTON = "net.minecraft.client.gui.GuiButton", SLIDER = "cpw.mods.fml.client.config.GuiSlider";
	private static Field gui, id, x, y, width, displayString, sliderValue;
	private static Method getValue;
	private static Constructor<?> newSlider;
	private static Object listener;
	private static boolean reflectFailed;

	public ScaleSlider() {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	@SuppressWarnings("unchecked")
	public void initGui(GuiScreenEvent.InitGuiEvent.Post e) {
		if (reflectFailed) {
			return;
		}
		try {
			if (gui == null) {
				Class<?> slider = Class.forName(SLIDER), iSlider = Class.forName(SLIDER + "$ISlider");
				newSlider = slider.getConstructor(int.class, int.class, int.class, int.class, int.class, String.class, String.class,
					double.class, double.class, double.class, boolean.class, boolean.class, iSlider);
				sliderValue = slider.getField("sliderValue");
				getValue = slider.getMethod("getValue");
				listener = Proxy.newProxyInstance(iSlider.getClassLoader(), new Class<?>[] {iSlider}, (proxy, m, args) -> {
					if (m.getName().equals("onChangeSliderValue")) {
						changed(args[0]);
						return null;
					}
					return m.getName().equals("equals") ? proxy == args[0] : m.getName().equals("hashCode") ? System.identityHashCode(proxy) : "ScaleSlider";
				});
				id = Reflect.field(BUTTON, "id", "field_146127_k");
				x = Reflect.field(BUTTON, "xPosition", "field_146128_h");
				y = Reflect.field(BUTTON, "yPosition", "field_146129_i");
				width = Reflect.field(BUTTON, "width", "field_146120_f");
				displayString = Reflect.field(BUTTON, "displayString", "field_146126_j");
				gui = GuiScreenEvent.class.getField("gui");
			}
			Object screen = gui.get(e);
			if (screen == null || !screen.getClass().getName().equals("net.minecraft.client.gui.GuiVideoSettings")) {
				return;
			}
			List<Object> buttons = e.buttonList;
			for (Object done : buttons) {
				if (id.getInt(done) == DONE && width.getInt(done) >= 200) { // split the Done row: slider left, Done right
					int cx = x.getInt(done) + width.getInt(done) / 2;
					x.setInt(done, cx + 5);
					width.setInt(done, 150);
					Object s = newSlider.newInstance(ID, cx - 155, y.getInt(done), 150, 20, "", "", 50.0, 100.0, (double)Scale.renderScale, false, true, listener);
					changed(s);
					buttons.add(s);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
			reflectFailed = true;
			MGL.LOG.warn("Graft: no render scale slider (1.7.10 reflection failed)", ex);
		}
	}

	private static void changed(Object s) throws ReflectiveOperationException {
		int v = Math.max(50, Math.min(100, (int)Math.round((Double)getValue.invoke(s) / 5) * 5));
		sliderValue.setDouble(s, (v - 50) / 50.0);
		displayString.set(s, v >= 100 ? "Render Scale: Native" : "Render Scale: " + v + "% (MetalFX)");
		Scale.set(v);
	}
}
