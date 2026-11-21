// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MSL text of a program is the same in every JVM (E9, load time). Metal's shader cache is keyed by the source text, so a
 * text that changes with each game start compiles anew at every load: the built-ins came out in the order of
 * {@code Map.copyOf}, whose iteration order changes with each JVM start, and Complementary's Overworld took about 15 s of
 * Metal compile time (summed over the workers) at every start instead of about 0.1 s. Two child JVMs translate the same
 * program and must print this JVM's text.
 */
class TranslationDeterminismTest {

	private static final String VS = """
		#version 120
		varying vec4 color;
		varying vec2 uv;
		varying vec3 normal;
		void main() {
			uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy + gl_MultiTexCoord1.xy * 0.0;
			normal = normalize(gl_NormalMatrix * gl_Normal);
			color = gl_Color;
			vec4 view = gl_ModelViewMatrix * gl_Vertex;
			gl_Position = gl_ProjectionMatrix * view + gl_ModelViewProjectionMatrix * gl_Vertex * 0.0;
		}
		""";
	private static final String FS = """
		#version 120
		varying vec4 color;
		varying vec2 uv;
		varying vec3 normal;
		void main() {
			gl_FragData[0] = color * vec4(normal * 0.5 + 0.5, 1.0) + vec4(uv, gl_FragCoord.xy * 0.0);
		}
		""";
	/** The engine's draw block slot (the runtime's EngineSlots: the pure packages cannot import it, so the value repeats here). */
	private static final int DRAW = 25;

	static ProgramTranslation translate() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("QsDraw", DRAW, List.of(
			new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64),
			new UniformField("normalMatrix", "mat3", 128), new UniformField("textureMatrix", "mat4", 176)))));
		return Translator.translate(new ProgramRequest("determinism", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, VS, lines("vs", VS)),
				new ProgramRequest.StageSource(Stage.FRAGMENT, FS, lines("fs", FS))),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), schema, null, false));
	}

	private static List<SourceLine> lines(String file, String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine(file, i)).toList();
	}

	/** The child JVM: prints the program's MSL. */
	public static final class Child {
		public static void main(String[] args) {
			ProgramTranslation t = translate();
			if (!t.ok()) {
				System.err.println(t.diagnostics());
				System.exit(2);
			}
			System.out.print(t.msl());
		}
	}

	@Test
	void theMslTextIsTheSameInEveryJvm(@TempDir Path dir) throws IOException, InterruptedException {
		ProgramTranslation here = translate();
		assertTrue(here.ok(), here.diagnostics().toString());
		assertTrue(here.msl().contains("gl_NormalMatrix") && here.msl().contains("gl_MultiTexCoord1"), here.msl());
		String java = ProcessHandle.current().info().command()
			.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		for (int run = 0; run < 2; run++) {
			Path out = dir.resolve("child" + run + ".msl");
			Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Child.class.getName())
				.redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
			try {
				// The text goes to a file, so the wait is bounded: a hung child fails the test instead of holding the build.
				assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child JVM did not finish in 60 s");
				assertEquals(0, child.exitValue(), "the child JVM failed");
				assertEquals(here.msl(), Files.readString(out, StandardCharsets.UTF_8), "child JVM " + run
					+ " printed another MSL text");
			} finally {
				child.destroyForcibly();
			}
		}
	}
}
