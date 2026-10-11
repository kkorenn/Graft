package dev.graft.v26_2.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import dev.graft.v26_2.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/** Adds METAL to the graphics API option, next to Default/OpenGL/Vulkan. */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
	@Shadow
	@Final
	@Mutable
	private static PreferredGraphicsApi[] $VALUES;

	// Right after $VALUES is set and before CODEC is built from values(), so "metal" round-trips through options.txt.
	@Inject(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/PreferredGraphicsApi;$VALUES:[Lnet/minecraft/client/PreferredGraphicsApi;", opcode = Opcodes.PUTSTATIC, shift = At.Shift.AFTER))
	private static void graft$addMetal(CallbackInfo ci) {
		PreferredGraphicsApi metal = PreferredGraphicsApiInvoker.graft$create("METAL", $VALUES.length, "metal", "options.graphicsApi.metal");
		$VALUES = Arrays.copyOf($VALUES, $VALUES.length + 1);
		$VALUES[$VALUES.length - 1] = metal;
		MetalBackend.OPTION = metal;
	}

	@Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
	private void graft$metalFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
		if ((Object)this != MetalBackend.OPTION) {
			return;
		}
		if (Boolean.getBoolean("graft.vanilla")) {
			return;
		}
		// Falls back to OpenGL, then Vulkan, if Metal can't start, like vanilla's own fallback chain.
		cir.setReturnValue(new GpuBackend[]{new MetalBackend(), new GlBackend(), new VulkanBackend()});
	}
}
