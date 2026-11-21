// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * A properties file's {@code #define} or {@code #undef} with no name is a warning, not an error: the line is skipped and
 * the rest of the file is kept (a thrown error would empty the whole file).
 */
class PropertiesNamelessDirectiveTest {

	private static PreprocessResult props(String text) throws PreprocessException {
		return new Preprocessor(Mode.PROPERTIES, (f, n) -> Optional.empty(), Map.of(), null)
			.process(new SourceText("shaders.properties", text));
	}

	@Test
	void aDefineWithNoNameWarnsAndTheRestOfTheFileIsKept() throws PreprocessException {
		PreprocessResult r = props("#define\nkey=1\n#define 2 x\nkept=2\n");
		assertEquals(List.of("key=1", "kept=2"), List.of(r.text().split("\n", -1)));
		assertEquals(2, r.warnings().size(), r.warnings().toString());
		assertEquals(1, r.warnings().get(0).line(), "the warning names the line of the first nameless #define");
	}

	@Test
	void anUndefWithNoNameWarnsAndTheRestOfTheFileIsKept() throws PreprocessException {
		PreprocessResult r = props("key=1\n#undef\nkept=2\n");
		assertEquals(List.of("key=1", "kept=2"), List.of(r.text().split("\n", -1)));
		assertEquals(1, r.warnings().size(), r.warnings().toString());
		assertEquals(2, r.warnings().get(0).line());
	}
}
