// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The printer's output is Vulkan GLSL 4.50 that our own parser and checker accept, with the interface the MSL has. */
final class Glsl450PrinterTest {

	private static final String VS = """
		#version 120
		varying vec2 uv;
		varying vec4 tint;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
			tint = gl_Color;
		}
		""";

	private static final String FS = """
		#version 120
		uniform sampler2D tex;
		uniform float scale;
		varying vec2 uv;
		varying vec4 tint;
		void main() {
			float acc;
			gl_FragData[0] = texture2D(tex, uv) * scale * tint;
			gl_FragData[1] = vec4(acc, 0.0, 0.0, 1.0);
		}
		""";

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static ProgramRequest fullScreen() {
		VertexLayout layout = VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
			new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 12));
		return new ProgramRequest("fullscreen", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, FS, lines(FS))),
			layout, List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, ProgramRequest.SamplerState.linear())),
			List.of(), List.of(),
			new UniformSchema(List.of(new UniformBlock("frame", 0, List.of(new UniformField("scale", "float", 0),
				new UniformField("modelViewMatrix", "mat4", 16),
				new UniformField("projectionMatrix", "mat4", 80))))),
			null, false);
	}

	/** The printed stage must parse and pass the strict checker: the round trip of the printer's text. */
	private static void assertRoundTrip(Glsl450Program.StageText st) {
		var unit = Parser.parse(st.source());
		var sema = SemanticAnalyzer.analyze(unit, SemaOptions.strict(st.stage()));
		assertTrue(sema.ok(), st.stage() + " does not check: " + sema.errors() + "\n" + st.source());
	}

	@Test
	void fullScreenProgramPrintsAsVulkanGlsl450WithExplicitInterfaces() {
		Glsl450Program p = Glsl450Printer.print(fullScreen());
		assertTrue(p.ok(), String.valueOf(p.errors()));
		Glsl450Program.StageText vs = p.stage(Stage.VERTEX);
		Glsl450Program.StageText fs = p.stage(Stage.FRAGMENT);
		assertTrue(vs.source().startsWith("#version 450"));
		assertTrue(vs.source().contains("layout(location = 0) in vec4 qs_a0;"), vs.source());
		assertTrue(vs.source().contains("layout(location = 1) in vec2 qs_a1;"), vs.source());
		assertTrue(fs.source().contains("layout(location = 0) out vec4 qs_c0;"), fs.source());
		assertTrue(fs.source().contains("layout(location = 1) out vec4 qs_c1;"), fs.source());
		assertTrue(fs.source().contains("layout(offset = 0) float qs_scale;"), fs.source());
		assertTrue(fs.source().contains("layout(std140, set = 0, binding = 1000) uniform frame"), fs.source());
		assertTrue(fs.source().contains("sampler2D u_tex"), fs.source());
		assertRoundTrip(vs);
		assertRoundTrip(fs);
	}

	@Test
	void compatibilityBuiltInsAndLegacyCallsAreGone() {
		Glsl450Program p = Glsl450Printer.print(fullScreen());
		Pattern compat = Pattern.compile("(?<!qs_)gl_(Vertex|Color|MultiTexCoord|FragData|ModelView|FrontColor)"
			+ "|texture2D|ftransform");
		for (Glsl450Program.StageText st : p.stages()) {
			assertFalse(compat.matcher(st.source()).find(), st.stage() + " keeps a compatibility name:\n"
				+ st.source());
		}
	}

	@Test
	void varyingsGetOneLocationBothSides() {
		Glsl450Program p = Glsl450Printer.print(fullScreen());
		String vs = p.stage(Stage.VERTEX).source();
		String fs = p.stage(Stage.FRAGMENT).source();
		// Keys sort as tint (location 0) then uv (location 1), the same numbers both stages print.
		assertTrue(vs.contains("location = 0) out vec4 u_tint"), vs);
		assertTrue(vs.contains("location = 1) out vec2 u_uv"), vs);
		assertTrue(fs.contains("location = 0) in vec4 u_tint"), fs);
		assertTrue(fs.contains("location = 1) in vec2 u_uv"), fs);
	}

	@Test
	void resourcesCarryTheirMetalSlotsAndTheTranslatorsBindings() {
		Glsl450Program p = Glsl450Printer.print(fullScreen());
		List<Glsl450Program.ResourceBinding> bindings = p.stage(Stage.FRAGMENT).bindings();
		assertTrue(bindings.stream().anyMatch(b -> b.kind() == Glsl450Program.ResourceBinding.Kind.TEXTURE
			&& b.binding() == 0 && b.slot() == 0 && b.sampler() != null), bindings.toString());
		assertTrue(bindings.stream().anyMatch(b -> b.kind() == Glsl450Program.ResourceBinding.Kind.BLOCK
			&& b.binding() == Glsl450Program.BLOCK_BINDING_BASE && b.slot() == 0), bindings.toString());
	}

	@Test
	void localsStartAtZeroAsTheMslEmitterStartsThem() {
		Glsl450Program p = Glsl450Printer.print(fullScreen());
		assertTrue(p.stage(Stage.FRAGMENT).source().contains("float u_acc = float(0);"),
			p.stage(Stage.FRAGMENT).source());
	}

	@Test
	void alphaTestBecomesDiscardAndOutputZeroIsWrittenWhenMissing() {
		ProgramRequest r = fullScreen();
		ProgramRequest withAlpha = new ProgramRequest(r.name(), r.family(), r.stages(), r.vertexLayout(),
			r.outputs(), r.textures(), r.images(), r.buffers(), r.uniforms(), new AlphaTest("GREATER", "scale"),
			false);
		String fs = Glsl450Printer.print(withAlpha).stage(Stage.FRAGMENT).source();
		assertTrue(fs.contains("if (!(qs_gl_FragData[0].w > qs_scale))"), fs);
	}

	@Test
	void computeProgramKeepsLocalSizeAndBindsItsBuffersAndImages() {
		String cs = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			layout(rgba16f) uniform image2D img;
			buffer Out { float values[]; };
			void main() {
				uint i = gl_GlobalInvocationID.x;
				values[i] = imageLoad(img, ivec2(i, 0)).x;
			}
			""";
		ProgramRequest r = new ProgramRequest("compute", ProgramRequest.Family.COMPUTE,
			List.of(new StageSource(Stage.COMPUTE, cs, lines(cs))), null, List.of(),
			List.of(), List.of(new ImageBinding("img", 3)), List.of(new BufferBinding("Out", 2)),
			UniformSchema.none(), null, false);
		Glsl450Program p = Glsl450Printer.print(r);
		assertTrue(p.ok(), String.valueOf(p.errors()));
		String src = p.stage(Stage.COMPUTE).source();
		assertTrue(src.contains("local_size_x = 8"), src);
		assertTrue(src.contains("binding = 1002"), src);
		assertTrue(src.contains("binding = 3"), src);
		assertRoundTrip(p.stage(Stage.COMPUTE));
	}

	@Test
	void aProgramWithoutItsSecondStageIsRefused() {
		ProgramRequest r = fullScreen();
		ProgramRequest one = new ProgramRequest(r.name(), r.family(), List.of(r.stages().get(0)), r.vertexLayout(),
			r.outputs(), r.textures(), r.images(), r.buffers(), r.uniforms(), null, false);
		assertFalse(Glsl450Printer.print(one).ok());
	}

	@Test
	void fragColorIsWrittenToEveryColourTarget() {
		String vs = """
			#version 120
			varying vec4 tint;
			void main() {
				gl_Position = ftransform();
				tint = gl_Color;
			}
			""";
		String fs = """
			#version 120
			varying vec4 tint;
			void main() {
				gl_FragColor = tint;
			}
			""";
		ProgramRequest r = new ProgramRequest("broadcast", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, vs, lines(vs)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 5, 4)),
			List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		Glsl450Program p = Glsl450Printer.print(r);
		assertTrue(p.ok(), String.valueOf(p.errors()));
		String src = p.stage(Stage.FRAGMENT).source();
		assertTrue(src.contains("qs_c0 = vec4(qs_gl_FragColor);"), src);
		assertTrue(src.contains("qs_c1 = vec4(qs_gl_FragColor);"), src);
		assertRoundTrip(p.stage(Stage.FRAGMENT));
	}
}
