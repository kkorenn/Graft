package dev.graft.v26_3;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips RingBufferMixin under Lunar Client: Lunar enlarges MappableRingBuffer itself with a required constant patch on
 * the same constant, which fails (and aborts startup) if ours replaced it first. Lunar's change covers the same need.
 */
public class MixinPlugin implements IMixinConfigPlugin {
	private static final boolean LUNAR = ClassLoader.getSystemResource("com/moonsworth/lunar/genesis/Genesis.class") != null
		|| MixinPlugin.class.getClassLoader().getResource("com/moonsworth/lunar/genesis/Genesis.class") != null;

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !(LUNAR && mixinClassName.endsWith(".RingBufferMixin"));
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
