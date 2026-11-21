// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.VertexPrologue;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The extended terrain format's GPU decode (plan.md E4's test list): vertices {@link TerrainEncoder} wrote for known
 * quads, including a split translucent quad whose extension came through the copy, are decoded on the Metal device by
 * a translated pack program that carries {@link TerrainLayout}'s layout, and every pack attribute comes out as the
 * encoded words' arithmetic says: the position with the region offset (push constants, slot 26) added and the camera
 * offset subtracted; the UVs as {@code q / 32768 + bias * u_TexCoordShrink}; the light minus the bias of 8; the
 * {@code mc_Entity} pair (id, and 1 for a fluid, -1 otherwise); the sprite centre; the tangent; the normal; and
 * {@code at_midBlock}'s offsets with the emission in {@code .w}. The globals block sits at slot 21 with the shrink at
 * beta.5's offset (184).
 *
 * <p>Expected values are computed in Java from the same encoded words the GPU read (not from the floats that went in),
 * so the test proves the decode, not the encoder (the encoder's bits are proven against Sodium's own by
 * {@link TerrainEncoderTest}). The {@code x127} decodes of the signed-normalised bytes carry one rounding each way and
 * get 1e-4.
 */
@Tag("gpu")
final class TerrainDecodeGpuTest {

	/** u_CameraOffset's byte offset in the globals block (beta.5's own globals.glsl, ADR 0007's hand-computed value). */
	private static final long CAMERA_AT = 144;
	/** u_TexCoordShrink's byte offset on beta.5 (beta.1 has it at 168; the engine reads it from Sodium's globals.glsl). */
	private static final long SHRINK_AT = 184;
	/** The shrink the test writes: not the constant 1/32768, so a decode that ignores it fails. */
	private static final float[] SHRINK = { 0.375f / 32768.0f, 0.5f / 32768.0f };
	/** The vertex data's buffer index (the backend's VERTEX_BUFFER_BASE, clear of the engine's own slots). */
	private static final int VERTEX_DATA = 27;
	/** Raw MTLVertexFormat of the nine attributes, in TerrainLayout's order (MetalPipeline's table, MTLVertexFormat.h). */
	private static final int[] MTL_FORMATS = { 37, 9, 13, 3, 13, 12, 12, 12, 29 };
	private static final float[] REGION = { 1234.5f, -678.25f, 9.0f };
	private static final float[] CAMERA = { -96.5f, 32.25f, -1040.0f };

	private static final String VS = """
		#version 330
		in vec4 vaPosition;
		in vec4 vaColor;
		in vec2 vaUV0;
		in vec2 vaUV2;
		in vec4 mc_Entity;
		in vec4 at_tangent;
		in vec3 vaNormal;
		in vec4 at_midBlock;
		in vec2 mc_midTexCoord;
		out vec4 vPosition;
		out vec4 vUvAndSprite;
		out vec4 vLightAndEntity;
		out vec4 vColor;
		out vec4 vTangent;
		out vec4 vNormal;
		out vec4 vMidBlock;
		void main() {
			gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
			gl_PointSize = 1.0;
			vPosition = vaPosition;
			vUvAndSprite = vec4(vaUV0, mc_midTexCoord);
			vLightAndEntity = vec4(vaUV2, mc_Entity.xy);
			vColor = vaColor;
			vTangent = at_tangent;
			vNormal = vec4(vaNormal, 0.0);
			vMidBlock = at_midBlock;
		}
		""";

	private static final String FS = """
		#version 450
		in vec4 vPosition;
		in vec4 vUvAndSprite;
		in vec4 vLightAndEntity;
		in vec4 vColor;
		in vec4 vTangent;
		in vec4 vNormal;
		in vec4 vMidBlock;
		layout(location = 0) out vec4 o0;
		layout(location = 1) out vec4 o1;
		layout(location = 2) out vec4 o2;
		layout(location = 3) out vec4 o3;
		layout(location = 4) out vec4 o4;
		layout(location = 5) out vec4 o5;
		layout(location = 6) out vec4 o6;
		void main() {
			o0 = vPosition;
			o1 = vUvAndSprite;
			o2 = vLightAndEntity;
			o3 = vColor;
			o4 = vTangent;
			o5 = vNormal;
			o6 = vMidBlock;
		}
		""";

