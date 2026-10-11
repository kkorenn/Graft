package dev.graft.v1_21_11.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.graft.metal.GlShaders;
import dev.graft.metal.MetalCommands;
import dev.graft.metal.MetalDevice;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.MetalRenderPass;
import dev.graft.metal.Mtl;
import dev.graft.metal.ObjC;
import dev.graft.metal.ShaderTranslator;
import dev.graft.v1_21_11.MetalGpuDevice;
import dev.graft.v1_21_11.MetalSampler;
import dev.graft.v1_21_11.MetalTexture;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sodium 0.8's render device (its own small OpenGL object model: buffers, vertex arrays, programs with uniforms set by
 * location, uniform-block binding points, texture units, glMultiDrawElementsBaseVertex) implemented on Metal. Sodium's
 * GL calls are redirected here by the mixins in this package; ids are the "GL names" Sodium stores in its GlObjects.
 */
public final class SodiumMetal {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int GL_ARRAY_BUFFER = 0x8892;
	private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
	private static final int GL_VERTEX_SHADER = 0x8B31;

	private SodiumMetal() {
	}

	private static int nextId = 1;

	public static int newId() {
		return nextId++;
	}

	/**
	 * API: programs owned by another mod (Graft Shaders shader packs). A Sodium GlProgram whose GL name such a mod hands
	 * out is routed there: uniform lookups and writes, binding, and the terrain multi-draw.
	 */
	public interface External {
		boolean owns(int program);

		void use(int program);

		void delete(int program);

		int uniformLocation(int program, String name);

		int uniformBlockIndex(int program, String name);

		void uniformBlockBinding(int program, int index, int bindingPoint);

		void uniformInt(int location, int value);

		void uniformFloats(int location, float[] values);

		void uniformMatrix(int location, FloatBuffer columnMajor, int size);

		/** The terrain multi-draw: pass is open on Sodium's target; draw[i] = {indexCount, firstIndex, baseVertex}. */
		void draw(MetalRenderPass pass, RenderPipeline pipeline, List<Attr> attributes, long vertexBuffer, long indexBuffer, boolean u16, int[][] draws);

		/**
		 * Allocation-free form of the terrain multi-draw: Sodium's native arrays as passed to glMultiDrawElementsBaseVertex
		 * (int counts, long byte offsets into the index buffer, int base vertices). Defaults to the int[][] form.
		 */
		default void draw(MetalRenderPass pass, RenderPipeline pipeline, List<Attr> attributes, long vertexBuffer, long indexBuffer, boolean u16,
						  int drawCount, long pCount, long pOffset, long pBaseVertex) {
			int[][] draws = new int[drawCount][];
			for (int i = 0; i < drawCount; i++) {
				draws[i] = new int[]{MemoryUtil.memGetInt(pCount + i * 4L), (int)(MemoryUtil.memGetLong(pOffset + i * 8L) / (u16 ? 2 : 4)), MemoryUtil.memGetInt(pBaseVertex + i * 4L)};
			}
			draw(pass, pipeline, attributes, vertexBuffer, indexBuffer, u16, draws);
		}
	}

	public static External external;
	/**
	 * API: a mod's program for a terrain pass (Graft Shaders shader packs; it also binds the mod's framebuffer), or null
	 * for Sodium's own. Called from ShaderChunkRenderer.begin.
	 */
	public static java.util.function.Function<net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass,
		net.caffeinemc.mods.sodium.client.gl.shader.GlProgram<net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface>> terrainProgram;
	private static int currentExternal;

	/** API: the MTLBuffer behind a Sodium GL buffer name (0 if none). */
	public static long buffer(int id) {
		Buf b = BUFFERS.get(id);
		return b != null ? b.mtl : 0L;
	}

	/** API: the GL buffer name bound to a uniform-buffer binding point. */
	public static int uniformBuffer(int bindingPoint) {
		return UBO[bindingPoint];
	}

	private static boolean isExternal(int program) {
		return !PROGRAMS.containsKey(program) && external != null && external.owns(program);
	}

	private static MetalGpuDevice device() {
		return MetalGpuDevice.current();
	}

	private static MetalCommands commands() {
		return device().commands();
	}

	// ---- buffers -------------------------------------------------------------------------------------------------

