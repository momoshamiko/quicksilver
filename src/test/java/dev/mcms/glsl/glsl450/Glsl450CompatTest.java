// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.sema.SemaOptions;
import dev.mcms.glsl.sema.SemanticAnalyzer;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How the printer lowers the compatibility built-ins, measured against the translator's emitter (the contract, W4-B's
 * review): the texture matrices (the lightmap matrix at [1]), the Inverse and Transpose matrices in every stage, the
 * quad corner behind position and texcoord 0, the point size, the alpha test's reference, matrix varyings' locations,
 * the extension texture-function names, and the 1.17+ attribute names.
 */
final class Glsl450CompatTest {

	private static final String QUAD_VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text, lines(text));
	}

	private static Glsl450Program print(ProgramRequest request) {
		Glsl450Program program = Glsl450Printer.print(request);
		assertTrue(program.errors().isEmpty(), String.valueOf(program.errors()));
		return program;
	}

	/** The printed stage must parse and pass the strict checker: the round trip of the printer's text. */
	private static void assertRoundTrip(Glsl450Program.StageText st) {
		var unit = Parser.parse(st.source());
		var sema = SemanticAnalyzer.analyze(unit, SemaOptions.strict(st.stage()));
		assertTrue(sema.ok(), st.stage() + " does not check: " + sema.errors() + "\n" + st.source());
	}

	private static String source(Glsl450Program program, Stage stage) {
		for (Glsl450Program.StageText st : program.stages()) {
			if (st.stage() == stage) {
				assertRoundTrip(st);
				return st.source();
			}
		}
		throw new AssertionError("no " + stage + " stage");
	}

	/** The printed stage's text without the strict round trip: the quad's corner reads Vulkan's gl_VertexIndex, which
	 * the checker does not know. */
	private static String text(Glsl450Program program, Stage stage) {
		for (Glsl450Program.StageText st : program.stages()) {
			if (st.stage() == stage) {
				return st.source();
			}
		}
		throw new AssertionError("no " + stage + " stage");
	}

	private static UniformSchema schema(UniformField... fields) {
		return new UniformSchema(List.of(new UniformBlock("frame", 0, List.of(fields))));
	}

	/** gl_TextureMatrix[1] is the lightmap matrix (scale 1/256, offset 1/32) and [2...] the identity, as the emitter. */
	@Test
	void textureMatrixFillsTheLightmapMatrixAndTheIdentity() {
		String vs = """
			#version 120
			varying vec4 lm;
			varying vec4 id;
			varying vec4 tm;
			void main() {
				gl_Position = ftransform();
				lm = gl_TextureMatrix[1] * vec4(0.5, 0.5, 0.5, 1.0);
				id = gl_TextureMatrix[2] * vec4(0.25);
				tm = gl_TextureMatrix[0] * gl_MultiTexCoord0;
			}
			""";
		String fs = """
			#version 120
			varying vec4 lm;
			varying vec4 id;
			varying vec4 tm;
			void main() {
				gl_FragData[0] = lm + id + tm;
			}
			""";
		ProgramRequest r = new ProgramRequest("texmat", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
				new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 12)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			schema(new UniformField("textureMatrix", "mat4", 0), new UniformField("modelViewMatrix", "mat4", 64),
				new UniformField("projectionMatrix", "mat4", 128)), null, false);
		String text = source(print(r), Stage.VERTEX);
		assertTrue(text.contains("qs_gl_TextureMatrix[0] = qs_textureMatrix"), text);
		assertTrue(text.contains("qs_gl_TextureMatrix[1] = mat4(vec4(0.00390625, 0.0, 0.0, 0.0)"), text);
		assertTrue(text.contains("qs_gl_TextureMatrix[2] = mat4(1.0)"), text);
	}

	/** The Inverse and Transpose matrices are served from the blocks in every stage, as the emitter's compatMatrix. */
	@Test
	void inverseAndTransposeMatricesAreServedInBothStages() {
		String vs = """
			#version 120
			varying vec4 a;
			void main() {
				gl_Position = ftransform();
				a = gl_ModelViewMatrixInverse * gl_ProjectionMatrixTranspose * gl_Vertex;
			}
			""";
		String fs = """
			#version 120
			varying vec4 a;
			void main() {
				gl_FragData[0] = a + gl_ProjectionMatrixInverse * vec4(1.0) + gl_ModelViewProjectionMatrixTranspose[0];
			}
			""";
		ProgramRequest r = new ProgramRequest("inverse", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			schema(new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64),
				new UniformField("textureMatrix", "mat4", 128)),
			null, false);
		String vertex = source(print(r), Stage.VERTEX);
		assertTrue(vertex.contains("inverse(qs_modelViewMatrix)"), vertex);
		assertTrue(vertex.contains("transpose(qs_projectionMatrix)"), vertex);
		String fragment = source(print(r), Stage.FRAGMENT);
		assertTrue(fragment.contains("inverse(qs_projectionMatrix)"), fragment);
		assertTrue(fragment.contains("transpose((qs_projectionMatrix * qs_modelViewMatrix))"), fragment);
	}

	/** The quad's corner also feeds position and texcoord 0 by their 1.17+ names, as the emitter's bindAttribute. */
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
				color = vec4(uv, p.zw);
			}
			""";
		ProgramRequest r = new ProgramRequest("corner", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		String text = text(print(r), Stage.VERTEX);
		assertTrue(text.contains("qs_vaPosition = vec4(qs_corner, 0.0, 1.0)"), text);
		assertTrue(text.contains("qs_vaUV0 = vec2(qs_corner)"), text);
	}

	/** A pipeline that draws points keeps the pack's gl_PointSize; GL's default of 1.0 only when the pack writes none. */
	@Test
	void packPointSizeStandsWhenThePackWritesOne() {
		String writes = """
			#version 120
			void main() {
				gl_Position = ftransform();
				gl_PointSize = 3.0;
			}
			""";
		ProgramRequest with = new ProgramRequest("points", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, writes), stage(Stage.FRAGMENT, "void main() { gl_FragColor = vec4(1.0); }")),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			schema(new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64)),
			null, true);
		assertFalse(source(print(with), Stage.VERTEX).contains("gl_PointSize = 1.0"), "the default must not overwrite");
		String writesNone = """
			#version 120
			void main() {
				gl_Position = ftransform();
			}
			""";
		ProgramRequest without = new ProgramRequest("nopoints", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, writesNone), stage(Stage.FRAGMENT, "void main() { gl_FragColor = vec4(1.0); }")),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			schema(new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64)),
			null, true);
		assertTrue(source(print(without), Stage.VERTEX).contains("gl_PointSize = 1.0"), "the default must be written");
	}

	/** A matrix varying takes one location per column, so the next varying starts after the columns. */
	@Test
	void matrixVaryingsTakeOneLocationPerColumn() {
		String vs = """
			#version 450
			out mat4 m;
			out vec4 v;
			void main() {
				gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
				m = mat4(1.0);
				v = vec4(0.5);
			}
			""";
		String fs = """
			#version 450
			in mat4 m;
			in vec4 v;
			out vec4 color;
			void main() {
				color = m[0] + m[1] + m[2] + m[3] + v;
			}
			""";
		ProgramRequest r = new ProgramRequest("matvarying", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		String vertex = source(print(r), Stage.VERTEX);
		assertTrue(vertex.contains("layout(location = 0) out mat4 u_m"), vertex);
		assertTrue(vertex.contains("layout(location = 4) out vec4 u_v"), vertex);
		String fragment = source(print(r), Stage.FRAGMENT);
		assertTrue(fragment.contains("layout(location = 0) in mat4 u_m"), fragment);
		assertTrue(fragment.contains("layout(location = 4) in vec4 u_v"), fragment);
	}

	/** The alpha test's reference comes from the pack's declaration, else the schema's served field, else a refusal. */
	@Test
	void alphaReferenceComesFromTheSchemaWhenThePackDoesNotDeclareIt() {
		String fs = """
			#version 120
			uniform sampler2D tex;
			varying vec2 uv;
			void main() {
				gl_FragData[0] = texture2D(tex, uv);
			}
			""";
		ProgramRequest served = new ProgramRequest("alphatest", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, QUAD_VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, ProgramRequest.SamplerState.linear())),
			List.of(), List.of(), schema(new UniformField("alphaTestRef", "float", 0)),
			new AlphaTest("GREATER", "alphaTestRef"), false);
		assertTrue(source(print(served), Stage.FRAGMENT).contains("qs_alphaTestRef"));
		ProgramRequest unserved = new ProgramRequest("alphatest-none", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, QUAD_VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, ProgramRequest.SamplerState.linear())),
			List.of(), List.of(), UniformSchema.none(), new AlphaTest("GREATER", "alphaTestRef"), false);
		Glsl450Program refused = Glsl450Printer.print(unserved);
		assertTrue(refused.errors().stream().anyMatch(e -> e.contains("alpha-reference")),
			String.valueOf(refused.errors()));
	}

	/** The extension texture-function names map to the core ones, as W2-C's builtin table maps them. */
	@Test
	void extensionTextureFunctionsAreMapped() {
		String fs = """
			#version 120
			#extension GL_ARB_shader_texture_lod : require
			uniform sampler2D tex;
			uniform sampler2DShadow shadow;
			varying vec2 uv;
			void main() {
				vec4 a = texture2DGradARB(tex, uv, dFdx(uv), dFdy(uv));
				vec4 b = vec4(texelFetch2D(tex, ivec2(4, 5), 0));
				vec4 c = shadow2D(shadow, vec3(uv, 0.5));
				gl_FragData[0] = a + b + c;
			}
			""";
		ProgramRequest r = new ProgramRequest("legacyext", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, QUAD_VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, ProgramRequest.SamplerState.linear()),
				TextureBinding.constexpr("shadow", 1, TextureKind.COMPARE,
					new ProgramRequest.SamplerState(ProgramRequest.Filter.NEAREST, null, ProgramRequest.Wrap.CLAMP_TO_EDGE,
						true, false))),
			List.of(), List.of(), UniformSchema.none(), null, false);
		String text = source(print(r), Stage.FRAGMENT);
		assertTrue(text.contains("textureGrad("), text);
		assertTrue(text.contains("texelFetch("), text);
		assertTrue(text.contains("vec4(texture("), text);
		assertFalse(text.contains("ARB") || text.contains("texelFetch2D"), text);
	}

	/**
	 * An undeclared projectionMatrix reads the quad's own matrix and is declared exactly once: the round trip catches
	 * the double declaration Complementary's gbuffers_line drove (W4-B's review).
	 */
	@Test
	void undeclaredQuadMatrixIsDeclaredOnceAndServed() {
		String vs = """
			#version 450
			out vec2 uv;
			void main() {
				gl_Position = projectionMatrix * modelViewMatrix * vec4(0.0, 0.0, 0.0, 1.0);
				uv = vec2(0.5);
			}
			""";
		String fs = """
			#version 450
			in vec2 uv;
			out vec4 color;
			void main() {
				color = vec4(uv, 0.0, 1.0);
			}
			""";
		ProgramRequest r = new ProgramRequest("quadline", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		String text = text(print(r), Stage.VERTEX);
		assertTrue(text.indexOf("qs_projectionMatrix") != text.lastIndexOf("qs_projectionMatrix"), text);
	}

	/** gl_MultiTexCoord2 and dhMaterialId are attributes the layout serves, like the emitter's table. */
	@Test
	void multiTexCoord2AndDhMaterialIdAreServedFromTheLayout() {
		String vs = """
			#version 120
			varying vec4 v;
			void main() {
				gl_Position = ftransform();
				v = gl_MultiTexCoord2 + vec4(dhMaterialId);
			}
			""";
		String fs = """
			#version 120
			varying vec4 v;
			void main() {
				gl_FragData[0] = v;
			}
			""";
		ProgramRequest r = new ProgramRequest("dhattr", ProgramRequest.Family.DISTANT_HORIZONS,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
				new VertexAttribute(Semantic.TEXCOORD2, "float2", 0, 12),
				new VertexAttribute(Semantic.DH_MATERIAL_ID, "uint1", 0, 20)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			schema(new UniformField("modelViewMatrix", "mat4", 0),
				new UniformField("projectionMatrix", "mat4", 64)), null, false);
		String text = source(print(r), Stage.VERTEX);
		assertTrue(text.contains("qs_gl_MultiTexCoord2 = vec4(vec2(qs_a1)"), text);
		assertTrue(text.contains("layout(location = 2) in uint qs_a2"), text);
		assertTrue(text.contains("int qs_dhMaterialId;"), text);
	}
}
