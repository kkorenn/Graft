package dev.graft.v1_21.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Sodium compat mixins apply only with a Sodium version the Metal emulation supports (0.8.x). */
public class CompatMixinPlugin implements IMixinConfigPlugin {
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// -Dgraft.disable=true: plain OpenGL, Sodium must stay untouched.
		return sodiumSupported() && !Boolean.getBoolean("graft.disable") && !Boolean.getBoolean("graft.disabled");
	}

	/** Sodium 0.7.x runs on Metal. Lives here, not in MetalGpuDevice: loading that this early drags in Blaze3D classes other mods mix into. */
	public static boolean sodiumSupported() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("sodium")
			.map(m -> m.getMetadata().getVersion().getFriendlyString().startsWith("0.7."))
			.orElse(false);
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
