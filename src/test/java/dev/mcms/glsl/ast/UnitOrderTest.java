// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.parse.Parser;

/**
 * {@link Unit#itemsInOrder()} keeps declarations in list order, slots each directive before the first declaration
 * whose position follows it, and leaves positionless (synthesized) declarations where the list puts them.
 */
class UnitOrderTest {
	private static VarDecl floatVar(int line, String name) {
		Pos p = new Pos(line, 1);
		return new VarDecl(p, new Type(p, List.of(), new BuiltinTypeSpec(p, "float"), List.of()),
				List.of(new Declarator(new Pos(line, 7), name, List.of(), null)));
	}

	private static ExtensionDirective ext(int line) {
		return new ExtensionDirective(new Pos(line, 1), "GL_X", "enable");
	}

	private static Unit unit(List<Directive> directives, List<Decl> decls) {
		return new Unit(new Pos(1, 1), null, directives, decls);
	}

	private static List<String> labels(List<GlslNode> items) {
		List<String> out = new ArrayList<>();
		for (GlslNode n : items) {
			out.add(switch (n) {
				case VarDecl v -> v.declarators().getFirst().name();
				case ExtensionDirective e -> "ext";
				default -> n.getClass().getSimpleName();
			});
		}
		return out;
	}

	@Test
	void reorderedDeclarationsKeepTheirListOrder() {
		// The list holds b (line 3) before a (line 1); the directive sits at line 2.
		Unit u = unit(List.of(ext(2)), List.of(floatVar(3, "b"), floatVar(1, "a")));
		assertEquals(List.of("ext", "b", "a"), labels(u.itemsInOrder()));
	}

	@Test
	void synthesizedDeclarationStaysWhereTheListPutsIt() {
		// c has no position (line 0): it neither moves nor pulls the directive forward.
		Unit u = unit(List.of(ext(2)), List.of(floatVar(1, "a"), floatVar(0, "c"), floatVar(3, "b")));
		assertEquals(List.of("a", "c", "ext", "b"), labels(u.itemsInOrder()));
	}

	@Test
	void directiveAfterEveryDeclarationComesLast() {
		Unit u = unit(List.of(ext(9)), List.of(floatVar(3, "b"), floatVar(1, "a")));
		assertEquals(List.of("b", "a", "ext"), labels(u.itemsInOrder()));
	}

	@Test
	void printerEmitsReorderedDeclarationsInListOrder() {
		Unit u = unit(List.of(ext(2)), List.of(floatVar(3, "b"), floatVar(1, "a")));
		String printed = GlslPrinter.print(u);
		assertTrue(printed.indexOf("#extension") < printed.indexOf("float b"), printed);
		assertTrue(printed.indexOf("float b") < printed.indexOf("float a"), printed);
		// Re-parsing keeps the same order of declarations.
		Unit again = Parser.parse(printed);
		assertEquals("b", again.decls().get(0) instanceof VarDecl v ? v.declarators().getFirst().name() : "?");
	}
}
