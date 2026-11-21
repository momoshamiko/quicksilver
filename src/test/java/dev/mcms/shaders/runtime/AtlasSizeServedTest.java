// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The GLSL uniform {@code atlasSize} is served by the engine's per-draw block (gbuffers and shadow programs) and by the
 * full-screen block (full-screen and compute programs), and by no other block: the translator must not list it as unknown,
 * which would make it read zero in every draw. The value itself is checked on the device by {@code AtlasSizeGpuTest}.
 */
class AtlasSizeServedTest {

	private static final String VS = """
		#version 130

		// Quicksilver test (AtlasSizeServedTest): a pass-through vertex stage. Original code.

		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String FS = """
		#version 130

		// Quicksilver test (AtlasSizeServedTest): the atlas size as two colour components. Original code.

		uniform ivec2 atlasSize;

		void main() {
			gl_FragData[0] = vec4(float(atlasSize.x), float(atlasSize.y), 0.0, 1.0);
		}
		""";

	@Test
	void theGbuffersDrawBlockServesAtlasSize() {
		ProgramRequest request = new ProgramRequest("gbuffers-atlas", ProgramRequest.Family.GBUFFERS, stages(),
			VertexLayout.fullScreenQuad(), List.of(new ProgramRequest.ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			new UniformSchema(List.of(ProgramRequests.DRAW_BLOCK, FrameUniforms.schema(ProgramRequests.FRAME_SLOT))), null, false);
		assertServedBy(request, "QsDraw");
	}

	@Test
	void theFullScreenBlockServesAtlasSize() {
		ProgramRequest request = new ProgramRequest("fullscreen-atlas", ProgramRequest.Family.FULL_SCREEN, stages(),
			VertexLayout.fullScreenQuad(), List.of(new ProgramRequest.ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			new UniformSchema(List.of(FrameUniforms.schema(ProgramRequests.FRAME_SLOT), ProgramRequests.PASS_BLOCK)), null,
			false);
		assertServedBy(request, "QsDraw");
	}

	private static void assertServedBy(ProgramRequest request, String block) {
		ProgramTranslation translation = Translator.translate(request);
		assertTrue(translation.ok(), () -> "the test program must translate: " + translation.diagnostics());
		ProgramTranslation.Reflection.UniformUse use = translation.reflection().uniforms().stream()
			.filter(u -> u.name().equals("atlasSize")).findFirst().orElseThrow(
				() -> new AssertionError("the reflection must list atlasSize: " + translation.reflection().uniforms()));
		assertEquals(block, use.servedBy(), "atlasSize must be served by " + block + ", not read as unknown");
		assertEquals("ivec2", use.glslType());
	}

	private static List<StageSource> stages() {
		return List.of(new StageSource(Stage.VERTEX, VS, ProgramRequests.linesOf("atlas-test.vsh", VS)),
			new StageSource(Stage.FRAGMENT, FS, ProgramRequests.linesOf("atlas-test.fsh", FS)));
	}
}
