package dev.graft.v1_21_11.compat.sodium;

import dev.graft.v1_21_11.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram", remap = false)
public abstract class GlProgramMixin {
	@Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glUseProgram(I)V"))
	private void graft$bind(int program) {
		SodiumMetal.useProgram(program);
	}

	@Redirect(method = "unbind", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glUseProgram(I)V"))
	private void graft$unbind(int program) {
		SodiumMetal.useProgram(0);
	}

	@Redirect(method = "delete", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glDeleteProgram(I)V"))
	private void graft$delete(int program) {
		SodiumMetal.deleteProgram(program);
	}

	@Redirect(method = {"bindUniform", "bindUniformOptional"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetUniformLocation(ILjava/lang/CharSequence;)I"))
	private int graft$location(int program, CharSequence name) {
		return SodiumMetal.uniformLocation(program, name);
	}

	@Redirect(method = {"bindUniformBlock", "bindUniformBlockOptional"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glGetUniformBlockIndex(ILjava/lang/CharSequence;)I"))
	private int graft$blockIndex(int program, CharSequence name) {
		return SodiumMetal.uniformBlockIndex(program, name);
	}

	@Redirect(method = {"bindUniformBlock", "bindUniformBlockOptional"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glUniformBlockBinding(III)V"))
	private void graft$blockBinding(int program, int index, int binding) {
		SodiumMetal.uniformBlockBinding(program, index, binding);
	}
}
