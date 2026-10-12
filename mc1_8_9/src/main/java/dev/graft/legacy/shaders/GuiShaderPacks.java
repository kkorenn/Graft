package dev.graft.legacy.shaders;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.List;

/** Shader pack selection (Video Settings -> Shaders...): packs in .minecraft/shaderpacks, OFF, options, open folder. */
public final class GuiShaderPacks extends GuiScreen {
	private static final int PER_PAGE = 8, OFF = 1000, OPTIONS = 1001, FOLDER = 1002, DONE = 1003, PREV = 1004, NEXT = 1005;
	private final GuiScreen parent;
	private List<String> packs;
	private int page;

	private GuiShaderPacks(GuiScreen parent) {
		this.parent = parent;
	}

	/** For VideoScreens.shadersScreenFactory. */
	public static Object create(Object parent) {
		Hooks.init();
		return new GuiShaderPacks((GuiScreen)parent);
	}

	@Override
	public void initGui() {
		packs = ShaderPipeline.listPacks();
		buttonList.clear();
		int x = width / 2 - 100, y = 40;
		String sel = ShaderPipeline.selected();
		buttonList.add(new GuiButton(OFF, x, y, 200, 20, sel == null ? "> OFF <" : "OFF"));
		int pages = Math.max(1, (packs.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.min(page, pages - 1);
		for (int i = page * PER_PAGE, row = 1; i < packs.size() && i < (page + 1) * PER_PAGE; i++, row++) {
			String p = packs.get(i);
			String label = p.length() > 32 ? p.substring(0, 31) + "..." : p;
			buttonList.add(new GuiButton(i, x, y + row * 22, 200, 20, p.equals(sel) ? "> " + label + " <" : label));
		}
		int by = height - 28;
		GuiButton prev = new GuiButton(PREV, x - 24, y + 22, 20, 20, "<"), next = new GuiButton(NEXT, x + 204, y + 22, 20, 20, ">");
		prev.visible = next.visible = pages > 1;
		buttonList.add(prev);
		buttonList.add(next);
		GuiButton options = new GuiButton(OPTIONS, width / 2 - 154, by, 100, 20, "Shader Options...");
		options.enabled = sel != null;
		buttonList.add(options);
		buttonList.add(new GuiButton(FOLDER, width / 2 - 50, by, 100, 20, "Open Folder"));
		buttonList.add(new GuiButton(DONE, width / 2 + 54, by, 100, 20, "Done"));
	}

	@Override
	protected void actionPerformed(GuiButton b) throws IOException {
		if (!b.enabled) {
			return;
		}
		switch (b.id) {
			case OFF: ShaderPipeline.select(null); break;
			case OPTIONS: mc.displayGuiScreen(new GuiShaderOptions(this, ShaderPipeline.selected(), null)); return;
			case FOLDER: Runtime.getRuntime().exec(new String[] {"/usr/bin/open", ShaderPipeline.packsDir().getAbsolutePath()}); return;
			case DONE: mc.displayGuiScreen(parent); return;
			case PREV: page = Math.max(0, page - 1); break;
			case NEXT: page++; break;
			default:
				if (b.id >= 0 && b.id < packs.size()) {
					ShaderPipeline.select(packs.get(b.id));
				}
		}
		initGui();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		drawCenteredString(fontRendererObj, "Shader Packs", width / 2, 15, 0xFFFFFF);
		String status = ShaderPipeline.status;
		if (ShaderPipeline.disabledReason != null) {
			drawCenteredString(fontRendererObj, ShaderPipeline.disabledReason, width / 2, height - 42, 0xFF6060);
		} else if (status != null && !status.isEmpty()) {
			boolean err = status.contains("!");
			drawCenteredString(fontRendererObj, status.replace("!", ""), width / 2, height - 42, err ? 0xFF6060 : 0xA0A0A0);
		} else if (packs.isEmpty()) {
			drawCenteredString(fontRendererObj, "Put shader packs (.zip or folders) in .minecraft/shaderpacks", width / 2, height - 42, 0xA0A0A0);
		}
		super.drawScreen(mouseX, mouseY, partialTicks);
	}
}
