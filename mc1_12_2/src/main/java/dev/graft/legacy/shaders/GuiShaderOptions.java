package dev.graft.legacy.shaders;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** A pack's option screen (main or a sub-screen), laid out from shaders.properties. Right click / shift-click: previous / default. */
public final class GuiShaderOptions extends GuiScreen {
	private static final int DONE = 10000, PREV = 10001, NEXT = 10002;
	private final GuiScreen parent;
	private final String screen;
	private OptionScreens model;
	private String error;
	private List<OptionScreens.Entry> entries;
	private int page, perPage;

	GuiShaderOptions(GuiScreen parent, String pack, String screen) {
		this(parent, screen, open(pack));
	}

	private GuiShaderOptions(GuiScreen parent, String screen, Object model) {
		this.parent = parent;
		this.screen = screen;
		if (model instanceof OptionScreens) {
			this.model = (OptionScreens)model;
		} else {
			error = String.valueOf(model);
		}
	}

	private static Object open(String pack) {
		try {
			return new OptionScreens(pack);
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	@Override
	public void initGui() {
		buttonList.clear();
		buttonList.add(new GuiButton(DONE, width / 2 - 100, height - 28, 200, 20, "Done"));
		if (model == null) {
			return;
		}
		entries = model.entries(screen);
		int cols = model.columns(screen, entries.size()), bw = Math.min(200, (width - 20) / cols - 4), rows = Math.max(1, (height - 80) / 22);
		perPage = rows * cols;
		int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int x0 = width / 2 - (cols * (bw + 4) - 4) / 2;
		for (int i = page * perPage; i < entries.size() && i < (page + 1) * perPage; i++) {
			int k = i - page * perPage;
			OptionScreens.Entry e = entries.get(i);
			GuiButton b = new GuiButton(i, x0 + k % cols * (bw + 4), 30 + k / cols * 22, bw, 20, fit(model.label(e), bw));
			b.visible = !e.kind.equals("empty");
			buttonList.add(b);
		}
		if (pages > 1) {
			buttonList.add(new GuiButton(PREV, width / 2 - 154, height - 28, 50, 20, "<"));
			buttonList.add(new GuiButton(NEXT, width / 2 + 104, height - 28, 50, 20, ">"));
		}
	}

	private String fit(String s, int w) {
		if (fontRenderer.getStringWidth(s) <= w - 8) {
			return s;
		}
		while (s.length() > 1 && fontRenderer.getStringWidth(s + "...") > w - 8) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "...";
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
		if (button == 1 && model != null) { // right click: previous value
			for (GuiButton b : buttonList) {
				if (b.id < DONE && b.visible && b.mousePressed(mc, mouseX, mouseY)) {
					click(b.id, true);
					return;
				}
			}
		}
		super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void actionPerformed(GuiButton b) throws IOException {
		if (b.id == DONE) {
			if (model != null) {
				model.save();
				if (screen == null) {
					model.close();
				}
			}
			mc.displayGuiScreen(parent);
		} else if (b.id == PREV || b.id == NEXT) {
			page += b.id == PREV ? -1 : 1;
			page = Math.max(0, page);
			initGui();
		} else {
			click(b.id, false);
		}
	}

	private void click(int index, boolean back) {
		OptionScreens.Entry e = entries.get(index);
		if (e.kind.equals("screen")) {
			mc.displayGuiScreen(new GuiShaderOptions(this, e.name, model));
			return;
		}
		model.click(e, back, isShiftKeyDown());
		initGui();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		String title = model == null ? "Shader Options" : model.packName + (screen != null ? " - " + screen : "");
		drawCenteredString(fontRenderer, title, width / 2, 12, 0xFFFFFF);
		if (error != null) {
			drawCenteredString(fontRenderer, error, width / 2, height / 2, 0xFF6060);
		}
		super.drawScreen(mouseX, mouseY, partialTicks);
		if (model != null) {
			for (GuiButton b : buttonList) {
				if (b.id < DONE && b.visible && b.isMouseOver()) {
					String c = model.comment(entries.get(b.id));
					if (c != null && !c.isEmpty()) {
						drawHoveringText(Arrays.asList(fontRenderer.listFormattedStringToWidth(c, 240).toArray(new String[0])), mouseX, mouseY);
					}
				}
			}
		}
	}
}
