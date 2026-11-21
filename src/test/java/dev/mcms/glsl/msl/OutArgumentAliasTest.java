// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Out arguments copy their variable in before a statement and write it back after it. Two copies of one variable in
 * one statement would both be written back, so the second write wins and the first call's change is lost: the
 * translator refuses that shape rather than emit it.
 */
class OutArgumentAliasTest {

	private static final String HEAD = """
		#version 430
		layout(local_size_x = 1) in;
		vec2 g = vec2(0.0);
		bool bump(inout float x) {
			x = x + 1.0;
			return true;
		}
		void main() {
		""";

	private static ProgramTranslation translate(String body) {
		String text = HEAD + body + "}\n";
		List<SourceLine> lines = IntStream.rangeClosed(1, text.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("test", i)).toList();
		return Translator.translate(new ProgramRequest("alias", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, text, lines)), null, List.of(), List.of(), List.of(),
			List.of(), ProgramRequest.UniformSchema.none(), null, false));
	}

	@Test
	void twoOutCopiesOfOneVariableInOneStatementAreRefused() {
		ProgramTranslation t = translate("\tif (bump(g.x) && bump(g.x)) { }\n");
		assertTrue(!t.ok() && t.diagnostics().stream().anyMatch(d -> d.code().equals("out-argument-alias")),
			"expected out-argument-alias, got " + t.diagnostics());
	}

	@Test
	void oneOutCopyPerStatementIsNotAnAlias() {
		ProgramTranslation t = translate("\tbool b = bump(g.x);\n\tbool c = bump(g.x);\n");
		assertTrue(t.ok(), "two statements, one copy each: " + t.diagnostics());
	}
}
