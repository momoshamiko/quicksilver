// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E2 differential on the compat lowerings the corpus does not cover (W4-B's review): the alpha test's served
 * reference, the quad corner behind position and texcoord 0 by their 1.17+ names, the texture matrices with the
 * lightmap, the Inverse and Transpose matrices in the fragment stage, matrix varyings, a compute kernel with a buffer
 * block, an integer output, and a gbuffers program with compatibility attributes and texture matrices read from a real
 * vertex buffer.
 */
@Tag("gpu")
final class PrinterLoweringGpuTest {

	private static final int W = 64, H = 48;
	private static final int FRAME_SLOT = 3;

	/** MTLVertexFormat ids (MetalConst's switch): float2, float3, float4 and uchar components, normalized. */
	private static final int VF_FLOAT2 = 29, VF_FLOAT3 = 30, VF_UCHAR2N = 7, VF_UCHAR4N = 9;

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text, lines(text));
	}

	private static DifferentialGpu.Lib libOf(ProgramTranslation t) {
		return new DifferentialGpu.Lib(t.msl(), t.vertexEntry(), t.msl(), t.fragmentEntry());
	}

	private static DifferentialGpu.Lib libOf(StandardRoad.Program p) {
		StandardRoad.StageMsl v = p.stage(Stage.VERTEX), f = p.stage(Stage.FRAGMENT);
		return new DifferentialGpu.Lib(v.msl(), v.entry(), f.msl(), f.entry());
	}

	private static void assertAgrees(byte[] ours, byte[] theirs, int format, String what) {
		DifferentialDiff d = DifferentialDiff.of(format, ours, theirs);
		assertTrue(d.passing() >= 0.999, what + ": " + d);
	}

	private static final String QUAD_VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	/** The alpha test's reference from the schema when the pack does not declare it (the W4-B review's item). */
	@Test
	void alphaTestReferenceFromTheSchemaAgrees() {
		String fs = """
			#version 120
			uniform sampler2D tex;
			varying vec2 uv;
			void main() {
				vec4 c = texture2D(tex, uv);
				gl_FragData[0] = c;
			}
			""";
		ProgramRequest r = new ProgramRequest("alpha-ref", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, QUAD_VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, SamplerState.linear())),
			List.of(), List.of(), new UniformSchema(List.of(new UniformBlock("frame", FRAME_SLOT,
				List.of(new UniformField("alphaTestRef", "float", 0))))),
			new AlphaTest("GREATER", "alphaTestRef"), false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		Map<Integer, byte[]> blocks = Map.of(FRAME_SLOT, DifferentialGpu.uniformBytes(r.uniforms(), FRAME_SLOT, 7));
		Map<Integer, DifferentialGpu.Tex> textures = Map.of(0,
			DifferentialGpu.randomTexture(DifferentialGpu.RGBA16_FLOAT, 2, W, H, 1, 11));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, textures, blocks);
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, textures, blocks);
			assertAgrees(a[0], b[0], formats[0], "alpha reference");
		}
	}

	/** The quad's corner feeds vaPosition and vaUV0, as it feeds gl_Vertex and gl_MultiTexCoord0. */
	@Test
	void quadCornerFeedsVaPositionAndVaUV0() {
		String vs = """
			#version 450
			in vec4 vaPosition;
			in vec2 vaUV0;
			out vec2 uv;
			out vec4 p;
			void main() {
				gl_Position = vaPosition;
				uv = vaUV0;
				p = vaPosition;
			}
			""";
		String fs = """
			#version 450
			in vec2 uv;
			in vec4 p;
			out vec4 color;
			void main() {
				color = vec4(uv, p.zw) * 0.5 + vec4(0.25);
			}
			""";
		ProgramRequest r = new ProgramRequest("corner", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), Map.of());
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), Map.of());
			assertAgrees(a[0], b[0], formats[0], "quad corner");
		}
	}

	/** gl_TextureMatrix: [0] from the schema, [1] the lightmap matrix, [2] the identity. */
	@Test
	void textureMatricesIncludingTheLightmapAgree() {
		String vs = """
			#version 120
			varying vec4 tm0;
			varying vec4 lm;
			varying vec4 id;
			void main() {
				gl_Position = ftransform();
				tm0 = gl_TextureMatrix[0] * vec4(0.25, 0.5, 0.75, 1.0);
				lm = gl_TextureMatrix[1] * vec4(0.25, 0.5, 0.75, 1.0);
				id = gl_TextureMatrix[2] * vec4(0.1, 0.2, 0.3, 1.0);
			}
			""";
		String fs = """
			#version 120
			varying vec4 tm0;
			varying vec4 lm;
			varying vec4 id;
			void main() {
				gl_FragData[0] = tm0 * 0.2 + lm * 0.3 + id * 0.5;
			}
			""";
		ProgramRequest r = new ProgramRequest("texmat", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			new UniformSchema(List.of(new UniformBlock("frame", FRAME_SLOT, List.of(
				new UniformField("textureMatrix", "mat4", 0))))), null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		Map<Integer, byte[]> blocks = Map.of(FRAME_SLOT, DifferentialGpu.uniformBytes(r.uniforms(), FRAME_SLOT, 5));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), blocks);
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), blocks);
			assertAgrees(a[0], b[0], formats[0], "texture matrices");
		}
	}

	/** The Inverse and Transpose compatibility matrices are served in the fragment stage too. */
	@Test
	void inverseAndTransposeMatricesInTheFragmentStageAgree() {
		String fs = """
			#version 120
			varying vec2 uv;
			void main() {
				vec4 a = gl_ProjectionMatrixInverse * vec4(uv, 0.25, 1.0);
				vec4 b = gl_ModelViewMatrixInverseTranspose * vec4(uv, 0.5, 1.0);
				gl_FragData[0] = a * 0.5 + b * 0.25 + vec4(uv * 0.125, 0.0, 1.0);
			}
			""";
		ProgramRequest r = new ProgramRequest("inverse", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, QUAD_VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), Map.of());
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), Map.of());
			assertAgrees(a[0], b[0], formats[0], "inverse and transpose");
		}
	}

	/** A matrix varying takes one location per column, so the varyings after it read their own values. */
	@Test
	void matrixVaryingsAgree() {
		String vs = """
			#version 450
			out mat4 m;
			out vec4 v;
			out vec2 uv;
			void main() {
				vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(c * 2.0 - 1.0, 0.25, 1.0);
				uv = c * 0.5;
				m = mat4(vec4(uv, 0.1, 0.2), vec4(uv.yx, 0.3, 0.4), vec4(vec2(0.5, 0.6), uv), vec4(uv * uv, 0.7, 0.8));
				v = vec4(uv, 0.9, 1.0);
			}
			""";
		String fs = """
			#version 450
			in mat4 m;
			in vec4 v;
			in vec2 uv;
			out vec4 color;
			void main() {
				color = m[0] + m[1] * 0.5 + m[2] * 0.25 + m[3] * 0.125 + v * 0.0625 + vec4(uv * 0.5, 0.0, 0.0);
			}
			""";
		ProgramRequest r = new ProgramRequest("matvarying", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), Map.of());
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), Map.of());
			assertAgrees(a[0], b[0], formats[0], "matrix varyings");
		}
	}

	/** A compute kernel with a buffer block (the corpus's programs have only images and uniform blocks). */
	@Test
	void computeKernelWithABufferBlockAgrees() {
		String cs = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			layout(rgba16f) uniform image2D img;
			layout(std430) buffer Data {
				vec4 slots[4];
			};
			uniform float scale;
			void main() {
				ivec2 p = ivec2(gl_GlobalInvocationID.xy);
				imageStore(img, p, slots[p.x % 4] * scale + vec4(float(p.y) * (1.0 / 96.0)));
			}
			""";
		ProgramRequest r = new ProgramRequest("compute-buffer", ProgramRequest.Family.COMPUTE,
			List.of(stage(Stage.COMPUTE, cs)), null, List.of(), List.of(),
			List.of(new ImageBinding("img", 0)), List.of(new BufferBinding("Data", 1)),
			new UniformSchema(List.of(new UniformBlock("frame", 0, List.of(new UniformField("scale", "float", 0))))),
			null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		byte[] slots = new byte[64];
		new java.util.Random(9).nextBytes(slots);
		ByteBuffer bb = ByteBuffer.wrap(slots).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 16; i++) {
			bb.putFloat(4 * i, 0.05f + 0.9f * new java.util.Random(i).nextFloat());
		}
		Map<Integer, byte[]> blocks = Map.of(0, DifferentialGpu.uniformBytes(r.uniforms(), 0, 3), 1, slots);
		Map<Integer, DifferentialGpu.Tex> images = Map.of(0,
			DifferentialGpu.randomTexture(DifferentialGpu.RGBA16_FLOAT, 2, W, H, 1, 1));
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.runCompute(ours.msl(), ours.computeEntry(), W / 8, H / 8, 8, 8, images, blocks);
			StandardRoad.StageMsl c = std.stage(Stage.COMPUTE);
			byte[][] b = gpu.runCompute(c.msl(), c.entry(), W / 8, H / 8, 8, 8, images, blocks);
			assertAgrees(a[0], b[0], DifferentialGpu.RGBA16_FLOAT, "compute buffer");
		}
	}

	/**
	 * A gbuffers program with compatibility attributes and texture matrices: three real vertices from a vertex buffer,
	 * the lightmap matrix on gl_MultiTexCoord1 and the layout's gl_MultiTexCoord2.
	 */
	@Test
	void gbuffersAttributesAndTextureMatricesAgree() {
		String vs = """
			#version 120
			varying vec4 col;
			varying vec2 uv;
			varying vec2 lm;
			varying vec2 mid;
			void main() {
				gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				col = gl_Color;
				uv = gl_MultiTexCoord0.xy;
				lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
				mid = gl_MultiTexCoord2.xy;
			}
			""";
		String fs = """
			#version 120
			uniform sampler2D tex;
			varying vec4 col;
			varying vec2 uv;
			varying vec2 lm;
			varying vec2 mid;
			void main() {
				gl_FragData[0] = texture2D(tex, uv) * col + vec4(lm * 0.25, 0.0, 1.0) + vec4(mid * 0.125, 0.0, 0.0);
			}
			""";
		// One interleaved buffer: float3 position, uchar4 normalized color, float2 texcoord, uchar2 normalized
		// lightmap, float2 texcoord 2. The triangle covers the viewport.
		int stride = 52;
		ByteBuffer vertices = ByteBuffer.allocate(3 * stride).order(ByteOrder.LITTLE_ENDIAN);
		float[][] pos = {{-1, -1}, {3, -1}, {-1, 3}};
		byte[][] color = {{32, 96, 60, -1}, {-1, -128, 0, -1}, {64, -56, 100, -1}};
		float[][] uv = {{0, 0}, {2, 0}, {0, 2}};
		byte[][] lmByte = {{0, 0}, {-1, -1}, {-128, 64}};
		float[][] mid = {{0.25f, 0.5f}, {0.75f, 0.125f}, {0.5f, 0.875f}};
		for (int i = 0; i < 3; i++) {
			vertices.putFloat(pos[i][0]).putFloat(pos[i][1]).putFloat(0.5f);
			vertices.put(color[i]);
			vertices.putFloat(uv[i][0]).putFloat(uv[i][1]);
			vertices.put(lmByte[i]);
			vertices.putFloat(mid[i][0]).putFloat(mid[i][1]);
		}
		VertexLayout layout = VertexLayout.of(
			new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
			new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, 12),
			new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 16),
			new VertexAttribute(Semantic.LIGHTMAP, "uchar2Normalized", 0, 24),
			new VertexAttribute(Semantic.TEXCOORD2, "float2", 0, 28));
		ProgramRequest r = new ProgramRequest("gbuffers-attrs", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), layout,
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, SamplerState.linear())),
			List.of(), List.of(), new UniformSchema(List.of(new UniformBlock("frame", FRAME_SLOT, List.of(
				new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64),
				new UniformField("textureMatrix", "mat4", 128))))),
			null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		// The identity matrices, so gl_Position is the vertex itself (both roads flip y and map z afterwards).
		byte[] blocks = DifferentialGpu.uniformBytes(r.uniforms(), FRAME_SLOT, 5);
		ByteBuffer id = ByteBuffer.wrap(blocks).order(ByteOrder.LITTLE_ENDIAN);
		for (int m = 0; m < 2; m++) {
			for (int c = 0; c < 4; c++) {
				id.putFloat((m == 0 ? 0 : 64) + 16 * c, c == 0 ? 1f : 0f);
			}
		}
		Map<Integer, DifferentialGpu.Tex> textures = Map.of(0,
			DifferentialGpu.randomTexture(DifferentialGpu.RGBA16_FLOAT, 2, W, H, 1, 11));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = renderWithVertices(gpu, libOf(ours), formats, vertices.array(), stride, textures,
				Map.of(FRAME_SLOT, blocks));
			byte[][] b = renderWithVertices(gpu, libOf(std), formats, vertices.array(), stride, textures,
				Map.of(FRAME_SLOT, blocks));
			assertAgrees(a[0], b[0], formats[0], "gbuffers attributes");
		}
	}

	/** An integer output takes an integer attachment: a uvec4 output to R32Uint, compared exactly. */
	@Test
	void integerTargetAgrees() {
		String fs = """
			#version 450
			in vec2 uv;
			layout(location = 0) out uvec4 outColor;
			void main() {
				outColor = uvec4(uint(gl_FragCoord.x) * 3u + uint(gl_FragCoord.y) * 7u + uint(uv.x * 255.0));
			}
			""";
		String vs = """
			#version 450
			out vec2 uv;
			void main() {
				vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(c * 2.0 - 1.0, 0.25, 1.0);
				uv = c * 0.5;
			}
			""";
		ProgramRequest r = new ProgramRequest("integer-target", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 1)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		try (dev.mcms.shaders.metal.HeadlessGpu gpu = new dev.mcms.shaders.metal.HeadlessGpu()) {
			byte[] a = gpu.renderFullscreen(ours.msl(), ours.vertexEntry(), ours.fragmentEntry(),
				new int[] {dev.mcms.shaders.metal.HeadlessGpu.R32_UINT}, W, H, null, new byte[16], null)[0];
			StandardRoad.StageMsl v = std.stage(Stage.VERTEX), f = std.stage(Stage.FRAGMENT);
			byte[] b = gpu.renderFullscreen(v.msl() + "\n" + f.msl(), v.entry(), f.entry(),
				new int[] {dev.mcms.shaders.metal.HeadlessGpu.R32_UINT}, W, H, null, new byte[16], null)[0];
			assertArrayEquals(a, b, "the integer target must match exactly");
		}
	}

	/**
	 * A scalar integer output takes a multi-component integer attachment: the printer must pad with uvec4/ivec2 (GLSL
	 * has no uint4/int2 constructors), compared exactly on an RGBA32Uint target, and truncated to two components on an
	 * RG32Uint one.
	 */
	@Test
	void scalarIntegerOutputPaddedToAMultiComponentIntegerTarget() {
		String fs = """
			#version 450
			in vec2 uv;
			layout(location = 0) out uint outColor;
			void main() {
				outColor = uint(gl_FragCoord.x) * 3u + uint(gl_FragCoord.y) * 7u + uint(uv.x * 255.0);
			}
			""";
		String vs = """
			#version 450
			out vec2 uv;
			void main() {
				vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(c * 2.0 - 1.0, 0.25, 1.0);
				uv = c * 0.5;
			}
			""";
		for (int components : new int[] {4, 2}) {
			ProgramRequest r = new ProgramRequest("integer-pad-" + components, ProgramRequest.Family.FULL_SCREEN,
				List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
				List.of(new ColorTarget(0, 0, components)), List.of(), List.of(), List.of(), UniformSchema.none(),
				null, false);
			ProgramTranslation ours = TestSupport.translateOk(r);
			StandardRoad.Program std = StandardRoad.compile(r);
			assertTrue(std.ok(), String.valueOf(std.errors()));
			int format = components == 4 ? dev.mcms.shaders.metal.HeadlessGpu.RGBA32_UINT
				: dev.mcms.shaders.metal.HeadlessGpu.RG32_UINT;
			try (DifferentialGpu gpu = new DifferentialGpu()) {
				byte[] a = gpu.renderFullScreen(libOf(ours), new int[] {format}, W, H, Map.of(), Map.of())[0];
				byte[] b = gpu.renderFullScreen(libOf(std), new int[] {format}, W, H, Map.of(), Map.of())[0];
				assertArrayEquals(a, b, "the padded integer target (" + components + " components) must match exactly");
			}
		}
	}

	// ----- a render with a real vertex buffer and vertex attributes --------------------------------------------------

	/**
	 * Draws three vertices of one buffer through both libraries' pipelines: a pipeline descriptor with the vertex
	 * layout's buffer, its attributes and the target formats, the buffer bound at index 0 and the blocks at their slots.
	 */
	private static byte[][] renderWithVertices(DifferentialGpu gpu, DifferentialGpu.Lib lib, int[] formats,
			byte[] vertexData, int stride, Map<Integer, DifferentialGpu.Tex> textures, Map<Integer, byte[]> blocks) {
		long ctx = MetalBridge.createHeadlessContext();
		long enc = MetalBridge.createHeadlessEncoder(ctx);
		ShaderNative.Compiled vlib = ShaderNative.libraryNew(ctx, lib.vertexSource(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		ShaderNative.Compiled flib = ShaderNative.libraryNew(ctx, lib.fragmentSource(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		if (!vlib.ok() || !flib.ok()) {
			throw new IllegalStateException("Metal refused a library: " + (vlib.ok() ? flib.error() : vlib.error()));
		}
		// The layout of the gbuffers test above: five attributes of one interleaved buffer.
		int[][] attributes = {{0, 0, 0, VF_FLOAT3}, {1, 0, 12, VF_UCHAR4N}, {2, 0, 16, VF_FLOAT2},
			{3, 0, 24, VF_UCHAR2N}, {4, 0, 28, VF_FLOAT2}};
		int[] descriptor = new int[3 + 3 + 1 + attributes.length * 4 + formats.length * 9 + 2];
		int i = 0;
		descriptor[i++] = 1;  // one vertex buffer
		descriptor[i++] = 0;  // its index
		descriptor[i++] = stride;
		descriptor[i++] = 0;  // per-vertex step
		descriptor[i++] = attributes.length;
		for (int[] a : attributes) {
			descriptor[i++] = a[0];
			descriptor[i++] = a[1];
			descriptor[i++] = a[2];
			descriptor[i++] = a[3];
		}
		descriptor[i++] = formats.length;
		for (int format : formats) {
			descriptor[i++] = format;
			descriptor[i++] = 15;  // write mask all
			descriptor[i++] = 0;  // blending off
			i += 6;
		}
		descriptor[i++] = 0;  // no depth
		descriptor[i] = 3;  // triangle topology
		long pso = MetalBridge.pipelineNew(ctx, vlib.library(), lib.vertexEntry(), flib.library(),
			lib.fragmentEntry(), descriptor);
		if (pso == 0) {
			throw new IllegalStateException("pipeline refused");
		}
		long vertexBuffer = MetalBridge.newBuffer(ctx, vertexData.length);
		java.lang.foreign.MemorySegment.ofAddress(MetalBridge.bufferContents(vertexBuffer))
			.reinterpret(vertexData.length).copyFrom(java.lang.foreign.MemorySegment.ofArray(vertexData));
		long[] targets = new long[formats.length];
		List<Long> handles = new java.util.ArrayList<>();
		try {
			for (int k = 0; k < formats.length; k++) {
				targets[k] = ShaderNative.textureNew(ctx, formats[k], 2, W, H, 1, 1, 4 | 1, 2);
			}
			MetalBridge.renderBegin(enc, targets, new float[][] {{0, 0, 0, 0}}, 0, false, 0, W, H);
			MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, 3);
			MetalBridge.renderBuffer(enc, 0, vertexBuffer, 0);
			for (Map.Entry<Integer, byte[]> b : new TreeMap<>(blocks).entrySet()) {
				MetalBridge.renderBytes(enc, b.getKey(), b.getValue());
			}
			for (Map.Entry<Integer, DifferentialGpu.Tex> t : new TreeMap<>(textures).entrySet()) {
				long handle = upload(gpu, ctx, t.getValue());
				handles.add(handle);
				MetalBridge.renderTexture(enc, t.getKey(), handle);
			}
			MetalBridge.renderDraw(enc, 3, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			byte[][] out = new byte[formats.length][];
			for (int k = 0; k < formats.length; k++) {
				out[k] = readback(gpu, enc, ctx, targets[k], DifferentialGpu.bytesPerPixel(formats[k]));
			}
			return out;
		} finally {
			handles.forEach(MetalBridge::release);
			MetalBridge.release(vertexBuffer);
			for (long t : targets) {
				MetalBridge.release(t);
			}
			MetalBridge.release(pso);
			MetalBridge.release(flib.library());
			MetalBridge.release(vlib.library());
		}
	}

	private static long upload(DifferentialGpu gpu, long ctx, DifferentialGpu.Tex t) {
		long handle = ShaderNative.textureNew(ctx, t.format(), t.type(), t.width(), t.height(), t.depth(), 1,
			1 | ShaderNative.USAGE_SHADER_WRITE, 2);
		long row = (long) t.width() * DifferentialGpu.bytesPerPixel(t.format());
		if (!ShaderNative.textureUpload(ctx, handle, t.texels(), row, 0, 0, 0, 0, 0, 0, t.width(), t.height(),
			t.depth())) {
			throw new IllegalStateException("texture upload failed");
		}
		return handle;
	}

	private static byte[] readback(DifferentialGpu gpu, long enc, long ctx, long texture, int bpp) {
		long size = (long) W * H * bpp;
		long buffer = MetalBridge.newBuffer(ctx, size);
		try {
			MetalBridge.readTexture(enc, texture, W, H, bpp, buffer);
			MetalBridge.commitAndWait(enc);
			return java.lang.foreign.MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(size)
				.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
		} finally {
			MetalBridge.release(buffer);
		}
	}
}