	private static final class Buf {
		long mtl;
		long size;
	}

	private static final Int2ObjectOpenHashMap<Buf> BUFFERS = new Int2ObjectOpenHashMap<>();

	private static Buf buf(int id) {
		return BUFFERS.computeIfAbsent(id, k -> new Buf());
	}

	/** glBufferData / glBufferStorage: fresh storage (the GPU may still read the old one; command buffers retain it). */
	public static void allocate(int id, long size, long data) {
		Buf b = buf(id);
		if (b.mtl != 0L) {
			ObjC.release(b.mtl);
		}
		b.mtl = device().metal().newBuffer(Math.max(16, size));
		b.size = size;
		if (data != 0L && size > 0) {
			MemoryUtil.memCopy(data, MetalDevice.contents(b.mtl), size);
		}
	}

	/** glBufferSubData: ordered with the GPU work around it (staging copy). */
	public static void subData(int id, long offset, long data, long size) {
		MetalCommands.Scratch s = commands().scratch(size);
		MemoryUtil.memCopy(data, s.address(), size);
		commands().copyBuffer(s.buffer(), s.offset(), buf(id).mtl, offset, size);
	}

	public static void copy(int src, int dst, long readOffset, long writeOffset, long bytes) {
		commands().copyBuffer(buf(src).mtl, readOffset, buf(dst).mtl, writeOffset, bytes);
	}

	/** Shared storage: a mapping is the buffer's memory itself (Sodium fences its persistent staging buffers). */
	public static ByteBuffer map(int id, long offset, long length) {
		return MemoryUtil.memByteBuffer(MetalDevice.contents(buf(id).mtl) + offset, (int)length);
	}

	public static void deleteBuffer(int id) {
		Buf b = BUFFERS.remove(id);
		if (b != null && b.mtl != 0L) {
			ObjC.release(b.mtl);
		}
	}

	// ---- vertex arrays -------------------------------------------------------------------------------------------

	public record Attr(int index, int count, int glType, boolean normalized, boolean intType, int stride, int offset, int buffer) {
	}

	private static final class Vao {
		final List<Attr> attrs = new ArrayList<>();
		int indexBuffer;
		/** Derived from attrs, null until needed again after a change. */
		List<Attr> attrsCopy;
		String layoutKey;
		/** Last pipeline used with this layout, for program/passPipeline. */
		Program lastProgram;
		RenderPipeline lastPassPipeline;
		MetalPipeline lastPipeline;
	}

	private static final Int2ObjectOpenHashMap<Vao> VAOS = new Int2ObjectOpenHashMap<>();
	private static int boundVao;
	private static int boundArrayBuffer;

	public static void bindVertexArray(int id) {
		boundVao = id;
	}

	public static void bindBuffer(int target, int id) {
		if (target == GL_ARRAY_BUFFER) {
			boundArrayBuffer = id;
		} else if (target == GL_ELEMENT_ARRAY_BUFFER && boundVao != 0) {
			VAOS.computeIfAbsent(boundVao, k -> new Vao()).indexBuffer = id;
		}
	}

	/** glVertexAttrib(I)Pointer on the bound VAO, sourcing the bound GL_ARRAY_BUFFER. */
	public static void attribute(int index, int count, int glType, boolean normalized, boolean intType, int stride, int offset) {
		Vao vao = VAOS.computeIfAbsent(boundVao, k -> new Vao());
		vao.attrs.removeIf(a -> a.index() == index);
		vao.attrs.add(new Attr(index, count, glType, normalized, intType, stride, offset, boundArrayBuffer));
		vao.attrsCopy = null;
		vao.layoutKey = null;
		vao.lastPipeline = null;
	}

	public static void deleteVertexArray(int id) {
		VAOS.remove(id);
	}

	// ---- shaders & programs --------------------------------------------------------------------------------------

	private static final Map<Integer, String> SHADER_SOURCES = new HashMap<>();
	private static final Map<Integer, Integer> SHADER_TYPES = new HashMap<>();

	public static int createShader(int type) {
		int id = newId();
		SHADER_TYPES.put(id, type);
		return id;
	}

	public static void shaderSource(int shader, CharSequence source) {
		SHADER_SOURCES.put(shader, source.toString());
	}

