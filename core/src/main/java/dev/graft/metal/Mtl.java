package dev.graft.metal;

/**
 * Metal enum values (verified against the macOS SDK) and mappings from Minecraft's render enums.
 * Minecraft enums are passed by name() so this class works for every game version.
 */
public final class Mtl {
	public static final long PIXEL_INVALID = 0;
	public static final long PIXEL_BGRA8 = 80;
	public static final long PIXEL_DEPTH32F = 252;
	public static final long PIXEL_DEPTH32F_S8 = 260;
	public static final long PIXEL_STENCIL8 = 253;

	public static final long TEX_2D = 2;
	public static final long TEX_2D_ARRAY = 3;
	public static final long TEX_CUBE = 5;
	public static final long TEX_BUFFER = 9;

	public static final long USAGE_SHADER_READ = 1;
	public static final long USAGE_SHADER_WRITE = 2;
	public static final long USAGE_RENDER_TARGET = 4;
	public static final long USAGE_PIXEL_FORMAT_VIEW = 16;

	public static final long STORAGE_SHARED_OPTIONS = 0;
	public static final long STORAGE_PRIVATE = 2;

	public static final long LOAD_DONT_CARE = 0;
	public static final long LOAD_LOAD = 1;
	public static final long LOAD_CLEAR = 2;
	public static final long STORE_STORE = 1;

	public static final long PRIM_POINT = 0;
	public static final long PRIM_LINE = 1;
	public static final long PRIM_LINE_STRIP = 2;
	public static final long PRIM_TRIANGLE = 3;
	public static final long PRIM_TRIANGLE_STRIP = 4;

	public static final long CB_STATUS_COMPLETED = 4;
	public static final long CB_STATUS_ERROR = 5;

	private Mtl() {
	}

	public static long pixelFormat(String gpuFormat) {
		return switch (gpuFormat) {
			case "R8_UNORM" -> 10;
			case "R8_SNORM" -> 12;
			case "R8_UINT" -> 13;
			case "R8_SINT" -> 14;
			case "R16_UNORM" -> 20;
			case "R16_SNORM" -> 22;
			case "R16_UINT" -> 23;
			case "R16_SINT" -> 24;
			case "R16_FLOAT" -> 25;
			case "RG8_UNORM" -> 30;
			case "RG8_SNORM" -> 32;
			case "RG8_UINT" -> 33;
			case "RG8_SINT" -> 34;
			case "R32_UINT" -> 53;
			case "R32_SINT" -> 54;
			case "R32_FLOAT" -> 55;
			case "RG16_UNORM" -> 60;
			case "RG16_SNORM" -> 62;
			case "RG16_UINT" -> 63;
			case "RG16_SINT" -> 64;
			case "RG16_FLOAT" -> 65;
			case "RGBA8_UNORM" -> 70;
			case "RGBA8_SNORM" -> 72;
			case "RGBA8_UINT" -> 73;
			case "RGBA8_SINT" -> 74;
			case "RGB10A2_UNORM" -> 90;
			case "RGB10A2_UINT" -> 91;
			case "RG11B10_FLOAT" -> 92;
			case "RG32_UINT" -> 103;
			case "RG32_SINT" -> 104;
			case "RG32_FLOAT" -> 105;
			case "RGBA16_UNORM" -> 110;
			case "RGBA16_SNORM" -> 112;
			case "RGBA16_UINT" -> 113;
			case "RGBA16_SINT" -> 114;
			case "RGBA16_FLOAT" -> 115;
			case "RGBA32_UINT" -> 123;
			case "RGBA32_SINT" -> 124;
			case "RGBA32_FLOAT" -> 125;
			case "D16_UNORM" -> 250;
			case "D32_FLOAT" -> PIXEL_DEPTH32F;
			// Apple GPUs have no Depth24Unorm_Stencil8.
			case "D24_UNORM_S8_UINT", "D32_FLOAT_S8_UINT" -> PIXEL_DEPTH32F_S8;
			case "S8_UINT" -> PIXEL_STENCIL8;
			default -> throw new UnsupportedOperationException("Metal has no pixel format for " + gpuFormat);
		};
	}

	/** Bytes per texel as Metal stores it (differs from Minecraft's blockSize only for the D24S8 substitution). */
	public static int metalTexelSize(String gpuFormat, int mcBlockSize) {
		return gpuFormat.equals("D24_UNORM_S8_UINT") ? 8 : mcBlockSize;
	}

	public static boolean hasStencil(long pixelFormat) {
		return pixelFormat == PIXEL_DEPTH32F_S8 || pixelFormat == PIXEL_STENCIL8;
	}

	public static boolean hasDepth(long pixelFormat) {
		return pixelFormat == 250 || pixelFormat == PIXEL_DEPTH32F || pixelFormat == PIXEL_DEPTH32F_S8;
	}

