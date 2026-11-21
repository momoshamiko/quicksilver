// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Param;
import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.types.GlslType;

import java.util.List;

/**
 * One function overload: a user function (with its AST prototype) or a built-in signature. Parameter
 * qualifiers decide the direction of implicit conversions at call sites ({@code in}: argument to parameter,
 * {@code out}/{@code inout}: parameter to argument).
 */
public record FunctionOverload(String name, GlslType returnType, List<Parameter> params, boolean defined,
		boolean builtin, Pos pos, Object declaration) {

	public record Parameter(String name, GlslType type, StorageKind direction, boolean isConst) {
		public static Parameter in(GlslType type) {
			return new Parameter(null, type, StorageKind.IN, false);
		}

		public static Parameter out(GlslType type) {
			return new Parameter(null, type, StorageKind.OUT, false);
		}

		public static Parameter inOut(GlslType type) {
			return new Parameter(null, type, StorageKind.INOUT, false);
		}

		public boolean writes() {
			return direction == StorageKind.OUT || direction == StorageKind.INOUT;
		}
	}

	public boolean writes() {
		for (Parameter p : params) {
			if (p.writes()) {
				return true;
			}
		}
		return false;
	}

	static FunctionOverload user(dev.mcms.glsl.ast.FunctionPrototype proto, GlslType returnType,
			List<Parameter> params, boolean defined) {
		return new FunctionOverload(proto.name(), returnType, params, defined, false, proto.pos(), proto);
	}

	/** The GLSL signature spelling, for diagnostics ({@code max(genType, genType) -> genType}). */
	public String describe() {
		StringBuilder sb = new StringBuilder(name).append('(');
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(params.get(i).type());
		}
		return sb.append(") -> ").append(returnType).toString();
	}
}
