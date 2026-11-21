// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.PerVertexIds;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The per-vertex id input (ProgramRequest.PerVertexIds, plan.md E4): the id uniforms are read from the side buffer at
 * {@code [[vertex_id]]} and reach the fragment stage flat. A request without the field changes nothing.
 */
class PerVertexIdsTest {
	/** The engine's per-vertex id slot (EngineSlots.PER_VERTEX_IDS, pinned by the runtime GPU test: this package is pure). */
	static final int SLOT = 20;

	private static final String VS = "#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n";

	private static ProgramTranslation translate(String vs, String fs, ProgramRequest.PerVertexIds ids) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length).mapToObj(i -> new SourceLine("vs", i))
			.toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length).mapToObj(i -> new SourceLine("fs", i))
			.toList();
		return Translator.translate(new ProgramRequest("ids", ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, vl), new StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false,
			ids));
	}

	@Test
	void requestWithoutTheFieldTranslatesAsBefore() {
		ProgramTranslation t = translate(VS,
			"#version 330\nuniform int entityId;\nout vec4 c;\nvoid main() { c = vec4(float(entityId)); }", null);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("QsPerVertexIds"), t.msl());
		assertFalse(t.msl().contains("qs_ids"), t.msl());
	}

	@Test
	void fragmentOnlyDeclarationReadsTheBufferAndPassesItFlat() {
		ProgramTranslation t = translate(VS, "#version 330\nuniform int entityId;\nuniform int blockEntityId;\n"
			+ "uniform int currentRenderedItemId;\nout vec4 c;\n"
			+ "void main() { c = vec4(float(entityId + blockEntityId + currentRenderedItemId)); }",
			new PerVertexIds(SLOT, null, null));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("struct QsPerVertexIds"), t.msl());
		assertTrue(t.msl().contains("int qs_entityId;"), t.msl());
		// the vertex entry takes the side buffer and the prologue reads the record vertex_id selects
		assertTrue(t.msl().contains("const device QsPerVertexIds *arg_qsid [[buffer(" + SLOT + ")]]"), t.msl());
		assertTrue(t.msl().contains("c.qs_ids = arg_qsid;"), t.msl());
		assertTrue(t.msl().contains("u_entityId = qs_ids[qs_vid].qs_entityId;"), t.msl());
		assertTrue(t.msl().contains("u_blockEntityId = qs_ids[qs_vid].qs_blockEntityId;"), t.msl());
		assertTrue(t.msl().contains("u_currentRenderedItemId = qs_ids[qs_vid].qs_currentRenderedItemId;"), t.msl());
		// all three reach the fragment stage flat and the fragment reads its own member
		assertTrue(t.msl().contains("int v_entityId [[user(locn"), t.msl());
		assertTrue(t.msl().contains("[[flat]]"), t.msl());
		assertTrue(t.msl().contains("c.u_entityId = qs_in.v_entityId;"), t.msl());
		assertTrue(t.reflection().uniforms().stream().anyMatch(u -> u.name().equals("entityId")
			&& "per-vertex ids".equals(u.servedBy())), t.reflection().toString());
	}

	@Test
	void vertexStageUseBindsFromTheBufferToo() {
		ProgramTranslation t = translate("#version 330\nuniform int entityId;\nin vec3 vaPosition;\n"
			+ "out float v_hurt;\nvoid main() { v_hurt = float(entityId); gl_Position = vec4(vaPosition, 1.0); }",
			"#version 330\nin float v_hurt;\nout vec4 c;\nvoid main() { c = vec4(v_hurt); }",
			new PerVertexIds(SLOT, null, null));
		assertTrue(t.ok(), t.diagnostics().toString());
		// only the vertex reads entityId, so the value needs no varying, but the buffer read is bound
		assertTrue(t.msl().contains("u_entityId = qs_ids[qs_vid].qs_entityId;"), t.msl());
		assertTrue(t.msl().contains("[[buffer(" + SLOT + ")]]"), t.msl());
		assertFalse(t.msl().contains("v_entityId"), t.msl());
	}

	@Test
	void typeDisagreementBetweenTheStagesIsAnError() {
		ProgramTranslation t = translate(
			"#version 330\nuniform int entityId;\nin vec3 vaPosition;\nout float v_hurt;\n"
				+ "void main() { v_hurt = float(entityId); gl_Position = vec4(vaPosition, 1.0); }",
			"#version 330\nuniform float entityId;\nin float v_hurt;\nout vec4 c;\n"
				+ "void main() { c = vec4(entityId + v_hurt); }",
			new PerVertexIds(SLOT, null, null));
		assertFalse(t.ok());
		assertTrue(t.diagnostics().stream().anyMatch(d -> d.code().equals("varying-type")),
			t.diagnostics().toString());
	}

	@Test
	void fullNamelessRequestLeavesTheBufferOut() {
		ProgramTranslation t = translate(VS, "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }",
			new PerVertexIds(SLOT, null, null));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("QsPerVertexIds"), t.msl());
	}

	@Test
	void fullScreenLayoutRefusesTheInput() {
		String vs = "#version 120\nvoid main() { gl_Position = ftransform(); }";
		String fs = "#version 330\nuniform int entityId;\nout vec4 c;\nvoid main() { c = vec4(float(entityId)); }";
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("quad", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, vs, vl), new StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			UniformSchema.none(), null, false, new PerVertexIds(SLOT, null, null)));
		assertFalse(t.ok());
		assertTrue(t.diagnostics().stream().anyMatch(d -> d.code().equals("per-vertex-ids")),
			t.diagnostics().toString());
	}
}
