// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.metal.MetalBridge;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The clouds prologue against the game's own shader (plan.md E4): the game's {@code clouds.vsh} (26.3) and a pack-style
 * {@code gbuffers_clouds} program with {@link ProgramRequest.CloudsPrologue} draw the same points from the same
 * CloudFaces texels and CloudInfo block, and every written pixel agrees in colour, normal and clip position. A Java
 * port of the game's formula pins the absolute placement.
 *
 * <p>The game's shader text is read from the 26.3 jar on the test classpath; the test's preprocessing splices its
 * {@code #include} files, drops the {@code #extension} line and renames {@code gl_VertexIndex} to {@code gl_VertexID}
 * (GLSL 4.60 names them as synonyms) — no game code is copied into this file.
 */
@Tag("gpu")
class CloudsPrologueGpuTest {


	private static final int W = 512, H = 512;
	private static final int VERTICES = 32;  // 8 quads, every vertex id once (the pulling is a pure function of the id)
	private static final float[] CLOUD_COLOR = {0.8f, 0.9f, 1.0f, 0.6f};
	private static final float[] CLOUD_OFFSET = {0.26f, -0.1f, 0.5f};  // no coordinate sits on a pixel-centre boundary
	private static final float[] CELL_SIZE = {0.021f, 0.011f, 0.023f};  // small cells keep every point inside NDC
	private static final float SENTINEL = -2f;
	private static final int RGBA32F = HeadlessGpu.RGBA32_FLOAT;  // raw MTLPixelFormat 125; 65 is a packed 16-bit format, whose readback garbles the sentinel

	/** One quad of CloudFaces: cellX, cellZ and the packed direction and flags, as CloudRenderer writes them. */
	private record Quad(int cellX, int cellZ, int dirAndFlags) {
	}

	/** The cells sit two apart and even, so the extra-bit faces' shifted cells still hold no other quad's corner. */
	private static final List<Quad> QUADS = List.of(
		new Quad(0, 0, 0),                     // bottom face
		new Quad(2, 0, 1),                     // top face
		new Quad(4, 2, 1 | (1 << 5)),          // top face, useTopColor
		new Quad(6, 4, 2 | (1 << 4)),          // north face, inside face
		new Quad(8, 6, 3),                     // south face
		new Quad(10, 8, 4 | (1 << 6)),         // west face, extra Z bit
		new Quad(12, 10, 5 | (1 << 7)),        // east face, extra X bit
		new Quad(14, 12, 0 | (1 << 4) | (1 << 5) | (1 << 6) | (1 << 7)));

	private static final int INFO_SLOT = 22, FACES_SLOT = 30;

	@Test
	void packProgramAgreesWithTheGameShader() throws IOException {
		float[][] cloudInfo = {CLOUD_COLOR, CLOUD_OFFSET, CELL_SIZE};
		byte[] faces = facesBytes();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			long facesTexture = ShaderNative.textureBufferNew(ctx, HeadlessGpu.R8_SINT, QUADS.size() * 3, faces, 1);
			assertTrue(facesTexture != 0);
			try {
				float[][] a = draw(gpu, packTranslation(), Map.of(0, matsBytes(), INFO_SLOT, infoBytes(cloudInfo)),
					FACES_SLOT, facesTexture);
				float[][] b = draw(gpu, gameTranslation(), Map.of(0, infoBytes(cloudInfo), 1, dynamicTransformsBytes(),
					2, projectionBytes(), 3, fogBytes()), 0, facesTexture);
				assertEquals(3, a.length);
				assertEquals(3, b.length);
				Map<Long, float[]> expected = expectedPixels();
				for (int t = 0; t < 3; t++) {
					Map<Long, float[]> seenA = written(a[t]);
					Map<Long, float[]> seenB = written(b[t]);
					assertEquals(expected.keySet(), seenA.keySet(), "target " + t + " pack coverage differs (drawn " + seenA.size() + " pixels, expected " + expected.size() + "; the comparison harness clears to " + SENTINEL + " and the readback holds " + a[t][0] + ")");
					assertEquals(expected.keySet(), seenB.keySet(), "target " + t + " game coverage differs");
					for (var e : expected.entrySet()) {
						float[] exp = e.getValue();
						for (int c = 0; c < 4; c++) {
							assertClose(exp[t * 4 + c], seenA.get(e.getKey())[c], "pack target " + t + " channel " + c);
						}
						if (t == 0) {  // the game shader's own colour output
							for (int c = 0; c < 4; c++) {
								assertClose(exp[c], seenB.get(e.getKey())[c], "game colour channel " + c);
							}
						} else if (t == 1) {  // its distance output is the length of the pulled position
							float d = exp[12];  // the pulled world position's length
							for (int c = 0; c < 3; c++) {
								assertClose(d, seenB.get(e.getKey())[c], "game distance channel " + c);
							}
							assertClose(1f, seenB.get(e.getKey())[3], "game distance w");
						}
					}
				}
			} finally {
				MetalBridge.release(facesTexture);
			}
		}
	}

	// --- the two programs ---

	private static ProgramTranslation packTranslation() {
		String vs = "#version 330\n"
			+ "out vec4 vColor;\nout vec3 vNormal;\nout vec4 vPos;\n"
			+ "void main() {\n"
			+ "\tvColor = gl_Color;\n\tvNormal = gl_Normal;\n\tvPos = ftransform();\n\tgl_Position = ftransform();\n"
			+ "\tgl_PointSize = 1.0;\n"
			+ "}\n";
		String fs = "#version 330\nin vec4 vColor;\nin vec3 vNormal;\nin vec4 vPos;\n"
			+ "layout(location = 0) out vec4 oColor;\n"
			+ "layout(location = 1) out vec4 oPos;\n"
			+ "layout(location = 2) out vec4 oNormal;\n"
			+ "void main() { oColor = vColor; oPos = vPos; oNormal = vec4(vNormal, 0.0); }\n";
		return translate(vs, fs, new VertexLayout(List.of(), false, null), List.of(), List.of(), true, null, null,
			new ProgramRequest.CloudsPrologue(INFO_SLOT, FACES_SLOT), true);
	}

	private static ProgramTranslation gameTranslation() throws IOException {
		String vs = gameShader();
		String fs = "#version 330\n"
			+ "layout(location = 0) in float vertexDistance;\n"
			+ "layout(location = 1) in vec4 vertexColor;\n"
			+ "layout(location = 0) out vec4 oColor;\n"
			+ "layout(location = 1) out vec4 oPos;\n"
			+ "layout(location = 2) out vec4 oNormal;\n"
			+ "void main() { oColor = vertexColor; oPos = vec4(vec3(vertexDistance), 1.0); oNormal = vec4(0.5); }\n";
		return translate(vs, fs, new VertexLayout(List.of(), false, null),
			List.of(new TextureBinding("CloudFaces", 0, ProgramRequest.TextureKind.BUFFER, -1, null, -1)),
			List.of(new BufferBinding("CloudInfo", 0), new BufferBinding("DynamicTransforms", 1),
				new BufferBinding("Projection", 2), new BufferBinding("Fog", 3)),
			false, null, null, null, false);
	}

	private static ProgramTranslation translate(String vs, String fs, VertexLayout layout,
			List<TextureBinding> textures, List<BufferBinding> buffers, boolean ftransform,
			ProgramRequest.PerVertexIds ids, ProgramRequest.OverlayColor overlay,
			ProgramRequest.CloudsPrologue clouds, boolean points) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		// The matrices ftransform reads: identity model-view and the projection below.
		UniformSchema mats = new UniformSchema(List.of(new UniformBlock("QsMats", 0, List.of(
			new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64)))));
		return Translator.translate(new ProgramRequest("clouds", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, fs, fl)),
			layout, List.of(new ColorTarget(0, -1, 4), new ColorTarget(1, -1, 4), new ColorTarget(2, -1, 4)),
			textures, List.of(), buffers, ftransform ? mats : UniformSchema.none(), null, points, ids, overlay,
			clouds));
	}

	/** The game's clouds.vsh with its includes spliced (read from the 26.3 jar on the classpath). */
	private static String gameShader() throws IOException {
		String text = resource("assets/minecraft/shaders/core/clouds.vsh");
		StringBuilder out = new StringBuilder();
		for (String line : text.split("\n", -1)) {
			if (line.startsWith("#extension")) {
				continue;  // separate shader objects is the game's pipeline spelling, not a GLSL construct here
			}
			if (line.startsWith("#include")) {
				String name = line.substring(line.indexOf('<') + 1, line.lastIndexOf('>')).replace("minecraft:", "");
				out.append(resource("assets/minecraft/shaders/include/" + name, true)).append('\n');
				continue;
			}
			out.append(line.replace("gl_VertexIndex", "gl_VertexID")).append('\n');
		}
		return out.toString();
	}

	private static String resource(String name) throws IOException {
		return resource(name, false);
	}

	/** {@code guard} drops an include file's include-guard directives, which the engine's preprocessor resolves. */
	private static String resource(String name, boolean guard) throws IOException {
		try (InputStream in = CloudsPrologueGpuTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IOException("game asset not on the test classpath: " + name);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			String text = out.toString(java.nio.charset.StandardCharsets.UTF_8);
			if (!guard) {
				return text;
			}
			return text.lines().filter(l -> !l.startsWith("#ifndef") && !l.startsWith("#define")
				&& !l.startsWith("#endif")).reduce((a, b) -> a + "\n" + b).orElse("");
		}
	}

	// --- inputs ---

	/** The game's packing (CloudRenderer.encodeFace, javap 2026-11-14): three bytes per quad, one R8_SINT texel each
	 * (cellX >> 1, cellZ >> 1, direction and flags with cellX's and cellZ's low bits in bits 7 and 6). */
	private static byte[] facesBytes() {
		ByteBuffer b = ByteBuffer.allocate(QUADS.size() * 3);
		for (Quad q : QUADS) {
			b.put((byte) (q.cellX() >> 1)).put((byte) (q.cellZ() >> 1))
				.put((byte) (q.dirAndFlags() | ((q.cellX() & 1) << 7) | ((q.cellZ() & 1) << 6)));
		}
		return b.array();
	}

	/** The game's CloudInfo block, std140 as Std140Builder writes it: vec4, vec3, vec3. */
	private static byte[] infoBytes(float[][] cloudInfo) {
		ByteBuffer b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : cloudInfo[0]) {
			b.putFloat(v);
		}
		for (int i = 1; i <= 2; i++) {
			for (float v : cloudInfo[i]) {
				b.putFloat(v);
			}
			b.putFloat(0f);  // std140 vec3 padding
		}
		return b.array();
	}

	private static byte[] matsBytes() {
		return floats(modelView(), projection());
	}

	private static byte[] floats(float[]... rows) {
		ByteBuffer b = ByteBuffer.allocate(rows.length * 64).order(ByteOrder.LITTLE_ENDIAN);
		for (float[] row : rows) {
			for (float v : row) {
				b.putFloat(v);
			}
		}
		return b.array();
	}

	/**
	 * A model-view rotated 30 degrees about x, so z separates on screen too (a quad's four corners differ in x and z;
	 * with the identity model-view two corners of a flat face land on one pixel).
	 */
	private static float[] modelView() {
		float c = 0.8660254f, s = 0.5f;
		return new float[] {1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1};
	}

	private static float[] identity() {
		return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	private static float[] projection() {
		return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1};  // GL -1..1 depth, like the game's
	}

	private static byte[] dynamicTransformsBytes() {
		return floats(modelView(), identity(), new float[4], new float[3]);  // ModelViewMat, TextureMat, rest unused
	}

	private static byte[] projectionBytes() {
		return floats(projection());
	}

	private static byte[] fogBytes() {
		return new byte[48];  // the vertex shader's fog function reads no Fog field
	}

	// --- the Java port of clouds.vsh (position, colour, normal per vertex id) ---

	private static final int[] PACKED = {0xF03CC3, 0x6666F0, 0xC3F066};
	private static final float[] FACE_GREY = {0.7f, 1.0f, 0.8f, 0.8f, 0.9f, 0.9f};
	private static final float[][] FACE_NORMAL = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	private record Vertex(float[] clip, float[] color, float[] normal) {
	}

	private static Vertex gameVertex(int id) {
		int quadVertex = id % 4;
		Quad q = QUADS.get(id / 4);
		int dir = q.dirAndFlags() & 7;
		int flags = q.dirAndFlags() | ((q.cellX() & 1) << 7) | ((q.cellZ() & 1) << 6);  // the packed byte facesBytes writes
		int cellX = ((q.cellX() >> 1) << 1) | ((flags & 0x80) >> 7);
		int cellZ = ((q.cellZ() >> 1) << 1) | ((flags & 0x40) >> 6);
		int vi = dir * 4 + (((q.dirAndFlags() & 0x10) != 0) ? 3 - quadVertex : quadVertex);
		float[] p = new float[3];
		for (int c = 0; c < 3; c++) {
			p[c] = ((PACKED[c] >> vi) & 1) * CELL_SIZE[c] + (c == 0 ? cellX : c == 2 ? cellZ : 0) * CELL_SIZE[c]
				+ CLOUD_OFFSET[c];
		}
		float grey = (q.dirAndFlags() & 0x20) != 0 ? 1.0f : FACE_GREY[dir];
		float[] color = new float[4];
		for (int c = 0; c < 3; c++) {  // the game multiplies the face colour's rgb (alpha 1) by CloudColor
			color[c] = grey * CLOUD_COLOR[c];
		}
		color[3] = CLOUD_COLOR[3];
		return new Vertex(new float[] {p[0], p[1], p[2], 1f}, color, FACE_NORMAL[dir]);
	}

	/** Pixel (row-major key) to {oColor.x..w, oPos.x..w, oNormal.x}: memory row 0 is clip y -1, pixel c covers
	 * [(c) / W, (c + 1) / W] of the -1..1 range, so col = floor((x + 1) / 2 * W). */
	private static Map<Long, float[]> expectedPixels() {
		Map<Long, float[]> out = new HashMap<>();
		for (int id = 0; id < VERTICES; id++) {
			Vertex v = gameVertex(id);
			// gl_Position = P * M * pos; the model-view rotates about x, the projection only scales z.
			float[] m = modelView();
			float ry = m[5] * v.clip()[1] + m[9] * v.clip()[2];
			float rz = m[6] * v.clip()[1] + m[10] * v.clip()[2];
			float[] clip = {v.clip()[0], ry, rz * 0.5f + 0.5f, 1f};
			int col = (int) ((clip[0] + 1f) * 0.5f * W);
			int row = (int) ((clip[1] + 1f) * 0.5f * H);
			long key = (long) row * W + col;
			assertTrue(col >= 0 && col < W && row >= 0 && row < H, "vertex " + id + " outside the viewport");
			float[] cp = v.clip();
			float world = (float) Math.sqrt(cp[0] * cp[0] + cp[1] * cp[1] + cp[2] * cp[2]);  // fog_spherical_distance
			float[] old = out.put(key, new float[] {v.color()[0], v.color()[1], v.color()[2], v.color()[3],
				clip[0], clip[1], clip[2], clip[3], v.normal()[0], v.normal()[1], v.normal()[2], 0f, world});
			assertTrue(old == null, "two vertices land on one pixel; pick smaller cells");
		}
		return out;
	}

	// --- drawing ---


	private float[][] draw(HeadlessGpu gpu, ProgramTranslation t, Map<Integer, byte[]> buffers, int facesSlot,
			long facesTexture) {
		assertTrue(t.ok(), t.diagnostics().toString());
		long ctx = gpu.context(), enc = gpu.encoder();
		ShaderNative.Compiled lib = ShaderNative.libraryNew(ctx, t.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(lib.ok(), lib.error());
		int[] targets = {RGBA32F, RGBA32F, RGBA32F};
		int[] descriptor = new int[3 + targets.length * 9 + 2];
		int i = 0;
		descriptor[i++] = 0;  // vertexBufferCount
		descriptor[i++] = 0;  // attributeCount
		descriptor[i++] = targets.length;
		for (int t2 : targets) {
			descriptor[i++] = t2;
			descriptor[i++] = 15;  // write mask all
			i += 7;  // blending off
		}
		descriptor[i++] = 0;  // no depth
		descriptor[i++] = 1;  // MTLPrimitiveTopologyClassPoint
		long pso = 0;
		long[] color = new long[targets.length];
		List<Long> owned = new ArrayList<>();
		try {
			pso = MetalBridge.pipelineNew(ctx, lib.library(), t.vertexEntry(), lib.library(), t.fragmentEntry(),
				descriptor);
			for (int c = 0; c < targets.length; c++) {
				color[c] = ShaderNative.textureNew(ctx, RGBA32F, ShaderNative.TYPE_2D, W, H, 1, 1, 5,
					ShaderNative.STORAGE_PRIVATE);  // render target | shader read, like the engine's own targets
				owned.add(color[c]);
			}
			float[][] clears = new float[targets.length][];
			for (int c = 0; c < targets.length; c++) {
				clears[c] = new float[] {SENTINEL, SENTINEL, SENTINEL, SENTINEL};
			}
			MetalBridge.renderBegin(enc, color, clears, 0, false, 0, W, H);
			MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, 0 /* MTLPrimitiveTypePoint */);
			for (var e : buffers.entrySet()) {
				MetalBridge.renderBytes(enc, e.getKey(), e.getValue());
			}
			MetalBridge.renderTexture(enc, facesSlot, facesTexture);
			MetalBridge.renderDraw(enc, VERTICES, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			float[][] out = new float[targets.length][];
			for (int c = 0; c < targets.length; c++) {
				out[c] = pixels(gpu, color[c]);
			}
			return out;
		} finally {
			for (long h : owned) {
				MetalBridge.release(h);
			}
			if (pso != 0) {
				MetalBridge.release(pso);
			}
			MetalBridge.release(lib.library());
		}
	}

	private static float[] pixels(HeadlessGpu gpu, long texture) {
		MetalBridge.commitAndWait(gpu.encoder());  // the render pass must finish before getBytes sees the clear and the points
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, 16);
		float[] out = new float[W * H * 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
		return out;
	}

	/** Pixel key to its rgba value, for the pixels the sentinel clear does not cover. */
	private static Map<Long, float[]> written(float[] target) {
		Map<Long, float[]> out = new HashMap<>();
		for (int p = 0; p < W * H; p++) {
			if (Math.abs(target[p * 4] - SENTINEL) > 0.05f) {
				out.put((long) p, new float[] {target[p * 4], target[p * 4 + 1], target[p * 4 + 2], target[p * 4 + 3]});
			}
		}
		return out;
	}

	private static void assertClose(float expected, float seen, String what) {
		assertTrue(Math.abs(expected - seen) < 2e-3f, what + ": expected " + expected + " saw " + seen);
	}
}
