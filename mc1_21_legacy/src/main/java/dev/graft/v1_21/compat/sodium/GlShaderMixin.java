package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shader objects keep their source; compilation happens when the program links (GlShaders). */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class GlShaderMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCreateShader(I)I"))
	private int graft$create(int type) {
		return SodiumMetal.createShader(type);
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds;safeShaderSource(ILjava/lang/CharSequence;)V"))
	private void graft$source(int shader, CharSequence source) {
		SodiumMetal.shaderSource(shader, source);
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCompileShader(I)V"))
	private void graft$compile(int shader) {
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetShaderInfoLog(I)Ljava/lang/String;"))
	private String graft$log(int shader) {
		return "";
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glGetShaderi(II)I", remap = true))
	private int graft$status(int shader, int pname) {
		return 1;
	}

	@Redirect(method = "delete", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glDeleteShader(I)V"))
	private void graft$delete(int shader) {
		SodiumMetal.deleteShader(shader);
	}
}
