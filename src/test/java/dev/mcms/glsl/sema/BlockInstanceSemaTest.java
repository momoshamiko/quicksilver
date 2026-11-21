// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.types.StructType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A named block instance ({@code buffer B { ... } inst;}) records its block's struct, as an unnamed block does. */
class BlockInstanceSemaTest {

	private static InterfaceBlock firstBlock(Unit unit) {
		for (Decl d : unit.decls()) {
			if (d instanceof InterfaceBlock b) {
				return b;
			}
		}
		throw new AssertionError("no block in the unit");
	}

	@Test
	void namedInstanceKeepsTheStructOfItsBlockAndDeclaresTheInstance() {
		String glsl = "#version 430\nbuffer Out { uint o[]; } ob;\nvoid main() { ob.o[0] = 1u; }\n";
		Unit unit = Parser.parse(glsl);
		SemaResult sema = SemanticAnalyzer.analyze(unit, SemaOptions.lenient(Stage.COMPUTE, List.of()));
		InterfaceBlock block = firstBlock(unit);
		StructType st = sema.structOf(block);
		assertNotNull(st, "the block's struct");
		assertEquals("Out", st.name());
		assertEquals("o", st.fields().getFirst().name());
		assertTrue(sema.globals().stream().anyMatch(v -> v.name().equals("ob") && v.declaration() == block),
			"the instance is a global whose declaration is the block");
	}
}
