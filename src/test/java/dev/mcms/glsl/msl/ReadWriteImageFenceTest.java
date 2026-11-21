// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A compute store is fenced when its image may be read back by the kernel (plan.md E6, the fence bullet). A store through a
 * uniform fences when the kernel reads that uniform. A store through an image parameter always fences: the caller may read
 * the same image back, and the access scan records the parameter's uses under the parameter's own name, so it cannot tell.
 */
class ReadWriteImageFenceTest {

	private static final String UNIFORM_STORE = """
		#version 430
		layout(local_size_x = 1) in;
		layout(r32ui) uniform uimage2D img;
		layout(std430, binding = 0) buffer Out { uint data[]; };
		void main() {
			imageStore(img, ivec2(0), uvec4(7u));
			data[0] = imageLoad(img, ivec2(0)).x;
		}
		""";

	private static final String PARAMETER_STORE = """
		#version 430
		layout(local_size_x = 1) in;
		layout(r32ui) uniform uimage2D img;
		layout(std430, binding = 0) buffer Out { uint data[]; };
		void store(uimage2D target, ivec2 p) {
			imageStore(target, p, uvec4(7u));
		}
		void main() {
			store(img, ivec2(0));
			data[0] = imageLoad(img, ivec2(0)).x;
		}
		""";

	private static ProgramTranslation translate(String glsl) {
		List<SourceLine> lines = IntStream.rangeClosed(1, glsl.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("test", i)).toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("fence", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, glsl, lines)), null, List.of(), List.of(),
			List.of(new ImageBinding("img", 0)), List.of(new BufferBinding("Out", 1)),
			ProgramRequest.UniformSchema.none(), null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		return t;
	}

	/** The name of the image the first write in the text stores into. */
	private static String writtenImage(String msl) {
		Matcher write = Pattern.compile("(\\w+)\\.write\\(").matcher(msl);
		assertTrue(write.find(), "the store must translate to a write:\n" + msl);
		return write.group(1);
	}

	@Test
	void aStoreThroughAUniformTheKernelReadsIsFenced() {
		String msl = translate(UNIFORM_STORE).msl();
		String image = writtenImage(msl);
		assertTrue(msl.contains(image + ".fence()"), "the store must be fenced:\n" + msl);
	}

	@Test
	void aStoreThroughAnImageParameterIsFencedLikeTheUniformStore() {
		String uniformMsl = translate(UNIFORM_STORE).msl();
		String parameterMsl = translate(PARAMETER_STORE).msl();
		String uniformImage = writtenImage(uniformMsl);
		assertTrue(uniformMsl.contains(uniformImage + ".fence()"), uniformMsl);
		String parameterImage = writtenImage(parameterMsl);
		assertTrue(parameterMsl.contains(parameterImage + ".fence()"),
			"a store through an image parameter must be fenced too:\n" + parameterMsl);
	}
}
