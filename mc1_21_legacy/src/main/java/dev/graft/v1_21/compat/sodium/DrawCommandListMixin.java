package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList", remap = false)
public abstract class DrawCommandListMixin {
	/** @author Graft @reason Metal */
	@Overwrite
	public void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType) {
		SodiumMetal.multiDraw(batch.pElementCount, batch.pElementPointer, batch.pBaseVertex, batch.size, indexType.getFormatId());
	}
}
