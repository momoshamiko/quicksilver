// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The reflection says which textures a program can read at a mip level other than 0 (E9): the engine refills a target's
 * mip chain only before such programs. Through a sampler parameter it cannot tell which uniform a lookup reads, so every
 * uniform the stage passes to a sampler parameter counts.
 */
final class TextureLevelsReflectionTest {

	private static final String VS = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
		}
		""";

	private static StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new StageSource(stage, text, IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("levels", i)).toList());
	}

	/** The levels flag of each texture of a fragment program whose targets take a mip sampler and a level-0 sampler. */
	private static Map<String, Boolean> levels(String fs, String... names) {
		return levels(fs, Set.of(), names);
	}

	/**
	 * As {@link #levels(String, String...)}, but the textures in {@code declared} have no implicit sampler: the program declares
	 * their mipmaps, so every lookup takes the mip sampler (E6's rule).
	 */
	private static Map<String, Boolean> levels(String fs, Set<String> declared, String... names) {
		SamplerState mip = new SamplerState(ProgramRequest.Filter.LINEAR, ProgramRequest.Filter.LINEAR,
			ProgramRequest.Wrap.CLAMP_TO_EDGE, false, false);
		SamplerState level0 = SamplerState.linear();
		List<TextureBinding> bindings = IntStream.range(0, names.length)
			.mapToObj(i -> new TextureBinding(names[i], i, TextureKind.COLOUR, -1, mip, -1,
				declared.contains(names[i]) ? null : level0)).toList();
		ProgramRequest request = new ProgramRequest("levels", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, fs)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), bindings, List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation t = Translator.translate(request);
		assertTrue(t.ok(), t.diagnostics().toString());
		return t.reflection().textures().stream().collect(Collectors.toMap(
			ProgramTranslation.Reflection.ResourceUse::name, ProgramTranslation.Reflection.ResourceUse::levels));
	}

	@Test
	void implicitAndLevelZeroLookupsReadNoLevelAndAnExplicitLevelDoes() {
		String fs = """
			#version 130
			uniform sampler2D colortex0;
			uniform sampler2D colortex1;
			uniform sampler2D colortex2;
			in vec2 uv;
			out vec4 color;
			void main() {
				color = texture2D(colortex0, uv) + textureLod(colortex1, uv, 0.0) + textureLod(colortex2, uv, 3.0);
			}
			""";
		assertEquals(Map.of("colortex0", false, "colortex1", false, "colortex2", true),
			levels(fs, "colortex0", "colortex1", "colortex2"));
	}

	@Test
	void aLevelReadThroughASamplerParameterCountsForEveryUniformPassedToOne() {
		String fs = """
			#version 130
			uniform sampler2D colortex0;
			uniform sampler2D colortex3;
			uniform sampler2D colortex5;
			in vec2 uv;
			out vec4 color;
			vec4 blur(sampler2D t, vec2 p) { return textureLod(t, p, 2.0); }
			vec4 plain(sampler2D t, vec2 p) { return texture2D(t, p); }
			void main() {
				color = blur(colortex3, uv) + plain(colortex5, uv) + texture2D(colortex0, uv);
			}
			""";
		assertEquals(Map.of("colortex0", false, "colortex3", true, "colortex5", true),
			levels(fs, "colortex0", "colortex3", "colortex5"));
	}

	@Test
	void anImplicitLookupThroughASamplerParameterReadsTheLevelsOfAPassedTargetWithDeclaredMipmaps() {
		// Mellow 3.4.1a's composite2 (its bloom): blur3x3(colortex0, texcoord) with colortex0MipmapEnabled, texture() on the
		// parameter. The call passes colortex0's mip-filtered sampler as the parameter's implicit sampler, so the helper's
		// implicit lookups read colortex0's chain; colortex5 keeps its level-0 implicit sampler and reads level 0 alone.
		String fs = """
			#version 130
			uniform sampler2D colortex0;
			uniform sampler2D colortex5;
			in vec2 uv;
			out vec4 color;
			vec4 plain(sampler2D t, vec2 p) { return texture2D(t, p); }
			void main() {
				color = plain(colortex0, uv) + plain(colortex5, uv);
			}
			""";
		assertEquals(Map.of("colortex0", true, "colortex5", false), levels(fs, Set.of("colortex0"), "colortex0", "colortex5"));
		assertEquals(Map.of("colortex0", false, "colortex5", false), levels(fs, "colortex0", "colortex5"));
	}
}
