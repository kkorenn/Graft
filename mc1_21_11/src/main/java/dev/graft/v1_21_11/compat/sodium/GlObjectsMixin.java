package dev.graft.v1_21_11.compat.sodium;

import dev.graft.v1_21_11.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** GL object names come from SodiumMetal instead of glGen*. */
@Pseudo
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer", "net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray"}, remap = false)
public abstract class GlObjectsMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGenBuffers()I"), require = 0)
	private static int graft$genBuffer() {
		return SodiumMetal.newId();
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glGenVertexArrays()I"), require = 0)
	private static int graft$genVertexArray() {
		return SodiumMetal.newId();
	}
}
