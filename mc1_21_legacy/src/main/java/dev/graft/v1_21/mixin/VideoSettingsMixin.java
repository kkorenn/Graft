package dev.graft.v1_21.mixin;

import dev.graft.v1_21.compat.RenderScaleOption;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Scale slider at the end of vanilla Video Settings (used when Sodium isn't installed). */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsMixin {
	@Inject(method = "addOptions", at = @At("TAIL"))
	private void graft$addRenderScale(CallbackInfo ci) {
		var list = ((OptionsSubScreenAccessor)this).graft$list();
		if (list != null) {
			list.addBig(RenderScaleOption.create());
		}
	}
}
