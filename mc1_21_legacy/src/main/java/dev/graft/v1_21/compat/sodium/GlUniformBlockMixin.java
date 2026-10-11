package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBlock", remap = false)
public abstract class GlUniformBlockMixin {
	@Redirect(method = "bindBuffer", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glBindBufferBase(III)V"))
	private void graft$bind(int target, int binding, int buffer) {
		SodiumMetal.bindUniformBuffer(binding, buffer);
	}
}
