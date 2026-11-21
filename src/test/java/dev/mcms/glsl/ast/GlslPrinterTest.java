// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.parse.TestParse;

/** Parenthesisation exactly where precedence needs it, plus directive, declaration and statement printing. */
class GlslPrinterTest {
	private static String printed(String expr) {
		return GlslPrinter.print(TestParse.expr(expr));
	}

	@Test
	void noUnneededParentheses() {
		assertEquals("a + b * c", printed("a + b * c"));
		assertEquals("a - b - c", printed("a - b - c"));
		assertEquals("a / b * c", printed("a / b * c"));
		assertEquals("!a && b", printed("!a && b"));
		assertEquals("a = b = c", printed("a = b = c"));
		assertEquals("a ? b : c ? d : e", printed("a ? b : c ? d : e"));
		assertEquals("f(a, b + 1)", printed("f(a, b + 1)"));
		assertEquals("m[1][2]", printed("m[1][2]"));
		assertEquals("a.b.c", printed("a.b.c"));
		assertEquals("x++ + ++y", printed("x++ + ++y"));
	}

	@Test
	void parenthesesWherePrecedenceNeedsThem() {
		assertEquals("(a + b) * c", printed("(a + b) * c"));
		assertEquals("a - (b - c)", printed("a - (b - c)"));
		assertEquals("-(a + b)", printed("-(a + b)"));
		assertEquals("!(a && b)", printed("!(a && b)"));
		assertEquals("(a ? b : c) ? d : e", printed("(a ? b : c) ? d : e"));
		assertEquals("a, b", printed("(a, b)"));
		assertEquals("(a + b).xy", printed("(a + b).xy"));
		assertEquals("f((a, b), c)", printed("f((a, b), c)"));
		assertEquals("- -x", printed("- -x"));
		assertEquals("(-x)++", printed("(-x)++"));
	}

	@Test
	void literalSpellingIsPreserved() {
		assertEquals("0x1F", printed("0x1F"));
		assertEquals("42u", printed("42u"));
		assertEquals(".5", printed(".5"));
		assertEquals("1.", printed("1."));
		assertEquals("1.0f", printed("1.0f"));
		assertEquals("2.0lf", printed("2.0lf"));
		assertEquals("true", printed("true"));
	}

	@Test
	void unitPrintsDirectivesThenDeclarations() {
		String out = GlslPrinter.print(Parser.parse("""
				#version 400 compatibility
				#extension GL_ARB_sh : enable
				#pragma optimize(off)
				uniform float x;
				"""));
		String expected = """
				#version 400 compatibility
				#extension GL_ARB_sh : enable
				#pragma optimize(off)
				uniform float x;
				""";
		assertEquals(expected, out);
	}

	@Test
	void structAndInterfaceBlockPrinting() {
		assertEquals("""
				struct Light {
				\tvec3 position;
				\tfloat power;
				};""", GlslPrinter.print(TestParse.decl("struct Light { vec3 position; float power; };")));
		assertEquals("""
				layout(std430, binding = 1) buffer B {
				\tfloat data[];
				} reduction;""", GlslPrinter.print(TestParse.decl(
				"layout(std430, binding = 1) buffer B { float data[]; } reduction;")));
	}

	@Test
	void declarationPrintingKeepsQualifierOrderAndDims() {
		assertEquals("flat in vec3 normal;", GlslPrinter.print(TestParse.decl("flat in vec3 normal;")));
		assertEquals("float[3] x;", GlslPrinter.print(TestParse.decl("float[3] x;")));
		assertEquals("float x[3] = 1.0;", GlslPrinter.print(TestParse.decl("float x[3] = 1.0;")));
		assertEquals("layout(local_size_x = 8) in;", GlslPrinter.print(TestParse.decl("layout(local_size_x = 8) in;")));
		assertEquals("invariant gl_Position;", GlslPrinter.print(TestParse.decl("invariant gl_Position;")));
		assertEquals("precision highp float;", GlslPrinter.print(TestParse.decl("precision highp float;")));
		assertEquals("vec4 f(vec4, in float a[2]);",
				GlslPrinter.print(TestParse.decl("vec4 f(vec4, in float a[2]);")));
	}

