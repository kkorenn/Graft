package dev.graft.legacy.video;

import dev.graft.legacy.video.VideoSettings.Opt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.io.IOException;
import java.util.function.Function;

/** OptiFine-style Video Settings screen (and its sub-screens) for Minecraft 1.8.9; layout and behavior in {@link VideoUi}. */
public final class VideoScreen extends GuiScreen {
	private final GuiScreen parent;
	private final int page;
	private int sub;

	VideoScreen(GuiScreen parent, int page) {
		this.parent = parent;
		this.page = page;
	}

	@Override
	public void initGui() {
		buttonList.clear();
		int id = 0;
		for (VideoUi.Slot s : VideoUi.layout(page, sub, width, height)) {
			buttonList.add(s.slider ? new Slider(id++, s) : new Button(id++, s));
		}
	}

	@Override
	protected void actionPerformed(GuiButton b) throws IOException {
		if (!(b instanceof Button) || b instanceof Slider || !b.enabled) {
			return;
		}
		Object e = ((Button)b).slot.entry;
		int target = VideoUi.pageOf(e);
		if (target >= 0) {
			mc.displayGuiScreen(new VideoScreen(this, target));
		} else if (VideoUi.DONE.equals(e)) {
			close();
		} else if (VideoUi.PREV.equals(e) || VideoUi.NEXT.equals(e)) {
			sub += VideoUi.PREV.equals(e) ? -1 : 1;
			sub = Math.floorMod(sub, VideoUi.subPages(page, height));
			initGui();
		} else if (VideoUi.SHADERS.equals(e)) {
			Function<Object, Object> f = VideoScreens.shadersScreenFactory;
			if (f != null) {
				VideoSettings.save();
				mc.displayGuiScreen((GuiScreen)f.apply(this));
			}
		} else {
			VideoUi.click(e, isShiftKeyDown());
			if (e == VideoSettings.GUI_SCALE) {
				ScaledResolution r = new ScaledResolution(mc);
				setWorldAndResolution(mc, r.getScaledWidth(), r.getScaledHeight());
			}
			refresh();
		}
	}

	private void refresh() {
		for (GuiButton b : buttonList) {
			if (b instanceof Button) {
				((Button)b).update();
			}
		}
	}

	private void close() {
		VideoSettings.save();
		Game.saveVanilla();
		mc.displayGuiScreen(parent);
	}

	@Override
	protected void keyTyped(char c, int key) throws IOException {
		if (key == 1) {
			close();
		}
	}

	@Override
	protected void mouseReleased(int x, int y, int button) {
		super.mouseReleased(x, y, button);
		VideoUi.release();
		refresh();
	}

	@Override
	public void onGuiClosed() {
		VideoUi.release();
		VideoSettings.save();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		drawCenteredString(fontRendererObj, VideoUi.title(page, sub, height), width / 2, 10, 0xFFFFFF);
		super.drawScreen(mouseX, mouseY, partialTicks);
		Button over = null;
		for (GuiButton b : buttonList) {
			if (b instanceof Button && b.visible && mouseX >= b.xPosition && mouseY >= b.yPosition && mouseX < b.xPosition + b.width && mouseY < b.yPosition + b.height) {
				over = (Button)b;
			}
		}
		String[] tip = VideoUi.tooltip(over == null ? null : over.slot.entry);
		if (tip != null) {
			int w = 0;
			for (String line : tip) {
				w = Math.max(w, fontRendererObj.getStringWidth(line));
			}
			int x = Math.max(4, Math.min(width - w - 10, over.xPosition)), h = tip.length * 11 + 6;
			int y = over.yPosition + over.height + 4;
			if (y + h > height - 2) {
				y = over.yPosition - h - 4;
			}
			drawRect(x - 3, y - 3, x + w + 5, y + h - 3, 0xE0101010);
			drawHorizontalLine(x - 3, x + w + 4, y - 3, 0xFF808080);
			drawHorizontalLine(x - 3, x + w + 4, y + h - 4, 0xFF808080);
			for (int i = 0; i < tip.length; i++) {
				fontRendererObj.drawStringWithShadow(tip[i], x, y + i * 11, 0xFFFFFF);
			}
		}
	}

	private static class Button extends GuiButton {
		final VideoUi.Slot slot;

		Button(int id, VideoUi.Slot s) {
			super(id, s.x, s.y, s.w, 20, VideoUi.label(s.entry));
			slot = s;
			enabled = VideoUi.enabled(s.entry);
		}

		void update() {
			displayString = VideoUi.label(slot.entry);
			enabled = VideoUi.enabled(slot.entry);
		}
	}

	private static final class Slider extends Button {
		private boolean dragging;

		Slider(int id, VideoUi.Slot s) {
			super(id, s);
		}

		private Opt opt() {
			return (Opt)slot.entry;
		}

		private void drag(int mouseX) {
			VideoUi.drag(opt(), (mouseX - (xPosition + 4)) / (float)(width - 8));
			update();
		}

		@Override
		protected int getHoverState(boolean mouseOver) {
			return 0;
		}

		@Override
		protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
			if (!visible) {
				return;
			}
			if (dragging) {
				drag(mouseX);
			}
			mc.getTextureManager().bindTexture(buttonTextures);
			GlStateManager.color(1f, 1f, 1f, 1f);
			int k = xPosition + (int)(VideoUi.sliderValue(opt()) * (width - 8));
			drawTexturedModalRect(k, yPosition, 0, 66, 4, 20);
			drawTexturedModalRect(k + 4, yPosition, 196, 66, 4, 20);
		}

		@Override
		public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
			if (super.mousePressed(mc, mouseX, mouseY)) {
				dragging = true;
				drag(mouseX);
				return true;
			}
			return false;
		}

		@Override
		public void mouseReleased(int mouseX, int mouseY) {
			dragging = false;
		}
	}
}
