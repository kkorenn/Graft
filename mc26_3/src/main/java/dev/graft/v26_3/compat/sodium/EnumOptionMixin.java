package dev.graft.v26_3.compat.sodium;

import dev.graft.v26_3.MetalBackend;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium names graphics API entries from a fixed array indexed by ordinal; the added Metal entry is past its end. */
@Mixin(value = EnumOption.class, remap = false)
public class EnumOptionMixin {
	@Inject(method = "getElementName", at = @At("HEAD"), cancellable = true)
	private void graft$metalName(Enum<?> element, CallbackInfoReturnable<Component> cir) {
		if (element != null && element == MetalBackend.OPTION) {
			cir.setReturnValue(MetalBackend.OPTION.caption());
		}
	}
}
