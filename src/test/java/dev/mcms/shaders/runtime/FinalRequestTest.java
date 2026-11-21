// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The final program renders to the screen: its one output writes the main colour target, whose four components the
 * request must name — not colortex0's (the E3 review: a pack with an R8 colortex0 gave final a one-component
 * output, and the emitter truncated the screen value to it).
 */
class FinalRequestTest {

	private static PackModel model;

	@BeforeAll
	static void makePack(@TempDir Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("final.vsh"), """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("final.fsh"), """
			#version 130
			const int colortex0Format = R8;
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(colortex0, texcoord);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	@Test
	void theFinalProgramWritesTheMainTargetsFourComponents() {
		ResolvedProgram finalProgram = model.rootFolder().programs().stream()
			.filter(p -> p.name().equals("final")).findFirst().orElseThrow();
		ProgramRequest request = ProgramRequests.fullScreen(finalProgram, model, null,
			model.rootFolder().union().colorFormats(), Map.of(), ShadowTargets.of(model.rootFolder()), Set.of());
		assertEquals(4, request.outputs().get(0).components(), "the main colour target is RGBA");
		assertEquals(0, request.outputs().get(0).target(), "final's output redirects to the screen");
	}
}
