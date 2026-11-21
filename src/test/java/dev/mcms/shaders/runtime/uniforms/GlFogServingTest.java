// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.UniformUse;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;

/**
 * gl_Fog and the documented fog uniforms read in a vertex and a fragment stage are served by the frame block and are not
 * reported as unknown (plan.md E4; the fog range is {@link FrameUniforms}'s). Compute stages cannot read gl_Fog: it is a
 * compatibility built-in and the analyser refuses it in a core-profile unit.
 */
final class GlFogServingTest {
	private static final String VS = """
		#version 120
		varying float vFogEnd;
		void main() {
			gl_Position = ftransform();
			vFogEnd = gl_Fog.end;
		}
		""";

	private static final String FS = """
		#version 120
		varying float vFogEnd;
		uniform float fogEnd;
		uniform vec3 fogColor;
		uniform float fogStart;
		uniform float fogDensity;
		uniform int fogMode;
		uniform int fogShape;
		void main() {
			gl_FragData[0] = vec4(gl_Fog.color.rgb * gl_Fog.scale, gl_Fog.density + gl_Fog.start + vFogEnd + fogEnd);
			gl_FragData[1] = vec4(fogColor, fogStart + fogDensity + float(fogMode + fogShape));
		}
		""";

	@Test
	void fogReadsInVertexAndFragmentStagesAreServedByTheFrameBlock() {
		ProgramRequest request = new ProgramRequest("fog-serving", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4)), List.of(), List.of(), List.of(),
			new UniformSchema(List.of(FrameUniforms.schema(0))), null, false);
		ProgramTranslation t = Translator.translate(request);
		assertTrue(t.diagnostics().isEmpty(), String.join("\n", t.diagnostics().stream().map(Object::toString).toList()));
		assertServedByFrame(t, "gl_Fog", "fogColor", "fogStart", "fogEnd", "fogDensity", "fogMode", "fogShape");
	}

	private static void assertServedByFrame(ProgramTranslation t, String... names) {
		for (String name : names) {
			UniformUse use = t.reflection().uniforms().stream().filter(u -> u.name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " is not listed: " + t.reflection().uniforms()));
			assertEquals("QsFrame", use.servedBy(), name + " in " + t.reflection().uniforms());
		}
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new ProgramRequest.StageSource(stage, text,
			java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("fog", i)).toList());
	}
}