	public static void deleteShader(int shader) {
		SHADER_SOURCES.remove(shader);
		SHADER_TYPES.remove(shader);
	}

	public static final class Program {
		final String name;
		final List<Integer> shaders = new ArrayList<>();
		final Map<String, Integer> attributes = new HashMap<>();
		final Map<String, Integer> outputs = new HashMap<>();
		GlShaders.Program linked;
		/** location -> uniform name (loose uniforms and samplers). */
		final List<String> locations = new ArrayList<>();
		/** location -> member in the uniform block (null for samplers). */
		GlShaders.Member[] members = new GlShaders.Member[0];
		ShaderTranslator.Kind[] kinds;
		ByteBuffer uniformData;
		boolean uniformsDirty = true;
		/** Metal slot of a uniform block -> GL uniform-buffer binding point (-1: none). */
		int[] blockBindings;
		/** Metal slot of a sampler -> texture unit. */
		int[] samplerUnits;
		/** pass pipeline -> vertex layout key -> pipeline. */
		final Map<RenderPipeline, Map<String, MetalPipeline>> pipelines = new IdentityHashMap<>();

		Program(String name) {
			this.name = name;
		}
	}

	private static final Int2ObjectOpenHashMap<Program> PROGRAMS = new Int2ObjectOpenHashMap<>();
	private static Program current;

	public static int createProgram(String name) {
		int id = newId();
		PROGRAMS.put(id, new Program(name));
		return id;
	}

	public static void attachShader(int program, int shader) {
		PROGRAMS.get(program).shaders.add(shader);
	}

	public static void bindAttribLocation(int program, int index, CharSequence name) {
		PROGRAMS.get(program).attributes.put(name.toString(), index);
	}

	public static void bindFragDataLocation(int program, int index, CharSequence name) {
		PROGRAMS.get(program).outputs.put(name.toString(), index);
	}

	public static void linkProgram(int id) {
		Program p = PROGRAMS.get(id);
		String vs = null;
		String fs = null;
		for (int shader : p.shaders) {
			if (SHADER_TYPES.get(shader) == GL_VERTEX_SHADER) {
				vs = SHADER_SOURCES.get(shader);
			} else {
				fs = SHADER_SOURCES.get(shader);
			}
		}
		p.linked = GlShaders.link(p.name, vs, fs, p.attributes, p.outputs);
		p.locations.addAll(p.linked.uniforms().keySet());
		for (int i = 0; i < p.linked.names().size(); i++) {
			if (p.linked.kinds().get(i) == ShaderTranslator.Kind.SAMPLED_IMAGE) {
				p.locations.add(p.linked.names().get(i));
			}
		}
		p.uniformData = MemoryUtil.memCalloc(Math.max(16, (p.linked.uniformSize() + 15) & ~15));
		p.members = new GlShaders.Member[p.locations.size()];
		for (int i = 0; i < p.members.length; i++) {
			p.members[i] = p.linked.uniforms().get(p.locations.get(i));
		}
		int slots = p.linked.names().size();
		p.kinds = p.linked.kinds().toArray(new ShaderTranslator.Kind[0]);
		p.blockBindings = new int[slots];
		Arrays.fill(p.blockBindings, -1);
		p.samplerUnits = new int[slots];
	}

	public static void useProgram(int id) {
		if (isExternal(id)) {
			current = null;
			currentExternal = id;
			external.use(id);
			return;
		}
		if (currentExternal != 0 && external != null) {
			external.use(0);
		}
		currentExternal = 0;
		current = id == 0 ? null : PROGRAMS.get(id);
	}

	public static void deleteProgram(int id) {
		if (isExternal(id)) {
			external.delete(id);
			return;
		}
		Program p = PROGRAMS.remove(id);
		if (p != null) {
			p.pipelines.values().forEach(m -> m.values().forEach(MetalPipeline::close));
			if (p.uniformData != null) {
				MemoryUtil.memFree(p.uniformData);
			}
		}
	}

	public static int uniformLocation(int program, CharSequence name) {
		if (isExternal(program)) {
			return external.uniformLocation(program, name.toString());
		}
		return PROGRAMS.get(program).locations.indexOf(name.toString());
	}

