package dev.graft.legacy;

/**
 * Minecraft 1.7.10 has no chunk-upload/Tessellator-submit classes of the 1.8+ shape, so the native terrain/Tessellator
 * hooks never match; 1.7.10 chunks (display lists) and Tessellator draws (client arrays) go through MGL's generic paths.
 */
final class Version {
	static final String BUFFER = "-", LAYER = "-", UPLOAD_DESC = "-";

	private Version() {
	}
}
