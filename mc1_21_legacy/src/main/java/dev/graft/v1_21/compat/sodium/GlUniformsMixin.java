package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.FloatBuffer;

/** glUniform* write into the current Metal program's uniform block. */
@Pseudo
@Mixin(targets = {
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat",
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v",
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v",
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v",
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt",
	"net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f"
}, remap = false)
public abstract class GlUniformsMixin {
	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform1f(IF)V"), require = 0)
	private void graft$1f(int location, float v) {
		SodiumMetal.uniformFloats(location, v);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform1i(II)V"), require = 0)
	private void graft$1i(int location, int v) {
		SodiumMetal.uniformInt(location, v);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform2f(IFF)V"), require = 0)
	private void graft$2f(int location, float x, float y) {
		SodiumMetal.uniformFloats(location, x, y);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform3f(IFFF)V"), require = 0)
	private void graft$3f(int location, float x, float y, float z) {
		SodiumMetal.uniformFloats(location, x, y, z);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform4f(IFFFF)V"), require = 0)
	private void graft$4f(int location, float x, float y, float z, float w) {
		SodiumMetal.uniformFloats(location, x, y, z, w);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform2fv(ILjava/nio/FloatBuffer;)V"), require = 0)
	private void graft$2fv(int location, FloatBuffer v) {
		SodiumMetal.uniformFloats(location, v);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform3fv(ILjava/nio/FloatBuffer;)V"), require = 0)
	private void graft$3fv(int location, FloatBuffer v) {
		SodiumMetal.uniformFloats(location, v);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniform4fv(ILjava/nio/FloatBuffer;)V"), require = 0)
	private void graft$4fv(int location, FloatBuffer v) {
		SodiumMetal.uniformFloats(location, v);
	}

	@Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glUniformMatrix4fv(IZLjava/nio/FloatBuffer;)V"), require = 0)
	private void graft$mat4(int location, boolean transpose, FloatBuffer v) {
		SodiumMetal.uniformMatrix4(location, v);
	}
}
