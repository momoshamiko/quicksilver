// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.AssignOp;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.PostfixExpr;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.ast.UnaryExpr;
import dev.mcms.glsl.ast.UnaryOp;
import dev.mcms.glsl.sema.CallResolution;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.VariableSymbol;
import java.util.HashMap;
import java.util.Map;

/**
 * How a stage reads and writes its images and blocks, from its uses (GLSL 4.60 section 8.12 for images, section 4.10
 * for buffers). An image is keyed by its GLSL name; a block member by its block's name (the block's struct name).
 * A resource the stage never uses has no entry, and the caller names its fallback.
 */
final class ResourceAccess {

	private static final int READ = 1, WRITE = 2;
	private final Map<String, Integer> uses = new HashMap<>();

	/** Records the reads and writes of one stage's images and blocks. */
	static ResourceAccess scan(Unit unit, SemaResult sema) {
		ResourceAccess out = new ResourceAccess();
		Trees.transform(unit, node -> {
			if (node instanceof CallExpr c && !c.args().isEmpty()) {
				CallResolution r = sema.callOf(c);
				if (r != null && r.builtin() != null) {
					String n = r.name();
					if (n.equals("imageLoad") || n.equals("imageSize") || n.equals("imageSamples")) {
						out.mark(sema, c.args().get(0), READ);
					} else if (n.equals("imageStore")) {
						out.mark(sema, c.args().get(0), WRITE);
					} else if (n.startsWith("imageAtomic") || Atomics.isAtomicFunction(n)) {
						out.mark(sema, c.args().get(0), READ | WRITE);
					}
				}
			} else if (node instanceof AssignExpr a) {
				out.mark(sema, a.target(), a.op() == AssignOp.ASSIGN ? WRITE : READ | WRITE);
			} else if (node instanceof PostfixExpr p) {
				out.mark(sema, p.operand(), READ | WRITE);
			} else if (node instanceof UnaryExpr u && (u.op() == UnaryOp.PRE_INC || u.op() == UnaryOp.PRE_DEC)) {
				out.mark(sema, u.operand(), READ | WRITE);
			}
			return node;
		});
		return out;
	}

	/** Adds the uses another stage makes, so the program's access is the union over its stages. */
	void addAll(ResourceAccess other) {
		other.uses.forEach((key, bits) -> uses.merge(key, bits, (x, y) -> x | y));
	}

	/** The access a resource needs: "read", "write" or "read_write"; {@code fallback} when no use is recorded. */
	String accessOf(String key, String fallback) {
		Integer bits = uses.get(key);
		if (bits == null) {
			return fallback;
		}
		return switch (bits) {
			case READ -> "read";
			case WRITE -> "write";
			default -> "read_write";
		};
	}

	private void mark(SemaResult sema, Expr target, int bits) {
		Expr root = target;
		while (root instanceof IndexExpr || root instanceof FieldExpr) {
			root = root instanceof IndexExpr ix ? ix.base() : ((FieldExpr) root).base();
		}
		if (root instanceof Ident id && sema.symbolOf(id) instanceof VariableSymbol v) {
			String key = v.declaration() instanceof InterfaceBlock b ? b.name() : v.name();
			uses.merge(key, bits, (x, y) -> x | y);
		}
	}
}
