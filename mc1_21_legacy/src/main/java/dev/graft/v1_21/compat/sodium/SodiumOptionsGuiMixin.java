package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumPage;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Adds the Graft page (Render Scale) after Sodium's own pages. */
@Pseudo
@Mixin(value = SodiumOptionsGUI.class, remap = false)
public abstract class SodiumOptionsGuiMixin {
	@Shadow
	@Final
	private List<OptionPage> pages;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void graft$addPage(Screen prevScreen, CallbackInfo ci) {
		this.pages.add(SodiumPage.create());
	}
}
