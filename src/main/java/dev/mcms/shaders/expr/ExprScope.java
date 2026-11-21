// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * The names an expression may use. The runtime implements this with the standard uniforms ({@code frameTimeCounter},
 * {@code cameraPosition}, {@code sunAngle}, ...), the pack's earlier variables and uniforms, biome, category and
 * precipitation constants, and the entity booleans (OptiFine doc shaders.properties, "Custom uniforms", Parameters).
 */
public interface ExprScope {
	/**
	 * @param name the identifier as written in the expression
	 * @return the binding of the name, or null when this scope does not know it (a load-time error then names the
	 * expression and column)
	 */
	ExprBinding resolve(String name);
}
