package dev.graft.legacy;

/** Minecraft 1.12.2 names used by the shared transformer (mc_legacy_forge). */
final class Version {
	static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder", LAYER = "net/minecraft/util/BlockRenderLayer";
	/** ChunkRenderDispatcher.uploadChunk */
	static final String UPLOAD_DESC = "(L" + LAYER + ";L" + BUFFER + ";Lnet/minecraft/client/renderer/chunk/RenderChunk;"
		+ "Lnet/minecraft/client/renderer/chunk/CompiledChunk;D)Lcom/google/common/util/concurrent/ListenableFuture;";

	private Version() {
	}
}