	@Test
	void theEncodedQuadsDecodeToTheirPackAttributesOnTheDevice() {
		// Two quads: a solid block with awkward values everywhere, and a fluid whose extension came through the copy
		// that a split makes (base fields through Vertex.copyVertexTo, the extension through the engine's copy hook).
		TerrainVertex[] solid = quad(new float[][] { { -7.5f, 0.0f, 15.5f }, { 0.25f, -8.0f, 24.0f },
			{ 8.0f, 31.75f, -0.125f }, { 16.0f, 16.0f, 16.0f } },
			new float[][] { { 0.25f, 0.25f }, { 0.75f, 0.25f }, { 0.75f, 0.75f }, { 0.25f, 0.75f } });
		for (int i = 0; i < 4; i++) {
			solid[i].blockId = 8;
			solid[i].fluid = false;
			solid[i].emission = 11;
			solid[i].centreX = 3.5f;
			solid[i].centreY = 9.5f;
			solid[i].centreZ = 4.5f;
			solid[i].midU = 0.5f;
			solid[i].midV = 0.25f;
		}
		int solidSection = 190;
		TerrainVertex[] translucent = quad(new float[][] { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 1, 0 }, { 0, 1, 0 } },
			new float[][] { { 0.1f, 0.9f }, { 0.9f, 0.9f }, { 0.9f, 0.1f }, { 0.1f, 0.1f } });
		for (int i = 0; i < 4; i++) {
			translucent[i].blockId = 5;
			translucent[i].fluid = true;
			translucent[i].emission = 3;
			translucent[i].centreX = 7.5f;
			translucent[i].centreY = 1.5f;
			translucent[i].centreZ = 3.25f;
			translucent[i].midU = 0.75f;
			translucent[i].midV = 0.5f;
		}
		TerrainVertex[] split = { new TerrainVertex(), new TerrainVertex(), new TerrainVertex(), new TerrainVertex() };
		for (int i = 0; i < 4; i++) {
			Vertex.copyVertexTo(translucent[i], split[i]);
			TerrainVertex.copyExtension(translucent[i], split[i]);
		}
		int splitSection = 41;