	public static int uniformBlockIndex(int program, CharSequence name) {
		if (isExternal(program)) {
			return external.uniformBlockIndex(program, name.toString());
		}
		Program p = PROGRAMS.get(program);
		int slot = p.linked.names().indexOf(name.toString());
		return slot >= 0 && p.linked.kinds().get(slot) == ShaderTranslator.Kind.UNIFORM_BUFFER ? slot : -1;
	}

	public static void uniformBlockBinding(int program, int blockIndex, int bindingPoint) {
		if (isExternal(program)) {
			external.uniformBlockBinding(program, blockIndex, bindingPoint);
			return;
		}
		int[] bindings = PROGRAMS.get(program).blockBindings;
		if (blockIndex >= 0 && blockIndex < bindings.length) {
			bindings[blockIndex] = bindingPoint;
		}
	}

	// ---- uniforms (glUniform* on the current program) ------------------------------------------------------------

	private static GlShaders.Member member(int location) {
		if (current == null || location < 0 || location >= current.members.length) {
			return null;
		}
		return current.members[location];
	}

	public static void uniformInt(int location, int value) {
		if (currentExternal != 0) {
			external.uniformInt(location, value);
			return;
		}
		if (current == null || location < 0 || location >= current.members.length) {
			return;
		}
		GlShaders.Member m = current.members[location];
		if (m == null) {
			// sampler uniform: texture unit (for every slot of that name)
			List<String> names = current.linked.names();
			String name = current.locations.get(location);
			for (int slot = 0; slot < names.size(); slot++) {
				if (names.get(slot).equals(name)) {
					current.samplerUnits[slot] = value;
				}
			}
			return;
		}
		current.uniformData.putInt(m.offset(), value);
		current.uniformsDirty = true;
	}

	public static void uniformFloats(int location, float... values) {
		if (currentExternal != 0) {
			external.uniformFloats(location, values);
			return;
		}
		GlShaders.Member m = member(location);
		if (m == null) {
			return;
		}
		for (int i = 0; i < values.length; i++) {
			current.uniformData.putFloat(m.offset() + i * 4, values[i]);
		}
		current.uniformsDirty = true;
	}

	public static void uniformFloats(int location, FloatBuffer values) {
		if (currentExternal != 0) {
			float[] v = new float[values.remaining()];
			values.get(values.position(), v);
			external.uniformFloats(location, v);
			return;
		}
		GlShaders.Member m = member(location);
		if (m == null) {
			return;
		}
		int p = values.position();
		for (int i = 0, n = values.remaining(); i < n; i++) {
			current.uniformData.putFloat(m.offset() + i * 4, values.get(p + i));
		}
		current.uniformsDirty = true;
	}

