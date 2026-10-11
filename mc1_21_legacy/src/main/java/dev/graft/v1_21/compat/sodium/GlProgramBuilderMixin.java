package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder", remap = false)
public abstract class GlProgramBuilderMixin {
	@Shadow
	@Final
	private ResourceLocation name;

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCreateProgram()I"))
	private int graft$create() {
		return SodiumMetal.createProgram("sodium");
	}

	@Redirect(method = "attachShader", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glAttachShader(II)V"))
	private void graft$attach(int program, int shader) {
		SodiumMetal.attachShader(program, shader);
	}

	@Redirect(method = "bindAttribute", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glBindAttribLocation(IILjava/lang/CharSequence;)V"))
	private void graft$attrib(int program, int index, CharSequence name) {
		SodiumMetal.bindAttribLocation(program, index, name);
	}

	@Redirect(method = "bindFragmentData", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30C;glBindFragDataLocation(IILjava/lang/CharSequence;)V"))
	private void graft$fragData(int program, int index, CharSequence name) {
		SodiumMetal.bindFragDataLocation(program, index, name);
	}

	@Redirect(method = "link", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glLinkProgram(I)V"))
	private void graft$link(int program) {
		SodiumMetal.linkProgram(program);
	}

	@Redirect(method = "link", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGetProgramInfoLog(I)Ljava/lang/String;"))
	private String graft$log(int program) {
		return "";
	}

	@Redirect(method = "link", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glGetProgrami(II)I", remap = true))
	private int graft$status(int program, int pname) {
		return 1;
	}
}
