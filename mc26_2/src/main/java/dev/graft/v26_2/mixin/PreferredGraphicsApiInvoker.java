package dev.graft.v26_2.mixin;

import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PreferredGraphicsApi.class)
public interface PreferredGraphicsApiInvoker {
	@Invoker("<init>")
	static PreferredGraphicsApi graft$create(String name, int ordinal, String serializedName, String key) {
		throw new AssertionError();
	}
}
