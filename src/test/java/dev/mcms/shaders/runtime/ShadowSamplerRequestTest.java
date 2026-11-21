// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shadow samplers of a pack's requests (E5; the public docs: the shadowtex page's "Hardware Sampling" and the
 * shadowHardwareFiltering, shadowtexNearest and shadowtexMipmap constant pages): a shadow read takes its filter and mip
 * from the constants of its map or colour buffer, and a declared {@code sampler2D} on a shadowtex map becomes a
 * comparison sampler when shadowHardwareFiltering is declared true. Also: which sets read shadow maps at all, so a set
 * whose shadow reads are only in gbuffers programs gets live shadow targets.
 */
class ShadowSamplerRequestTest {

	private static final String VERTEX = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";

	/** A composite that reads both maps and a colour buffer; the constants come in as the test sets them. */
	private static String composite(String constants) {
		return """
			#version 130
			%s
			uniform sampler2D shadowtex0;
			uniform sampler2D shadowcolor0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(shadowcolor0, texcoord) + vec4(texture2D(shadowtex0, texcoord).r);
			}
			""".formatted(constants);
	}

	private static PackModel load(Path dir, Map<String, String> files) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		for (Map.Entry<String, String> file : files.entrySet()) {
			Files.writeString(shaders.resolve(file.getKey()), file.getValue());
		}
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static ResolvedProgram program(PackModel model, String name) {
		return model.rootFolder().programs().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
	}

	private static TextureBinding binding(ProgramRequest request, String name) {
		return request.textures().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
	}

	private static ProgramRequest compositeRequest(PackModel model) {
		ProgramRequest request = ProgramRequests.fullScreen(program(model, "composite"), model, null,
			model.rootFolder().union().colorFormats(), model.rootFolder().union().mipmaps(),
			ShadowTargets.of(model.rootFolder()), Set.of());
		assertTrue(request != null, "the composite must give a request");
		return request;
	}

	@Test
	void aMapReadTakesItsComparisonFromTheDeclaredTypeAndItsFilterFromTheConstants(@TempDir Path dir) throws Exception {
		// Hardware filtering declared true, and shadowtex0 read only through a comparison (texture with a vec3 coordinate):
		// its declaration becomes sampler2DShadow, nearest as declared, and its mip chain is sampled through a linear mip
		// filter. shadowcolor0 keeps its linear sampler. A map the same stage reads as a value keeps sampler2D (ShadowTargets).
		PackModel model = load(dir, Map.of("composite.vsh", VERTEX, "composite.fsh", """
			#version 130
			const bool shadowHardwareFiltering = true;
			const bool shadowtex0Nearest = true;
			const bool shadowtex0Mipmap = true;
			const bool shadowcolor0Mipmap = true;
			uniform sampler2D shadowtex0;
			uniform sampler2D shadowcolor0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(shadowcolor0, texcoord) + vec4(texture(shadowtex0, vec3(texcoord, 0.5)));
			}
			"""));
		ProgramRequest request = compositeRequest(model);
		TextureBinding map = binding(request, "shadowtex0");
		assertEquals(TextureKind.COMPARE, map.kind(), "a hardware-filtered map is a comparison sampler");
		assertTrue(map.sampler().compare());
		assertEquals(ProgramRequest.Filter.NEAREST, map.sampler().filter());
		assertEquals(null, map.sampler().mip(), "a depth map has no mip chain (Depth32Float; shadowtex0Mipmap is ignored)");
		TextureBinding colour = binding(request, "shadowcolor0");
		assertFalse(colour.sampler().compare());
		assertEquals(ProgramRequest.Filter.LINEAR, colour.sampler().filter());
		assertEquals(ProgramRequest.Filter.LINEAR, colour.sampler().mip());
	}

	@Test
	void withoutTheConstantsTheDeclaredSamplerKeepsItsTypeAndLinearNoMipFilter(@TempDir Path dir) throws Exception {
		// The docs give no default for shadowHardwareFiltering: nothing declared, nothing rewritten. No mip constant:
		// no mip filter. No nearest constant: linear.
		PackModel model = load(dir, Map.of("composite.vsh", VERTEX, "composite.fsh", composite("")));
		ProgramRequest request = compositeRequest(model);
		TextureBinding map = binding(request, "shadowtex0");
		assertEquals(TextureKind.COLOUR, map.kind());
		assertFalse(map.sampler().compare());
		assertEquals(ProgramRequest.Filter.LINEAR, map.sampler().filter());
		assertNull(map.sampler().mip());
	}

	@Test
	void aDeclaredFalseKeepsTheDeclaredSamplerType(@TempDir Path dir) throws Exception {
		PackModel model = load(dir, Map.of("composite.vsh", VERTEX, "composite.fsh", composite("""
			const bool shadowHardwareFiltering = false;
			""")));
		TextureBinding map = binding(compositeRequest(model), "shadowtex0");
		assertEquals(TextureKind.COLOUR, map.kind());
		assertFalse(map.sampler().compare());
	}

	@Test
	void aSetWhoseShadowReadsAreOnlyInGbuffersHasLiveShadowTargets(@TempDir Path dir) throws Exception {
		// No shadow program, no composite reads: the only reader is a gbuffers program (its stage text declares the map).
		PackModel model = load(dir, Map.of(
			"gbuffers_terrain.vsh", VERTEX,
			"gbuffers_terrain.fsh", """
				#version 130
				uniform sampler2D shadowtex1;
				varying vec2 texcoord;
				void main() {
					gl_FragColor = vec4(texture2D(shadowtex1, texcoord).r);
				}
				"""));
		assertTrue(ShadowTargets.of(model.rootFolder()).used(), "a gbuffers read makes the set use shadow targets");
	}

	@Test
	void aSetWithoutAnyShadowReadUsesNoShadowTargets(@TempDir Path dir) throws Exception {
		PackModel model = load(dir, Map.of("composite.vsh", VERTEX, "composite.fsh", """
			#version 130
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(colortex0, texcoord);
			}
			"""));
		assertFalse(ShadowTargets.of(model.rootFolder()).used());
	}

	@Test
	void theWaterAliasIsReadFromAnyProgramOfTheSet(@TempDir Path dir) throws Exception {
		PackModel model = load(dir, Map.of("composite.vsh", VERTEX, "composite.fsh", composite(""),
			"gbuffers_water.vsh", VERTEX, "gbuffers_water.fsh", """
				#version 130
				uniform sampler2D watershadow;
				void main() {
					gl_FragColor = texture2D(watershadow, vec2(0.0));
				}
				"""));
		assertTrue(ShadowTargets.of(model.rootFolder()).waterShadow());
		assertEquals(1, ShadowTargets.of(model.rootFolder()).depthIndexOf("shadow"));
	}
}
