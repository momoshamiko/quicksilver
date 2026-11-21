// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The line-widening epilogue follows the game's {@code rendertype_lines.vsh}: a layout that carries the line width and
 * a normal widens (the game's shader projects {@code Position} and {@code Position + Normal}), and one without a normal
 * — the game's debug points ({@code core/debug_point.vsh} widens nothing and writes {@code gl_PointSize = LineWidth}) —
 * takes no widening, with the point size from the line-width attribute. The geometry comes from the layout's
 * attributes, not the served gl_Vertex/gl_Normal members: a pack program need not read either name (game log
 * 2026-11-14-e4-preview2: "use of undeclared identifier 'gl_Normal'").
 */
class LineWideningEpilogueTest {

	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";
	/** A program that names no attribute: the epilogue must still find the geometry in the layout. */
	private static final String VS_LEGACY = "#version 330\nvoid main() { gl_Position = gl_Vertex; }\n";
	/** The 1.17 name: no gl_Vertex and no gl_Normal member is ever declared. */
	private static final String VS_VANILLA_NAMES = "#version 330\nin vec3 vaPosition;\n"
		+ "void main() { gl_Position = vec4(vaPosition, 1.0); }\n";

	private static ProgramTranslation translate(String vs, VertexLayout layout, boolean points) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, FS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		return Translator.translate(new ProgramRequest("line", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, FS, fl)),
			layout, List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, points));
	}

	/** The outline format (DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH's shape): position, colour, normal, width. */
	private static VertexLayout lines() {
		return VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
			new VertexAttribute(Semantic.COLOR, "float4", 0, 12),
			new VertexAttribute(Semantic.NORMAL, "float3", 0, 28),
			new VertexAttribute(Semantic.LINE_WIDTH, "float1", 0, 40));
	}

	/** The debug points format (DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH's shape): no normal. */
	private static VertexLayout points() {
		return VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
			new VertexAttribute(Semantic.COLOR, "float4", 0, 12),
			new VertexAttribute(Semantic.LINE_WIDTH, "float1", 0, 28));
	}

	@Test
	void aProgramThatNamesNoAttributeStillWidensThroughTheLayoutsAttributes() {
		ProgramTranslation t = translate(VS_LEGACY, lines(), false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("qs_ln_end"), "the epilogue must widen: " + t.msl());
		assertTrue(t.msl().contains("qs_ln_pos = float3(qs_a0).xyz"), "the start comes from the POSITION attribute: "
			+ t.msl());
		assertTrue(t.msl().contains("qs_a2"), "the end point comes from the NORMAL attribute: " + t.msl());
		assertFalse(t.msl().contains("gl_Normal"), "the program reads no gl_Normal, none may appear: " + t.msl());
	}

	@Test
	void theVanillaNamesWidenToo() {
		ProgramTranslation t = translate(VS_VANILLA_NAMES, lines(), false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("qs_ln_end"), t.msl());
		assertFalse(t.msl().contains("gl_Vertex.xyz"), "no gl_Vertex member may be read: " + t.msl());
	}

	@Test
	void aProgramThatReadsTheNormalStillWidens() {
		ProgramTranslation t = translate("#version 330\nvoid main() { gl_Position = vec4(gl_Vertex.xyz + gl_Normal, 1.0); }\n",
			lines(), false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("qs_ln_end"), t.msl());
	}

	@Test
	void aLayoutWithoutANormalTakesNoWidening() {
		ProgramTranslation t = translate(VS_LEGACY, points(), true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("qs_ln_"), "debug points widen nothing: " + t.msl());
		assertFalse(t.msl().contains("gl_Normal"), t.msl());
	}

	@Test
	void pointsTakeTheirSizeFromTheLineWidthAttributeWhenTheProgramWritesNone() {
		ProgramTranslation t = translate(VS_LEGACY, points(), true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("o.gl_PointSize = qs_a2;"), "the game's debug_point.vsh sets the size from LineWidth: "
			+ t.msl());
	}

	@Test
	void pointsWithoutALineWidthKeepTheDefaultSize() {
		ProgramTranslation t = translate(VS_LEGACY,
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)), true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("o.gl_PointSize = 1.0f;"), t.msl());
		assertFalse(t.msl().contains("qs_ln_"), t.msl());
	}
}
