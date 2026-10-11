package dev.graft.v1_21.compat.sodium;

import dev.graft.v1_21.compat.SodiumMetal;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** Fence "ids" are Metal submit indices. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.sync.GlFence", remap = false)
public abstract class GlFenceMixin {
	@Shadow
	@Final
	private long id;
	@Shadow
	private boolean disposed;

	/** @author Graft @reason Metal */
	@Overwrite
	public boolean isCompleted() {
		return SodiumMetal.fenceDone(this.id, 0L);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void sync(long timeout) {
		SodiumMetal.fenceDone(this.id, timeout);
	}

	/** @author Graft @reason Metal */
	@Overwrite
	public void delete() {
		this.disposed = true;
	}
}
