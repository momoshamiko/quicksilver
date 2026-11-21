// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The clouds prologue (plan.md E4): the game's 26.3 clouds draw binds no vertex format; clouds.vsh pulls three texels
 * per quad from the isamplerBuffer CloudFaces and builds position and colour from the vertex index. A request that
 * carries {@link ProgramRequest.CloudsPrologue} gets that pulling as its vertex prologue, so a pack's
 * {@code gbuffers_clouds} reads {@code gl_Vertex}, {@code gl_Color} and {@code gl_Normal} like any geometry program.
 */
class CloudsPrologueTest {

	private static final String PACK_VS = "#version 330\n"
		+ "out vec4 vColor;\nout vec3 vNormal;\n"
		+ "void main() {\n"
		+ "\tvColor = gl_Color;\n"
		+ "\tvNormal = gl_Normal;\n"
		+ "\tgl_Position = ftransform();\n"
		+ "}\n";

	private static final String PACK_FS = "#version 330\nin vec4 vColor;\nin vec3 vNormal;\n"
		+ "out vec4 c;\nvoid main() { c = vColor + vec4(vNormal, 0.0); }\n";

	private static ProgramTranslation translate(String vs, ProgramRequest.CloudsPrologue clouds,
			VertexLayout layout) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, PACK_FS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		return Translator.translate(new ProgramRequest("clouds", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, PACK_FS, fl)),
			layout, List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			new ProgramRequest.UniformSchema(List.of(new ProgramRequest.UniformBlock("QsMats", 0, List.of(
				new ProgramRequest.UniformField("modelViewMatrix", "mat4", 0),
				new ProgramRequest.UniformField("projectionMatrix", "mat4", 64))))),
			null, false, null, null, clouds));
	}

	private static final VertexLayout CLOUDS_LAYOUT = new VertexLayout(List.of(), false, null);

	@Test
	void prologueReproducesTheGameVertexPulling() {
		ProgramTranslation t = translate(PACK_VS, new ProgramRequest.CloudsPrologue(22, 30), CLOUDS_LAYOUT);
		assertTrue(t.ok(), t.diagnostics().toString());
		// The texel buffer read and the game's packed bit constants (clouds.vsh, 26.3).
		assertTrue(t.msl().contains("qs_cloudfs.read(uint(qs_cbase))"), t.msl());
		assertTrue(t.msl().contains("0xF03CC3"), t.msl());
		assertTrue(t.msl().contains("0x6666F0"), t.msl());
		assertTrue(t.msl().contains("0xC3F066"), t.msl());
		// The slots the request named (EngineSlots: CLOUD_INFO 22, CLOUD_FACES 30).
		assertTrue(t.msl().contains("[[buffer(22)]]"), t.msl());
		assertTrue(t.msl().contains("[[texture(30)]]"), t.msl());
		// gl_Vertex, gl_Color and gl_Normal are served from the pulled values.
		assertTrue(t.msl().contains("float4(qs_cpos, 1.0f)"), t.msl());
		assertTrue(t.msl().contains("qs_ccolv"), t.msl());
		assertTrue(t.msl().contains("qs_cnormal"), t.msl());
	}

	@Test
	void texcoordsKeepTheirGlDefaults() {
		String vs = "#version 330\nout vec4 vTc;\n"
			+ "void main() { vTc = gl_MultiTexCoord0; gl_Position = ftransform(); }\n";
		ProgramTranslation with = translate(vs, new ProgramRequest.CloudsPrologue(22, 30), CLOUDS_LAYOUT);
		ProgramTranslation without = translate(vs, null, CLOUDS_LAYOUT);
		assertTrue(with.ok(), with.diagnostics().toString());
		assertTrue(without.ok(), without.diagnostics().toString());
		// The game's clouds carry no texture coordinates (clouds.vsh has none), so the GL default fills stand.
		int withLine = with.msl().indexOf("gl_MultiTexCoord0 =");
		int withoutLine = without.msl().indexOf("gl_MultiTexCoord0 =");
		assertTrue(withLine >= 0 && withoutLine >= 0, with.msl());
		assertTrue(with.msl().substring(withLine).split(";", 2)[0]
			.equals(without.msl().substring(withoutLine).split(";", 2)[0]), with.msl());
	}

	@Test
	void layoutWithAttributesIsRefused() {
		ProgramTranslation t = translate(PACK_VS, new ProgramRequest.CloudsPrologue(22, 30),
			VertexLayout.of(new ProgramRequest.VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0)));
		assertFalse(t.ok());
		assertTrue(t.diagnostics().toString().contains("clouds-prologue"), t.diagnostics().toString());
	}

	@Test
	void fullScreenLayoutIsRefused() {
		ProgramTranslation t = translate(PACK_VS, new ProgramRequest.CloudsPrologue(22, 30),
			VertexLayout.fullScreenQuad());
		assertFalse(t.ok());
		assertTrue(t.diagnostics().toString().contains("clouds-prologue"), t.diagnostics().toString());
	}

	@Test
	void regionPrologueTogetherIsRefused() {
		ProgramTranslation t = translate(PACK_VS, new ProgramRequest.CloudsPrologue(22, 30),
			new VertexLayout(List.of(), false, new ProgramRequest.VertexPrologue(2, 0, -1, -1)));
		assertFalse(t.ok());
		assertTrue(t.diagnostics().toString().contains("clouds-prologue"), t.diagnostics().toString());
	}
}
