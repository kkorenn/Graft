package dev.graft.v1_21_11.compat.sodium;

import dev.graft.v1_21_11.compat.SodiumMetal;

import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlAbstractTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(value = GlAbstractTessellation.class, remap = false)
public abstract class TessellationMixin {
	@Shadow
	@Final
	protected TessellationBinding[] bindings;

	/** @author Graft @reason Metal: record the vertex layout on the bound VAO */
	@Overwrite
	protected void bindAttributes(CommandList commandList) {
		for (TessellationBinding binding : this.bindings) {
			commandList.bindBuffer(binding.target(), binding.buffer());
			for (GlVertexAttributeBinding a : binding.attributeBindings()) {
				SodiumMetal.attribute(a.getIndex(), a.getCount(), a.getFormat(), a.isNormalized(), a.isIntType(), a.getStride(), a.getPointer());
			}
		}
	}
}
