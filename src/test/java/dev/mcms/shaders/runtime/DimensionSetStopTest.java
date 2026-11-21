// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader's stop points (no Metal device): a load whose request is already replaced stops before its first program,
 * so it makes no translation and no Metal call. The device argument is 0: a Metal call would fail on it, so a test that
 * reaches one fails here rather than passing by luck. The pack is written here, so the test runs in the public source
 * too, which has no bench/test-packs.
 */
class DimensionSetStopTest {

	@Test
	void aLoadStoppedBeforeItsFirstProgramMakesNoTranslationAndNoMetalCall(@TempDir Path dir) throws IOException {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
			#version 130
			void main() {
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("gbuffers_basic.fsh"), """
			#version 130
			/* DRAWBUFFERS:0 */
			void main() {
				gl_FragData[0] = vec4(1.0);
			}
			""");
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
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			void main() {
				gl_FragColor = texture2D(colortex0, texcoord);
			}
			""");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		DimensionPrograms dimension = model.rootFolder();
		assertThrows(GbufferPlans.BuildCancelled.class, () -> DimensionSet.load(0L, "stop", model, dimension,
			CustomUniforms.of(model), 70, dimension.union().colorFormats(), List.of(), Engine.COPIES_NOT_COMPILED,
			new GbufferPlans.Options(1, Thread.MIN_PRIORITY, () -> true)));
	}
}