		byte[] vertices;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment memory = arena.allocate(TerrainVertexType.STRIDE * 8L);
			new TerrainEncoder().write(memory.address(), 0x25, solid, solidSection);
			new TerrainEncoder().write(memory.address() + 4L * TerrainVertexType.STRIDE, 0x25, split, splitSection);
			vertices = memory.toArray(ValueLayout.JAVA_BYTE);
		}

		ProgramTranslation program = translate();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, program.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			if (!library.ok()) {
				throw new IllegalStateException("translation failed to compile: " + library.error() + "\n" + program.msl());
			}
			long vertexBuffer = 0;
			long globalsBuffer = 0;
			long regionBuffer = 0;
			long pipeline = 0;
			long[] targets = new long[7];
			try {
				vertexBuffer = upload(ctx, vertices);
				regionBuffer = upload(ctx, regionBlock());
				globalsBuffer = upload(ctx, globalsBlock());
				pipeline = MetalBridge.pipelineNew(ctx, library.library(), program.vertexEntry(), library.library(),
					program.fragmentEntry(), descriptor());
				for (int i = 0; i < targets.length; i++) {
					targets[i] = ShaderNative.textureNew(ctx, HeadlessGpu.RGBA32_FLOAT, ShaderNative.TYPE_2D, 1, 1, 1, 1,
						ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET, ShaderNative.STORAGE_PRIVATE);
				}
				for (int vertex = 0; vertex < 8; vertex++) {
					float[][] out = drawVertex(gpu, enc, ctx, targets, pipeline, vertexBuffer, globalsBuffer,
						regionBuffer, vertex);
					assertVertex(out, vertices, vertex, program.msl());
				}
			} finally {
				MetalBridge.release(library.library());
				MetalBridge.release(pipeline);
				for (long target : targets) {
					if (target != 0) {
						MetalBridge.release(target);
					}
				}
				MetalBridge.release(regionBuffer);
				MetalBridge.release(globalsBuffer);
				MetalBridge.release(vertexBuffer);
			}
		}
	}

	private static ProgramTranslation translate() {
		ProgramRequest.VertexLayout layout = new ProgramRequest.VertexLayout(TerrainLayout.layout(SHRINK_AT).attributes(),
			false, new VertexPrologue(TerrainEngine.REGION_SLOT, 0, TerrainUniforms.GLOBALS_SLOT, CAMERA_AT));
		List<ColorTarget> outputs = IntStream.range(0, 7).mapToObj(i -> new ColorTarget(i, i, 4)).toList();
		ProgramRequest request = new ProgramRequest("terrain-decode", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), layout, outputs, List.of(), List.of(),
			List.of(), ProgramRequest.UniformSchema.none(), null, true);
		ProgramTranslation translation = Translator.translate(request);
		if (!translation.ok()) {
			throw new AssertionError("translation failed: " + translation.diagnostics());
		}
		return translation;
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		return new ProgramRequest.StageSource(stage, text,
			IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test", i)).toList());
	}

	/** The int array mc_pipeline_new parses: one 44-byte vertex buffer, the nine attributes, seven RGBA32F targets. */
	private static int[] descriptor() {
		int[] offsets = { TerrainVertexType.A_POSITION, TerrainVertexType.A_COLOR, TerrainVertexType.A_TEXCOORD,
			TerrainVertexType.A_LIGHT_AND_DATA, TerrainVertexType.A_ENTITY, TerrainVertexType.A_TANGENT,
			TerrainVertexType.A_NORMAL, TerrainVertexType.A_MID_BLOCK, TerrainVertexType.A_MID_TEXCOORD };
		int[] descriptor = new int[1 + 3 + 1 + 9 * 4 + 1 + 7 * 9 + 2];
		int i = 0;
		descriptor[i++] = 1;  // one vertex buffer
		descriptor[i++] = VERTEX_DATA;
		descriptor[i++] = TerrainVertexType.STRIDE;
		descriptor[i++] = 0;  // step rate: per vertex
		descriptor[i++] = 9;  // the nine attributes, in the layout's order
		for (int a = 0; a < 9; a++) {
			descriptor[i++] = a;
			descriptor[i++] = VERTEX_DATA;
			descriptor[i++] = offsets[a];
			descriptor[i++] = MTL_FORMATS[a];
		}
		descriptor[i++] = 7;  // the seven colour targets
		for (int t = 0; t < 7; t++) {
			descriptor[i++] = HeadlessGpu.RGBA32_FLOAT;
			descriptor[i++] = 15;  // write all components
			descriptor[i++] = 0;   // blending off
			descriptor[i++] = 1;
			descriptor[i++] = 0;
			descriptor[i++] = 0;
			descriptor[i++] = 1;
			descriptor[i++] = 0;
			descriptor[i++] = 0;
		}
		descriptor[i++] = 0;  // no depth
		descriptor[i++] = 1;  // MTLPrimitiveTopologyClassPoint
		return descriptor;
	}

	/** Draws one vertex as a one-pixel point and reads the seven targets back. */
	private static float[][] drawVertex(HeadlessGpu gpu, long enc, long ctx, long[] targets, long pipeline,
			long vertexBuffer, long globalsBuffer, long regionBuffer, int vertex) {
		float[][] clears = new float[targets.length][];
		Arrays.fill(clears, MetalBridge.DONT_CARE);
		MetalBridge.renderBegin(enc, targets, clears, 0, false, 0, 1, 1);
		MetalBridge.renderPipeline(enc, pipeline, 0, false, false, 0, 0, 0 /* MTLPrimitiveTypePoint */);
		MetalBridge.vertexBuffer(enc, VERTEX_DATA, vertexBuffer, 0);
		MetalBridge.renderBuffer(enc, TerrainUniforms.GLOBALS_SLOT, globalsBuffer, 0);
		// The region offset is bound where the backend binds a region's push constants (the decode reads that slot).
		MetalBridge.renderBuffer(enc, TerrainEngine.REGION_SLOT, regionBuffer, 0);
		MetalBridge.renderDraw(enc, 1, 1, vertex, 0);
		MetalBridge.endEncoders(enc);
		float[][] out = new float[targets.length][4];
		long readback = 0;
		try {
			readback = MetalBridge.newBuffer(ctx, 16);
			for (int t = 0; t < targets.length; t++) {
				MetalBridge.readTexture(enc, targets[t], 1, 1, 16, readback);
				MetalBridge.commitAndWait(enc);
				byte[] bytes = MemorySegment.ofAddress(MetalBridge.bufferContents(readback)).reinterpret(16)
					.toArray(ValueLayout.JAVA_BYTE);
				ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out[t]);
			}
		} finally {
			MetalBridge.release(readback);
		}
		return out;
	}

	private static void assertVertex(float[][] out, byte[] vertices, int vertex, String msl) {
		byte[] v = new byte[TerrainVertexType.STRIDE];
		System.arraycopy(vertices, vertex * TerrainVertexType.STRIDE, v, 0, v.length);
		String where = "vertex " + vertex + " (quad " + (vertex < 4 ? "solid" : "split") + ", corner " + (vertex & 3)
			+ ")\nmsl:\n" + msl + "\n";
		// Position: the 20-bit words de-interleaved, scaled, section-translated, region-added, camera-subtracted.
		int hi = word(v, 0);
		int lo = word(v, 4);
		int sectionWord = word(v, TerrainVertexType.A_LIGHT_AND_DATA) >>> 24;
		int[] shift = { 0, 10, 20 };
		int[] sectionShift = { 5, 0, 2 };
		int[] sectionMask = { 7, 3, 7 };
		for (int c = 0; c < 3; c++) {
			int q = ((hi >> shift[c] & 0x3FF) << 10) | (lo >> shift[c] & 0x3FF);
			float expected = q * (32.0f / 1048576.0f) - 8.0f;
			expected += (sectionWord >> sectionShift[c] & sectionMask[c]) * 16;
			expected = expected + REGION[c] - CAMERA[c];
			assertEquals(expected, out[0][c], 0.0f, where + "position component " + c);
		}
		// UV: q / 32768 + bias * u_TexCoordShrink, the bias from the sign bit (Sodium's chunk_vertex.glsl).
		int tex = word(v, TerrainVertexType.A_TEXCOORD);
		assertEquals(texel(tex & 0xFFFF, SHRINK[0]), out[1][0], 1.0e-7f, where + "u");
		assertEquals(texel(tex >>> 16 & 0xFFFF, SHRINK[1]), out[1][1], 1.0e-7f, where + "v");
		// The sprite centre rides the same target, unconverted.
		assertEquals(floatAt(v, TerrainVertexType.A_MID_TEXCOORD), out[1][2], 0.0f, where + "sprite centre u");
		assertEquals(floatAt(v, TerrainVertexType.A_MID_TEXCOORD + 4), out[1][3], 0.0f, where + "sprite centre v");
		// Light: the two biased bytes minus the bias of 8 (sky first, the encoder's byte order): the light level.
		int data = word(v, TerrainVertexType.A_LIGHT_AND_DATA);
		assertEquals((data & 0xFF) - 8, out[2][0], 0.0f, where + "sky light");
		assertEquals((data >>> 8 & 0xFF) - 8, out[2][1], 0.0f, where + "block light");
		// mc_Entity: the stored id + 1 minus one, and y = 1 for a fluid, -1 otherwise.
		int entity = word(v, TerrainVertexType.A_ENTITY);
		assertEquals((entity & 0xFFFF) - 1, out[2][2], 0.0f, where + "block id");
		assertEquals((entity >>> 16) == 1 ? 1.0f : -1.0f, out[2][3], 0.0f, where + "mc_Entity.y (fluid)");
		// Colour: the four unorm bytes.
		for (int c = 0; c < 4; c++) {
			assertEquals((v[TerrainVertexType.A_COLOR + c] & 0xFF) / 255.0f, out[3][c], 1.0e-6f, where + "colour " + c);
		}
		// Tangent: the snorm bytes as they are (±127/127 is exactly ±1, so the handedness is exact).
		for (int c = 0; c < 4; c++) {
			assertEquals(snorm(v, TerrainVertexType.A_TANGENT + c), out[4][c], 1.0e-6f, where + "tangent " + c);
		}
		// Normal: the three snorm bytes.
		for (int c = 0; c < 3; c++) {
			assertEquals(snorm(v, TerrainVertexType.A_NORMAL + c), out[5][c], 1.0e-6f, where + "normal " + c);
		}
		// at_midBlock: the offsets and the emission in .w, back in 1/64 m units (one rounding each way).
		for (int c = 0; c < 4; c++) {
			assertEquals(snorm(v, TerrainVertexType.A_MID_BLOCK + c) * 127.0f, out[6][c], 1.0e-4f,
				where + "mid block " + c);
		}
	}

	/** {@code q / 32768 + bias * shrink}: the sign bit selects the bias (+1 set, -1 clear), as Sodium's decode does. */
	private static float texel(int encoded, float shrink) {
		float bias = (encoded >>> 15 & 1) == 1 ? 1.0f : -1.0f;
		return (encoded & 0x7FFF) / 32768.0f + bias * shrink;
	}

	private static float snorm(byte[] v, int offset) {
		return v[offset] / 127.0f;
	}

	private static int word(byte[] v, int offset) {
		return Byte.toUnsignedInt(v[offset]) | Byte.toUnsignedInt(v[offset + 1]) << 8
			| Byte.toUnsignedInt(v[offset + 2]) << 16 | v[offset + 3] << 24;
	}

	private static float floatAt(byte[] v, int offset) {
		return Float.intBitsToFloat(word(v, offset));
	}

	private static TerrainVertex[] quad(float[][] positions, float[][] uvs) {
		TerrainVertex[] quad = new TerrainVertex[4];
		for (int i = 0; i < 4; i++) {
			quad[i] = new TerrainVertex();
			quad[i].x = positions[i][0];
			quad[i].y = positions[i][1];
			quad[i].z = positions[i][2];
			quad[i].color = 0xFF80C0E0;
			quad[i].ao = i == 0 ? 0.5f : 1.0f;
			quad[i].u = uvs[i][0];
			quad[i].v = uvs[i][1];
			quad[i].light = 0x00F000A0;
		}
		return quad;
	}

	private static long upload(long ctx, byte[] bytes) {
		long buffer = MetalBridge.newBuffer(ctx, bytes.length);
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
		return buffer;
	}

	private static byte[] regionBlock() {
		byte[] block = new byte[20];
		ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, REGION[0]).putFloat(4, REGION[1])
			.putFloat(8, REGION[2]);
		return block;
	}

	private static byte[] globalsBlock() {
		byte[] block = new byte[256];
		ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putFloat((int) CAMERA_AT, CAMERA[0])
			.putFloat((int) CAMERA_AT + 4, CAMERA[1]).putFloat((int) CAMERA_AT + 8, CAMERA[2])
			.putFloat((int) SHRINK_AT, SHRINK[0]).putFloat((int) SHRINK_AT + 4, SHRINK[1]);
		return block;
	}
}
