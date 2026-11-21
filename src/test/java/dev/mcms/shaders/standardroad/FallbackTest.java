// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The fallback never throws and warns once per pack and program: a program the road cannot even print is the refused
 * answer, not an exception, and the same program name of two packs warns twice (program names repeat across packs).
 * The requests here never reach shaderc, so the test runs wherever the plain tests run.
 */
final class FallbackTest {

	private static final String VS = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	/** A request whose only stage has no text: the printer fails inside, not by returning errors. */
	private static ProgramRequest poison() {
		return new ProgramRequest("poison", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.FRAGMENT, null, List.<SourceLine>of())), VertexLayout.fullScreenQuad(),
			List.of(), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
	}

	/** A vertex-only request: the road refuses it before shaderc (a program needs its second stage). */
	private static ProgramRequest refused() {
		return new ProgramRequest("deferred1", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS))), VertexLayout.fullScreenQuad(), List.of(),
			List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
	}

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	@Test
	void aRequestTheRoadCannotHandleIsTheRefusedAnswerNotAThrow() {
		Fallback.Result poison = Fallback.standardRoad("pack", poison(), "Metal rejected the MSL");
		assertFalse(poison.ok());
		assertNotNull(poison.reason());
		assertFalse(poison.reason().isBlank());
		Fallback.Result absent = Fallback.standardRoad("pack", null, "Metal rejected the MSL");
		assertFalse(absent.ok());
		assertNotNull(absent.reason());
		Fallback.Result refused = Fallback.standardRoad("pack", refused(), "Metal rejected the MSL");
		assertFalse(refused.ok());
		assertNotNull(refused.program());
		assertTrue(refused.reason().contains("vertex and a fragment stage"), refused.reason());
	}

	@Test
	void theWarnLineIsKeyedByPackAndProgramAndClearedOnPackChange() {
		Fallback.packChanged();
		Fallback.standardRoad("packA", refused(), "Metal rejected the MSL");
		Fallback.standardRoad("packB", refused(), "Metal rejected the MSL");
		assertTrue(Fallback.warned("packA", "deferred1"), "packA's program warns");
		assertTrue(Fallback.warned("packB", "deferred1"), "the same program name of another pack warns too");
		Fallback.packChanged();
		assertFalse(Fallback.warned("packA", "deferred1"), "the pack switch clears the warnings");
		assertFalse(Fallback.warned("packB", "deferred1"));
	}
}
