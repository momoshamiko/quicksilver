// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Metal compiles the emitted attribute fill and the point-size epilogue. Attribute values need vertex buffers, which
 * the headless harness does not bind, so these are compile checks; the fill's values are checked as text in
 * AttributeFillAndPointSizeTest.
 */
@Tag("gpu")
final class AttributeAndPointGpuTest {

	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";

	private static void compiles(String vs, boolean points) {
		ProgramTranslation t = TranslateSupport.translateOk(new ProgramRequest("attr-gpu",
			ProgramRequest.Family.GBUFFERS,
			List.of(TranslateSupport.stage(Stage.VERTEX, vs), TranslateSupport.stage(Stage.FRAGMENT, FS)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float1", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, points));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			HeadlessGpu.CompileResult r = gpu.compile(t.msl());
			assertTrue(r.ok(), r.diagnostics() + "\n" + t.msl());
		}
	}

	@Test
	void floatAttributeWidenedToVec4Compiles() {
		compiles("#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; }\n", false);
	}

	@Test
	void floatAttributeWidenedToVec3Compiles() {
		compiles("#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n", false);
	}

	@Test
	void pointProgramWritingPointSizeCompiles() {
		compiles("#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; gl_PointSize = 3.0; }\n",
			true);
	}
}
