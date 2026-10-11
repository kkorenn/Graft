package dev.graft.v26_3.mixin;

import dev.graft.metal.MetalConfig;
import dev.graft.v26_3.MetalBackend;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow
	@Final
	public Options options;

	/** Options are loaded, the backend isn't picked yet: switch the renderer to Metal the first time the mod runs. */
	@Inject(method = "<init>", at = @At(value = "NEW", target = "com/mojang/blaze3d/platform/MonitorManager"))
	private void graft$selectMetalOnce(CallbackInfo ci) {
		MetalConfig.load(FabricLoader.getInstance().getConfigDir().resolve("graft.properties"));
		if (!MetalConfig.metalSelected) {
			MetalConfig.metalSelected = true;
			MetalConfig.save();
			options.preferredGraphicsBackend().set(MetalBackend.OPTION);
			options.save();
		}
	}
}