	@Test
	void statementPrintingKeepsStructure() {
		assertEquals("""
				void main() {
				\tif (a) {
				\t\tx = 1;
				\t} else {
				\t\tx = 2;
				\t}
				}""", GlslPrinter.print(Parser.parse("void main() { if (a) { x = 1; } else { x = 2; } }").decls()
				.getFirst()));
		assertEquals("""
				void main() {
				\tfor (int i = 0; i < 4; ++i) {
				\t\tsum += i;
				\t}
				}""", GlslPrinter.print(Parser.parse("void main() { for (int i = 0; i < 4; ++i) { sum += i; } }")
				.decls().getFirst()));
		assertEquals("void main() {\n\tfor (;;) break;\n}",
				GlslPrinter.print(Parser.parse("void main() { for (;;) break; }").decls().getFirst()));
		assertEquals("void main() {\n\tdo ++i; while (i < 8);\n}",
				GlslPrinter.print(Parser.parse("void main() { do ++i; while (i < 8); }").decls().getFirst()));
	}

	@Test
	void assignmentTargetsKeepNecessaryParentheses() {
		assertEquals("(a ? b : c) = d", printed("(a ? b : c) = d"));
		assertEquals("-x = d", printed("-x = d"));
		// Printing and re-parsing such a target gives the same tree.
		var tree = TestParse.expr("(a ? b : c) = d");
		var printedTree = GlslPrinter.print(tree);
		var reparsed = dev.mcms.glsl.parse.TestParse.expr(printedTree);
		assertTrue(Trees.equalsIgnorePos(tree, reparsed), printedTree);
	}

	@Test
	void directivesAreEmittedInPlace() {
		String out = GlslPrinter.print(Parser.parse("""
				#version 460
				uniform float a;
				#extension GL_ARB_sh : enable
				uniform float b;
				"""));
		String expected = """
				#version 460
				uniform float a;
				#extension GL_ARB_sh : enable
				uniform float b;
				""";
		assertEquals(expected, out);
	}

	@Test
	void itemsInOrderMergesByPosition() {
		var u = Parser.parse("""
				#version 460
				uniform float a;
				#extension GL_ARB_sh : enable
				uniform float b;
				""");
		var items = u.itemsInOrder();
		assertEquals(3, items.size());
		assertInstanceOf(VarDecl.class, items.get(0));
		assertInstanceOf(ExtensionDirective.class, items.get(1));
		assertInstanceOf(VarDecl.class, items.get(2));
	}

	@Test
	void rebuiltIfWithoutElseInsideIfElseKeepsItsBranch() {
		// The parser never produces this shape (else binds to the inner if), but a rewrite can; the printer must brace
		// it so the else stays on the outer if when re-parsed.
		Pos pos = new Pos(1, 1);
		var innerThen = new dev.mcms.glsl.ast.ExprStmt(pos, new dev.mcms.glsl.ast.Ident(pos, "x"));
		var inner = new dev.mcms.glsl.ast.IfStmt(pos, new dev.mcms.glsl.ast.ExprCond(pos,
				new dev.mcms.glsl.ast.Ident(pos, "b")), innerThen, null);
		var outerElse = new dev.mcms.glsl.ast.ExprStmt(pos, new dev.mcms.glsl.ast.Ident(pos, "y"));
		var outer = new dev.mcms.glsl.ast.IfStmt(pos, new dev.mcms.glsl.ast.ExprCond(pos,
				new dev.mcms.glsl.ast.Ident(pos, "a")), inner, outerElse);
		var body = new dev.mcms.glsl.ast.BlockStmt(pos, java.util.List.of(outer));
		var def = new dev.mcms.glsl.ast.FunctionDefinition(pos,
				new dev.mcms.glsl.ast.FunctionPrototype(pos,
						new dev.mcms.glsl.ast.Type(pos, java.util.List.of(),
								new dev.mcms.glsl.ast.BuiltinTypeSpec(pos, "void"), java.util.List.of()),
						"main", pos, java.util.List.of()),
				body);
		String printed = GlslPrinter.print(def);
		assertTrue(printed.contains("} else"), printed);
		var reparsed = (dev.mcms.glsl.ast.FunctionDefinition) Parser.parse(printed).decls().getFirst();
		var outerAgain = (dev.mcms.glsl.ast.IfStmt) ((dev.mcms.glsl.ast.BlockStmt) reparsed.body()).statements()
				.getFirst();
		assertTrue(outerAgain.elseStmt() != null, "the outer if keeps its else");
		var thenBlock = (dev.mcms.glsl.ast.BlockStmt) outerAgain.thenStmt();
		var innerAgain = (dev.mcms.glsl.ast.IfStmt) thenBlock.statements().getFirst();
		assertTrue(innerAgain.elseStmt() == null, "the else must stay on the outer if");
	}
}
