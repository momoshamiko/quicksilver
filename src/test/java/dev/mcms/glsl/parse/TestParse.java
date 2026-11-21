// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import dev.mcms.glsl.ast.BlockStmt;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.TypeSpec;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.VarDecl;

/** Small helpers shared by the parser tests. */
public final class TestParse {
	private TestParse() {
	}

	static Unit unit(String text) {
		return Parser.parse(text);
	}
	public static Decl decl(String text) {
		return Parser.parse(at460(text)).decls().getFirst();
	}

	/** Fragments without their own version line are parsed as GLSL 4.60 (the tests that check version rules pass a
	 * full source). */
	private static String at460(String text) {
		return text.startsWith("#") ? text : "#version 460\n" + text;
	}

	static VarDecl varDecl(String text) {
		return (VarDecl) decl(text);
	}

	/** Parses {@code text} as an initializer so expression tests need no wrapper nodes. */
	public static Expr expr(String text) {
		VarDecl v = varDecl("float mcmsValue = " + text + ";");
		return v.declarators().getFirst().initializer();
	}

	/** Parses a block ({@code "{ ... }"}) as a function body. */
	public static BlockStmt body(String block) {
		FunctionDefinition f = (FunctionDefinition) Parser.parse(at460("void main() " + block)).decls().getFirst();
		return f.body();
	}

	static TypeSpec spec(String text) {
		return varDecl(text + " x;").type().spec();
	}
}
