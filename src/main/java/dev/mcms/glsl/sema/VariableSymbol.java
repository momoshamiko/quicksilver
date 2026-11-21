// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.types.GlslType;
import java.util.Objects;

/**
 * A variable: local, parameter, global, interface block instance, or a built-in like {@code gl_Vertex} or
 * {@code gl_MaxDrawBuffers}. {@code builtin} names the built-ins the emitter synthesizes; {@code readOnly}
 * is true when the program may not write it (uniforms, inputs, const variables, read-only built-ins).
 * {@code declaration} is the AST node that declared it (a {@link dev.mcms.glsl.ast.Declarator} or
 * {@link dev.mcms.glsl.ast.InterfaceBlock}), or null for built-ins.
 */
public record VariableSymbol(String name, GlslType type, TypeQuals quals, boolean builtin, boolean readOnly,
		Pos pos, Object declaration) implements Symbol {
	public boolean isConst() {
		return quals.isConst();
	}

	/**
	 * Hashes the name, position and built-in flag only: the record's own hash walked the whole declaration (an initializer's
	 * tree too) at each lookup, and the emitters look a symbol up at each reference (E9, load time). Equal symbols share
	 * these three, so the record's equality stays.
	 */
	@Override
	public int hashCode() {
		return (31 * name.hashCode() + Objects.hashCode(pos)) * 31 + Boolean.hashCode(builtin);
	}
}
