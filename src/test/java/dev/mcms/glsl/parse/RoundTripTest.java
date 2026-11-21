// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import dev.mcms.glsl.ast.GlslPrinter;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.Unit;

/**
 * Round trip on every test source: parse, print, parse again, compare the trees (positions aside), and check the
 * printer is stable (printing the second tree gives the same text).
 */
class RoundTripTest {
	@Test
	void everyTestSourceRoundTrips() {
		for (TestShaders.Source s : TestShaders.ALL) {
			Unit first = Parser.parse(s.text());
			String printed = GlslPrinter.print(first);
			Unit second = Parser.parse(printed);
			assertTrue(Trees.equalsIgnorePos(first, second), () -> s.name() + " changed through print/parse:\n"
					+ printed);
			assertEquals(printed, GlslPrinter.print(second), () -> s.name() + " print is not stable");
		}
	}

	@Test
	void grammarFragmentsRoundTrip() {
		String[] fragments = {
				"uniform vec4 c;",
				"layout(binding = 2) uniform sampler2D tex;",
				"float[2][2] m[4];",
				"struct A { struct B { int y; } b; };",
				"void f(vec4, in float, out vec3 d[2]);",
				"void main() { int i = 0, j = 1; i += j << 2; }",
				"void main() { switch (x) { case 0: case 1: y = 1; default: break; } }",
				"void main() { for (int i = 0, j = 8; i < j; ++i, --j) sum += i; }",
				"void main() { while (bool ok = f()) ++i; }",
				"void main() { v = a ? 1, 2 : 3; }",
				"void main() { x = - -y; }",
				"void main() { x = (a + b).xy; }",
				"layout(std140) uniform M { mat4 v; };",
				"void main() { int n = a.length(); }",
				"float a[2] = {1.0, 2.0};",
				"float b[2][2] = {{1.0, 2.0}, {3.0, 4.0}};",
				"uniform float a;\n#extension GL_ARB_sh : enable\nuniform float b;",
				"void main() { a[i += 1] = 0.0; }",
		};
		for (String f : fragments) {
			Unit first = Parser.parse("#version 460\n" + f);
			String printed = GlslPrinter.print(first);
			Unit second = Parser.parse(printed);
			assertTrue(Trees.equalsIgnorePos(first, second), () -> f + " changed through print/parse:\n" + printed);
			assertEquals(printed, GlslPrinter.print(second), () -> f + " print is not stable");
		}
	}
}