	public static long vertexFormat(String gpuFormat) {
		return switch (gpuFormat) {
			case "R8_UNORM" -> 47;
			case "R8_SNORM" -> 48;
			case "R8_UINT" -> 45;
			case "R8_SINT" -> 46;
			case "RG8_UNORM" -> 7;
			case "RG8_SNORM" -> 10;
			case "RG8_UINT" -> 1;
			case "RG8_SINT" -> 4;
			case "RGB8_UNORM" -> 8;
			case "RGB8_SNORM" -> 11;
			case "RGB8_UINT" -> 2;
			case "RGB8_SINT" -> 5;
			case "RGBA8_UNORM" -> 9;
			case "RGBA8_SNORM" -> 12;
			case "RGBA8_UINT" -> 3;
			case "RGBA8_SINT" -> 6;
			case "R16_UNORM" -> 51;
			case "R16_SNORM" -> 52;
			case "R16_UINT" -> 49;
			case "R16_SINT" -> 50;
			case "RG16_UNORM" -> 19;
			case "RG16_SNORM" -> 22;
			case "RG16_UINT" -> 13;
			case "RG16_SINT" -> 16;
			case "RGB16_UNORM" -> 20;
			case "RGB16_SNORM" -> 23;
			case "RGB16_UINT" -> 14;
			case "RGB16_SINT" -> 17;
			case "RGBA16_UNORM" -> 21;
			case "RGBA16_SNORM" -> 24;
			case "RGBA16_UINT" -> 15;
			case "RGBA16_SINT" -> 18;
			case "R16_FLOAT" -> 53;
			case "RG16_FLOAT" -> 25;
			case "RGB16_FLOAT" -> 26;
			case "RGBA16_FLOAT" -> 27;
			case "R32_FLOAT" -> 28;
			case "RG32_FLOAT" -> 29;
			case "RGB32_FLOAT" -> 30;
			case "RGBA32_FLOAT" -> 31;
			case "R32_SINT" -> 32;
			case "RG32_SINT" -> 33;
			case "RGB32_SINT" -> 34;
			case "RGBA32_SINT" -> 35;
			case "R32_UINT" -> 36;
			case "RG32_UINT" -> 37;
			case "RGB32_UINT" -> 38;
			case "RGBA32_UINT" -> 39;
			case "RGB10A2_UNORM" -> 41;
			case "RG11B10_FLOAT" -> 54;
			default -> throw new UnsupportedOperationException("Metal has no vertex format for " + gpuFormat);
		};
	}

	public static long blendFactor(String f) {
		return switch (f) {
			case "ZERO" -> 0;
			case "ONE" -> 1;
			case "SRC_COLOR" -> 2;
			case "ONE_MINUS_SRC_COLOR" -> 3;
			case "SRC_ALPHA" -> 4;
			case "ONE_MINUS_SRC_ALPHA" -> 5;
			case "DST_COLOR" -> 6;
			case "ONE_MINUS_DST_COLOR" -> 7;
			case "DST_ALPHA" -> 8;
			case "ONE_MINUS_DST_ALPHA" -> 9;
			case "SRC_ALPHA_SATURATE" -> 10;
			case "CONSTANT_COLOR" -> 11;
			case "ONE_MINUS_CONSTANT_COLOR" -> 12;
			case "CONSTANT_ALPHA" -> 13;
			case "ONE_MINUS_CONSTANT_ALPHA" -> 14;
			default -> throw new IllegalArgumentException(f);
		};
	}

	public static long blendOp(String op) {
		return switch (op) {
			case "ADD" -> 0;
			case "SUBTRACT" -> 1;
			case "REVERSE_SUBTRACT" -> 2;
			case "MIN" -> 3;
			case "MAX" -> 4;
			default -> throw new IllegalArgumentException(op);
		};
	}

	public static long compare(String op) {
		return switch (op) {
			case "NEVER_PASS" -> 0;
			case "LESS_THAN" -> 1;
			case "EQUAL" -> 2;
			case "LESS_THAN_OR_EQUAL" -> 3;
			case "GREATER_THAN" -> 4;
			case "NOT_EQUAL" -> 5;
			case "GREATER_THAN_OR_EQUAL" -> 6;
			case "ALWAYS_PASS" -> 7;
			default -> throw new IllegalArgumentException(op);
		};
	}

	/** Minecraft write mask is R=1 G=2 B=4 A=8; Metal's is R=8 G=4 B=2 A=1. */
	public static long writeMask(int mcMask) {
		return ((mcMask & 1) != 0 ? 8 : 0) | ((mcMask & 2) != 0 ? 4 : 0) | ((mcMask & 4) != 0 ? 2 : 0) | ((mcMask & 8) != 0 ? 1 : 0);
	}

	/** LINES and QUADS are expanded into triangles by Minecraft's index buffers and shaders, same as the Vulkan backend. */
	public static long primitive(String topology) {
		return switch (topology) {
			case "POINTS" -> PRIM_POINT;
			case "DEBUG_LINES" -> PRIM_LINE;
			case "DEBUG_LINE_STRIP" -> PRIM_LINE_STRIP;
			case "TRIANGLE_STRIP", "LINE_STRIP" -> PRIM_TRIANGLE_STRIP; // LINE_STRIP: 1.21.6-1.21.10, expanded quads like LINES
			case "LINES", "TRIANGLES", "QUADS", "TRIANGLE_FAN" -> PRIM_TRIANGLE;
			default -> throw new IllegalArgumentException(topology);
		};
	}

	public static long addressMode(String mode) {
		return switch (mode) {
			case "REPEAT" -> 2;
			case "CLAMP_TO_EDGE" -> 0;
			case "MIRRORED_REPEAT" -> 3;
			default -> throw new IllegalArgumentException(mode);
		};
	}

	public static long filter(String mode) {
		return mode.equals("LINEAR") ? 1 : 0;
	}
}
