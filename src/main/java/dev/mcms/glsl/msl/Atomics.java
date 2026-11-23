// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.sema.CallResolution;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructType;
import java.util.HashSet;
import java.util.Set;

/**
 * The variables and block members that an atomic function reads and writes (GLSL 4.60 section 8.11). MSL needs them as
 * atomic types (atomic_int, atomic_uint) in threadgroup or device memory, so every other access to them is a relaxed
 * atomic load or store. A block member is named by its block's struct and its GLSL name ("Struct.name").
 */
final class Atomics {

	private final Set<VariableSymbol> variables = new HashSet<>();
	private final Set<String> fields = new HashSet<>();

	/** Records the atomic uses of one stage: its shared variables and its block members. */
	void scan(Unit unit, SemaResult sema) {
		Trees.transform(unit, node -> {
			if (node instanceof CallExpr c && !c.args().isEmpty()) {
				CallResolution r = sema.callOf(c);
				if (r != null && r.builtin() != null && isAtomicFunction(r.name())) {
					Expr e = unwrapRef(c.args().get(0), sema);
					if (e instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
						variables.add(v);
						// A member of an unnamed block is a global whose declaration is the block.
						if (v.declaration() instanceof InterfaceBlock b && sema.structOf(b) != null) {
							fields.add(MslTypes.structName(sema.structOf(b)) + "." + v.name());
						}
					}
					if (e instanceof FieldExpr f && sema.typeOf(f.base()) instanceof StructType st) {
						fields.add(MslTypes.structName(st) + "." + f.name());
					}
				}
			}
			return node;
		});
	}

	/** True when the expression is a variable or block member (or an element of one) that an atomic function uses. */
	boolean isRef(Expr e, SemaResult sema) {
		Expr root = unwrapRef(e, sema);
		if (root instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
			return variables.contains(v);
		}
		if (root instanceof FieldExpr f && sema.typeOf(f.base()) instanceof StructType st) {
			return fields.contains(MslTypes.structName(st) + "." + f.name());
		}
		return false;
	}

	private static Expr unwrapRef(Expr e, SemaResult sema) {
		Expr root = e;
		while (true) {
			if (root instanceof IndexExpr ix) {
				root = ix.base();
			} else if (root instanceof FieldExpr f && sema.typeOf(f.base()) instanceof ScalarType
					&& f.name().length() == 1) {
				root = f.base();
			} else {
				return root;
			}
		}
	}

	boolean isVariable(VariableSymbol v) {
		return variables.contains(v);
	}

	boolean isField(String struct, String field) {
		return fields.contains(struct + "." + field);
	}

	/** The Metal type of an atomic int or uint, or of an array of them: atomic_uint, array&lt;atomic_int, 4&gt;. */
	static String valueOf(GlslType t) {
		return switch (t) {
			case ScalarType s -> "atomic_" + MslTypes.scalar(s.kind());
			case ArrayType a -> "array<" + valueOf(a.element()) + ", " + a.size() + ">";
			default -> throw new IllegalArgumentException("no atomic Metal type for " + t);
		};
	}

	/** The GLSL atomic functions (the counters are not here: MSL has no counter type). */
	static boolean isAtomicFunction(String name) {
		return name.startsWith("atomic") && !name.startsWith("atomicCounter");
	}
}
