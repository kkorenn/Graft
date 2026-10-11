package dev.graft.v26_2.compat;

import com.mojang.blaze3d.systems.RenderSystem;

public final class SodiumCompat {
	private SodiumCompat() {
	}

	public static boolean onMetal() {
		return RenderSystem.getDevice() != null && dev.graft.v26_2.MetalGpuDevice.BACKEND_NAME.equals(RenderSystem.getDevice().getDeviceInfo().backendName());
	}
}