	/** Column-major 4x4; std140 matrix columns are 16 bytes apart. */
	public static void uniformMatrix4(int location, FloatBuffer values) {
		if (currentExternal != 0) {
			external.uniformMatrix(location, values, 4);
			return;
		}
		GlShaders.Member m = member(location);
		if (m == null) {
			return;
		}
		int p = values.position();
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				current.uniformData.putFloat(m.offset() + c * m.matrixStride() + r * 4, values.get(p + c * 4 + r));
			}
		}
		current.uniformsDirty = true;
	}

	// ---- uniform buffers, textures -------------------------------------------------------------------------------

	private static final int[] UBO = new int[16];
	private static final long[] UNIT_TEXTURE = new long[16];
	private static final long[] UNIT_SAMPLER = new long[16];

	public static void bindUniformBuffer(int bindingPoint, int buffer) {
		UBO[bindingPoint] = buffer;
	}

	public static void bindTexture(int unit, GpuTextureView view, GpuSampler sampler) {
		UNIT_TEXTURE[unit] = ((MetalTexture.View)view).mtl();
		UNIT_SAMPLER[unit] = ((MetalSampler)sampler).mtl();
	}

	// ---- chunk render pass ---------------------------------------------------------------------------------------

	private static MetalRenderPass pass;
	private static RenderPipeline passPipeline;
	private static int passGeneration = -1;
	/** What the last Sodium-program draw left bound on the pass (bindings are redone when any of it changes). */
	private static Program boundProgram;
	private static MetalPipeline boundPipeline;
	private static final long[] BOUND = new long[128];
	private static final long[] BOUND_SAMPLER = new long[128];

	/** ShaderChunkRenderer.begin: a Metal pass on the target (instead of binding its GL framebuffer). */
	public static void beginPass(RenderTarget target, RenderPipeline pipeline) {
		MetalTexture.View color = (MetalTexture.View)target.getColorTextureView();
		MetalTexture.View depth = (MetalTexture.View)target.getDepthTextureView();
		MetalRenderPass.Attachment[] colors = {new MetalRenderPass.Attachment(color.mtl(), color.metalTexture().pixelFormat(), 0, null, Double.NaN)};
		MetalRenderPass.Attachment d = depth != null ? new MetalRenderPass.Attachment(depth.mtl(), depth.metalTexture().pixelFormat(), 0, null, Double.NaN) : null;
		pass = commands().beginRenderPass(colors, d, 0, 0, color.getWidth(0), color.getHeight(0), "sodium terrain");
		passPipeline = pipeline;
		passGeneration = -1;
		boundPipeline = null;
	}

	public static void endPass() {
		if (pass != null) {
			commands().endRenderPass();
			pass = null;
		}
	}

	/** glMultiDrawElementsBaseVertex with the bound VAO and the current program. */
	public static void multiDraw(long pCount, long pOffset, long pBaseVertex, int drawCount, int glIndexType) {
		Vao vao = VAOS.get(boundVao);
		if (pass == null || drawCount == 0 || vao == null || vao.attrs.isEmpty()) {
			return;
		}
		if (currentExternal != 0) {
			if (vao.attrsCopy == null) {
				vao.attrsCopy = List.copyOf(vao.attrs);
			}
			boundPipeline = null; // the external draw binds its own resources
			external.draw(pass, passPipeline, vao.attrsCopy, buf(vao.attrs.get(0).buffer()).mtl, buf(vao.indexBuffer).mtl, glIndexType == 0x1403,
				drawCount, pCount, pOffset, pBaseVertex);
			return;
		}
		if (current == null) {
			return;
		}
		MetalPipeline pipeline = pipelineFor(vao);
		if (pipeline == null) {
			return;
		}
		boolean rebind = pass.generation() != passGeneration || pipeline != boundPipeline || current != boundProgram;
		passGeneration = pass.generation();
		boundPipeline = pipeline;
		boundProgram = current;
		if (pass.pipeline() != pipeline) {
			pass.setPipeline(pipeline);
		}
		pass.setVertexBuffer(0, buf(vao.attrs.get(0).buffer()).mtl, 0L);
		boolean u16 = glIndexType == 0x1403;
		pass.setIndexBuffer(buf(vao.indexBuffer).mtl, u16 ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
		bindResources(rebind);
		int indexSize = u16 ? 2 : 4;
		for (int i = 0; i < drawCount; i++) {
			int count = MemoryUtil.memGetInt(pCount + i * 4L);
			long offset = MemoryUtil.memGetLong(pOffset + i * 8L);
			int baseVertex = MemoryUtil.memGetInt(pBaseVertex + i * 4L);
			pass.drawIndexed(count, 1, (int)(offset / indexSize), baseVertex, 0);
		}
	}

	/** force: the pass's bindings may not be this program's (new encoder, other pipeline or program): bind everything. */
	private static void bindResources(boolean force) {
		Program p = current;
		ShaderTranslator.Kind[] kinds = p.kinds;
		int uniformSlot = p.linked.uniformSlot();
		if (force) {
			Arrays.fill(BOUND, 0L);
			Arrays.fill(BOUND_SAMPLER, 0L);
		}
		for (int slot = 0; slot < kinds.length; slot++) {
			ShaderTranslator.Kind kind = kinds[slot];
			if (slot == uniformSlot) {
				if (p.uniformsDirty || force) {
					int size = p.uniformData.capacity();
					MetalCommands.Scratch s = commands().scratch(size);
					MemoryUtil.memCopy(MemoryUtil.memAddress(p.uniformData), s.address(), size);
					pass.setBuffer(slot, s.buffer(), s.offset());
					p.uniformsDirty = false;
				}
			} else if (kind == ShaderTranslator.Kind.UNIFORM_BUFFER) {
				int point = p.blockBindings[slot];
				int id = point >= 0 ? UBO[point] : 0;
				if (id != 0) {
					long mtl = buf(id).mtl;
					if (slot >= BOUND.length || BOUND[slot] != mtl) {
						pass.setBuffer(slot, mtl, 0L);
						if (slot < BOUND.length) {
							BOUND[slot] = mtl;
						}
					}
				}
			} else if (kind == ShaderTranslator.Kind.SAMPLED_IMAGE) {
				int unit = p.samplerUnits[slot];
				long texture = UNIT_TEXTURE[unit];
				if (texture != 0L) {
					if (slot >= BOUND.length || BOUND[slot] != texture || BOUND_SAMPLER[slot] != UNIT_SAMPLER[unit]) {
						pass.setTexture(slot, texture, UNIT_SAMPLER[unit]);
						if (slot < BOUND.length) {
							BOUND[slot] = texture;
							BOUND_SAMPLER[slot] = UNIT_SAMPLER[unit];
						}
					} else {
						commands().flushClear(texture); // as setTexture would
					}
				}
			}
		}
	}

	private static MetalPipeline pipelineFor(Vao vao) {
		if (vao.lastPipeline != null && vao.lastProgram == current && vao.lastPassPipeline == passPipeline) {
			return vao.lastPipeline;
		}
		if (vao.layoutKey == null) {
			StringBuilder key = new StringBuilder();
			for (Attr a : vao.attrs) {
				key.append('|').append(a.index()).append(',').append(a.count()).append(',').append(a.glType()).append(',').append(a.normalized())
					.append(',').append(a.intType()).append(',').append(a.stride()).append(',').append(a.offset());
			}
			vao.layoutKey = key.toString();
		}
		MetalPipeline pipeline = current.pipelines.computeIfAbsent(passPipeline, k -> new HashMap<>()).computeIfAbsent(vao.layoutKey, k -> {
			try {
				List<MetalPipeline.Attribute> attributes = new ArrayList<>();
				for (Attr a : vao.attrs) {
					attributes.add(new MetalPipeline.Attribute(0, a.index(), a.offset(), Mtl.vertexFormat(vertexFormat(a))));
				}
				return new MetalPipeline(
					device().metal(), current.name, current.linked.vertexMsl(), current.linked.fragmentMsl(), current.linked.kinds(),
					List.of(new MetalPipeline.VertexBuffer(0, vao.attrs.get(0).stride(), 0)), attributes,
					new MetalPipeline.ColorTarget[]{MetalGpuDevice.colorTarget(passPipeline)}, MetalGpuDevice.raster(passPipeline)
				);
			} catch (RuntimeException e) {
				LOGGER.error("Graft: couldn't build Sodium pipeline {}: {}", current.name, e.getMessage());
				return null;
			}
		});
		vao.lastProgram = current;
		vao.lastPassPipeline = passPipeline;
		vao.lastPipeline = pipeline;
		return pipeline;
	}

	/** GL attribute (type, count, normalized, integer) to a Metal vertex format name. */
	public static String vertexFormat(Attr a) {
		String channels = switch (a.count()) {
			case 1 -> "R";
			case 2 -> "RG";
			case 3 -> "RGB";
			default -> "RGBA";
		};
		return switch (a.glType()) {
			case 0x1406 -> channels + "32_FLOAT";
			case 0x140B -> channels + "16_FLOAT";
			case 0x1401 -> channels + "8_" + (a.intType() ? "UINT" : "UNORM");
			case 0x1400 -> channels + "8_" + (a.intType() ? "SINT" : "SNORM");
			case 0x1403 -> channels + "16_" + (a.intType() ? "UINT" : "UNORM");
			case 0x1402 -> channels + "16_" + (a.intType() ? "SINT" : "SNORM");
			case 0x1405 -> channels + "32_UINT";
			case 0x1404 -> channels + "32_SINT";
			default -> throw new IllegalArgumentException("GL vertex type 0x" + Integer.toHexString(a.glType()));
		};
	}

	// ---- fences --------------------------------------------------------------------------------------------------

	public static long fence() {
		return commands().submitIndex();
	}

	public static boolean fenceDone(long index, long timeoutNs) {
		if (index >= commands().submitIndex() && timeoutNs != 0 && !commands().inRenderPass()) {
			commands().submit();
		}
		return commands().awaitCompletion(index, timeoutNs);
	}
}
