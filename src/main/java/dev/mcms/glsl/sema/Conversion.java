// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.types.GlslType;

/**
 * One implicit conversion the analyzer applied, with the site that caused it. Conversions follow the version's
 * table (GLSL 4.60 specification, section 4.1.10); the emitter inserts them where they are recorded.
 */
public record Conversion(Expr expr, GlslType from, GlslType to, Site site, boolean broadcast) {
	/** Where a conversion was applied. */
	public enum Site {
		ARGUMENT("argument"),
		OPERAND("operand"),
		ASSIGNMENT("assignment"),
		INITIALIZER("initializer"),
		RETURN("return");

		private final String word;

		Site(String word) {
			this.word = word;
		}

		public String word() {
			return word;
		}
	}

	/** {@code broadcast} is true when the converted value is also splatted to a vector or matrix shape. */
	public Conversion {
	}

	@Override
	public String toString() {
		return from + " -> " + to + " (" + site.word() + ")";
	}
}
