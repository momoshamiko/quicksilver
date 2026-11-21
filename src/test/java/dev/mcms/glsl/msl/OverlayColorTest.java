// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.OverlayColor;
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
 * The entity overlay input (ProgramRequest.OverlayColor, plan.md E4): {@code entityColor} is the overlay texel
 * UV1 selects, passed to the fragment stage flat as {@code (rgb, 1 - a)}. A request without the field changes
 * nothing.
 */
class OverlayColorTest {

	private static final String VS = "#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n";

	private static ProgramTranslation translate(String vs, String fs, boolean overlayAttribute) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length).mapToObj(i -> new SourceLine("vs", i))
			.toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length).mapToObj(i -> new SourceLine("fs", i))
			.toList();
		List<VertexAttribute> attributes = new java.util.ArrayList<>(List.of(
			new VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0)));
		if (overlayAttribute) {
			attributes.add(new VertexAttribute(ProgramRequest.Semantic.OVERLAY, "short2", 12, 0));
		}
		return Translator.translate(new ProgramRequest("overlay", ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, vl), new StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.of(attributes.toArray(new VertexAttribute[0])),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false,
			null, new OverlayColor(3)));
	}

	@Test
	void requestWithoutTheFieldTranslatesAsBefore() {
		List<SourceLine> vl = IntStream.rangeClosed(1, VS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		String fs = "#version 330\nuniform vec4 entityColor;\nout vec4 c;\nvoid main() { c = entityColor; }";
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("overlay", ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, VS, vl), new StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("qs_ovl"), t.msl());
		assertFalse(t.msl().contains("v_entityColor"), t.msl());
	}

	@Test
	void fragmentDeclarationFetchesTheOverlayAndPassesItFlat() {
		ProgramTranslation t = translate(VS,
			"#version 330\nuniform vec4 entityColor;\nout vec4 c;\n"
				+ "void main() { c = vec4(mix(c.rgb, entityColor.rgb, entityColor.a), 1.0); }", true);
		assertTrue(t.ok(), t.diagnostics().toString());
		// the vertex entry takes the overlay texture at the request's slot and the prologue fetches UV1's texel (the values
		// it makes are OverlayColorGpuTest's)
		assertTrue(t.msl().contains("texture2d<float> arg_ovl [[texture(3)]]"), t.msl());
		assertTrue(t.msl().contains("c.qs_ovl = arg_ovl;"), t.msl());
		assertTrue(t.msl().contains("qs_ovl.read(min(uint2(int2(qs_a1.xy))"), t.msl());
		// the value reaches the fragment stage flat and the fragment reads its own member
		assertTrue(t.msl().contains("float4 v_entityColor [[user(locn0)]] [[flat]]"), t.msl());
		assertTrue(t.msl().contains("c.u_entityColor = qs_in.v_entityColor;"), t.msl());
		assertTrue(t.reflection().uniforms().stream().anyMatch(u -> u.name().equals("entityColor")
			&& "overlay color".equals(u.servedBy())), t.reflection().toString());
	}

	@Test
	void layoutWithoutTheOverlayAttributeReadsZero() {
		ProgramTranslation t = translate(VS,
			"#version 330\nuniform vec4 entityColor;\nout vec4 c;\nvoid main() { c = entityColor; }", false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("u_entityColor = float4(0.0f);"), t.msl());
		assertFalse(t.msl().contains("qs_ovl.read"), t.msl());
		assertFalse(t.msl().contains("[[texture(3)]]"), t.msl());
	}

	@Test
	void vertexStageUseBindsTheFetchWithoutAVarying() {
		ProgramTranslation t = translate("#version 330\nuniform vec4 entityColor;\nin vec3 vaPosition;\n"
			+ "out float v_hurt;\nvoid main() { v_hurt = entityColor.a; gl_Position = vec4(vaPosition, 1.0); }",
			"#version 330\nin float v_hurt;\nout vec4 c;\nvoid main() { c = vec4(v_hurt); }", true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("qs_ovl.read(min(uint2(int2(qs_a1.xy))"), t.msl());
		assertFalse(t.msl().contains("v_entityColor"), t.msl());
	}

	@Test
	void namelessRequestLeavesTheTextureOut() {
		ProgramTranslation t = translate(VS, "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }", true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("qs_ovl"), t.msl());
	}

	@Test
	void fullScreenLayoutRefusesTheInput() {
		String vs = "#version 120\nvoid main() { gl_Position = ftransform(); }";
		String fs = "#version 330\nuniform vec4 entityColor;\nout vec4 c;\nvoid main() { c = entityColor; }";
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("overlay", ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, vl), new StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			UniformSchema.none(), null, false, null, new OverlayColor(3)));
		assertFalse(t.ok());
		assertTrue(t.diagnostics().stream().anyMatch(d -> d.code().equals("overlay-color")),
			t.diagnostics().toString());
	}
}
