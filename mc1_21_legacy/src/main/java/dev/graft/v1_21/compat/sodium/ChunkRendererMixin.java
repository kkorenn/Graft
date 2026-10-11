package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The chunk pass draws into a Metal render pass on the target (Sodium binds the target's GL framebuffer). */
@Pseudo
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ChunkRendererMixin {
	@Shadow
	@Final
	protected ChunkVertexType vertexType;
	@Shadow
	protected GlProgram<ChunkShaderInterface> activeProgram;

	@Shadow
	protected abstract GlProgram<ChunkShaderInterface> compileProgram(ChunkShaderOptions options);

	@Inject(method = "begin", at = @At("HEAD"), cancellable = true)
	private void graft$begin(TerrainRenderPass pass, FogParameters parameters, CallbackInfo ci) {
		SodiumMetal.beginPass(pass.getTarget(), pass.getPipeline());
		GlProgram<ChunkShaderInterface> program = SodiumMetal.terrainProgram != null ? SodiumMetal.terrainProgram.apply(pass) : null;
		this.activeProgram = program != null ? program : this.compileProgram(new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, this.vertexType));
		this.activeProgram.bind();
		this.activeProgram.getInterface().setupState(pass, parameters);
		ci.cancel();
	}

	@Inject(method = "end", at = @At("TAIL"))
	private void graft$end(TerrainRenderPass pass, CallbackInfo ci) {
		SodiumMetal.endPass();
	}
}
