package dev.graft.v1_21_11.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MainTargetAccessor {
	@Accessor("mainRenderTarget")
	RenderTarget graft$getMainRenderTarget();

	@Mutable
	@Accessor("mainRenderTarget")
	void graft$setMainRenderTarget(RenderTarget target);
}
