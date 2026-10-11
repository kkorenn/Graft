package dev.graft.legacy;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.util.HashMap;

/**
 * Native Tessellator submit: WorldVertexBufferUploader.draw lands here, and the BufferBuilder's vertices go to Metal as
 * they are (no glVertexPointer/glDrawArrays). Entities, tile entities, particles, sky, weather, GUI and items all draw
 * through this.
 */
public final class Tess {
	private static final HashMap<VertexFormat, int[][]> layouts = new HashMap<VertexFormat, int[][]>();

	public static void draw(BufferBuilder wr) {
		int n = wr.getVertexCount();
		if (n > 0) {
			VertexFormat f = wr.getVertexFormat();
			int[][] l = layouts.get(f);
			if (l == null) {
				layouts.put(new VertexFormat(f), l = layout(f));
			}
			MGL.submit(wr.getDrawMode(), n, f.getSize(), l[0], l[1], MGL.address(wr.getByteBuffer()) - wr.getByteBuffer().position());
		}
		wr.reset();
	}

	/** {Metal formats, byte offsets} per MGL attribute slot. */
	private static int[][] layout(VertexFormat f) {
		int[] fmt = new int[5], off = new int[5];
		for (int i = 0; i < f.getElementCount(); i++) {
			VertexFormatElement e = f.getElement(i);
			int slot;
			switch (e.getUsage()) {
				case POSITION: slot = MGL.POS; break;
				case COLOR: slot = MGL.COL; break;
				case NORMAL: slot = MGL.NRM; break;
				case UV: slot = e.getIndex() == 0 ? MGL.TEX0 : e.getIndex() == 1 ? MGL.TEX1 : -1; break;
				default: slot = -1;
			}
			if (slot >= 0) {
				fmt[slot] = MGL.metalFormat(slot, e.getElementCount(), e.getType().getGlConstant());
				off[slot] = f.getOffset(i);
			}
		}
		return new int[][] {fmt, off};
	}

	private Tess() {
	}
}
