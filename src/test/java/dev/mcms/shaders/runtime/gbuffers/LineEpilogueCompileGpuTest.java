// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The real outline formats compile through the translator (the game log 2026-11-14-e4-preview2 failed every LINE_WIDTH
 * layout with "use of undeclared identifier 'gl_Normal'" and fell back to game variants): the lines format widens
 * (rendertype_lines.vsh), the debug points format widens nothing (debug_point.vsh) and takes the point size from the
 * line-width attribute.
 */
@Tag("gpu")
class LineEpilogueCompileGpuTest {

	private static final String VS = "#version 330\nvoid main() { gl_Position = gl_Vertex; }\n";
	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";

	private static ProgramTranslation translate(VertexFormat format, boolean points) {
		ProgramRequest.VertexLayout layout = VertexLayoutRegistry.convert(format);
		assertNotNull(layout, "the outline format must convert");
		ProgramRequest request = new ProgramRequest("outline", ProgramRequest.Family.GBUFFERS,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), layout,
			List.of(new ProgramRequest.ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, points);
		ProgramTranslation translation = Translator.translate(request);
		assertTrue(translation.ok(), () -> translation.diagnostics().toString());
		return translation;
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		return new ProgramRequest.StageSource(stage, text,
			IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test", i)).toList());
	}

	private static void compiles(ProgramTranslation translation, String what) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			ShaderNative.Compiled library = ShaderNative.libraryNew(gpu.context(), translation.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			assertTrue(library.ok(), what + ": the Metal library failed: " + library.error());
			dev.mcms.metal.MetalBridge.release(library.library());
		}
	}

	@Test
	void theLinesFormatCompiles() {
		// The widening math is asserted on the emitted MSL (LineWideningEpilogueTest) and compiled through the real
		// request (GbufferCoverageGpuTest's lines locations); this minimal request serves no frame block, so its
		// epilogue takes the no-widening path and the compile proves the layout needs no gl_Normal.
		ProgramTranslation t = translate(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, false);
		assertTrue(!t.msl().contains("gl_Normal"), t.msl());
		compiles(t, "the lines format");
	}

	@Test
	void theDebugPointsFormatCompilesWithoutWidening() {
		ProgramTranslation t = translate(DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH, true);
		assertTrue(!t.msl().contains("qs_ln_"), "the debug points format widens nothing");
		compiles(t, "the debug points format");
	}
}
