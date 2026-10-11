package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderTextureSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Pseudo
@Mixin(value = DefaultShaderInterface.class, remap = false)
public abstract class ShaderInterfaceMixin {
	@Shadow
	@Final
	private Map<ChunkShaderTextureSlot, GlUniformInt> uniformTextures;

	/** @author Graft @reason Metal: texture units instead of GL texture binds (sampling state comes from the texture) */
	@Overwrite
	private void bindTexture(ChunkShaderTextureSlot slot, GpuTextureView view) {
		SodiumMetal.bindTexture(slot.ordinal(), view);
		this.uniformTextures.get(slot).setInt(slot.ordinal());
	}
}
