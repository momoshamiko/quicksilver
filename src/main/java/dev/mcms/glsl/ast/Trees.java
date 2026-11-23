// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Tree helpers for later stages: structural equality ignoring every position component, and a bottom-up rewrite that
 * rebuilds records through their canonical constructors.
 */
public final class Trees {
	/**
	 * Each record class's components, read once: a fresh {@code getRecordComponents} array per node gave every node fresh
	 * accessor methods, and each built its method handle anew on first use (E9, load time: about half of a translation).
	 */
	private static final ClassValue<RecordComponent[]> COMPONENTS = new ClassValue<>() {
		@Override
		protected RecordComponent[] computeValue(Class<?> type) {
			return type.getRecordComponents();
		}
	};

	/** Each node class's canonical constructor, looked up once for the same reason. */
	private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<>() {
		@Override
		protected Constructor<?> computeValue(Class<?> type) {
			RecordComponent[] components = COMPONENTS.get(type);
			Class<?>[] types = new Class<?>[components.length];
			for (int i = 0; i < components.length; i++) {
				types[i] = components[i].getType();
			}
			try {
				Constructor<?> ctor = type.getDeclaredConstructor(types);
				ctor.setAccessible(true);
				return ctor;
			} catch (NoSuchMethodException e) {
				throw new IllegalStateException("no canonical constructor in " + type.getSimpleName(), e);
			}
		}
	};

	private Trees() {
	}

	public static boolean equalsIgnorePos(Object a, Object b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		if (a instanceof Record && b instanceof Record) {
			if (a.getClass() != b.getClass()) {
				return false;
			}
			for (RecordComponent c : COMPONENTS.get(a.getClass())) {
				if (isPositionComponent(c)) {
					continue;
				}
				if (!equalsIgnorePos(get(a, c), get(b, c))) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof List<?> la && b instanceof List<?> lb) {
			if (la.size() != lb.size()) {
				return false;
			}
			for (int i = 0; i < la.size(); i++) {
				if (!equalsIgnorePos(la.get(i), lb.get(i))) {
					return false;
				}
			}
			return true;
		}
		return a.equals(b);
	}

	/**
	 * Rebuilds {@code node} bottom-up: children are transformed first, then each rebuilt node passes through
	 * {@code rewriter}. Returning the node itself keeps it. Unchanged subtrees keep their instance, so a rewrite that
	 * touches nothing returns the exact same tree, and an unchanged list keeps its instance too. Changed lists are
	 * copied immutably.
	 *
	 * <p>Contract for a rewriter: it must not return {@code null} (nodes are never deleted by this helper; drop a node by
	 * building its parent yourself), and it must return a node that fits the slot it replaces (an {@link Expr} for an
	 * expression slot, a {@link Stmt} for a statement slot, and so on). A null result, or a result the parent's
	 * constructor rejects, throws {@link IllegalArgumentException} naming the node.
	 */
	public static GlslNode transform(GlslNode node, UnaryOperator<GlslNode> rewriter) {
		RecordComponent[] components = COMPONENTS.get(node.getClass());
		Object[] args = null;
		for (int k = 0; k < components.length; k++) {
			Object value = get(node, components[k]);
			Object transformed = transformValue(value, rewriter);
			if (transformed != value) {
				if (args == null) {
					args = new Object[components.length];
					for (int j = 0; j < components.length; j++) {
						args[j] = get(node, components[j]);
					}
				}
				args[k] = transformed;
			}
		}
		GlslNode rebuilt = args == null ? node : construct(node, args);
		GlslNode result = rewriter.apply(rebuilt);
		if (result == null) {
			throw new IllegalArgumentException("rewriter returned null for " + describe(rebuilt));
		}
		return result;
	}

	/**
	 * Rebuilds {@code node} top-down: {@code rewriter} sees each node before its children are transformed (preserving
	 * original child identities for semantic lookups), and then the returned node's children are transformed recursively.
	 */
	public static GlslNode transformTopDown(GlslNode node, UnaryOperator<GlslNode> rewriter) {
		GlslNode current = rewriter.apply(node);
		if (current == null) {
			throw new IllegalArgumentException("rewriter returned null for " + describe(node));
		}
		RecordComponent[] components = COMPONENTS.get(current.getClass());
		Object[] args = null;
		for (int k = 0; k < components.length; k++) {
			Object value = get(current, components[k]);
			Object transformed = transformValueTopDown(value, rewriter);
			if (transformed != value) {
				if (args == null) {
					args = new Object[components.length];
					for (int j = 0; j < components.length; j++) {
						args[j] = get(current, components[j]);
					}
				}
				args[k] = transformed;
			}
		}
		return args == null ? current : construct(current, args);
	}

	private static Object transformValueTopDown(Object value, UnaryOperator<GlslNode> rewriter) {
		if (value instanceof GlslNode n) {
			return transformTopDown(n, rewriter);
		}
		if (value instanceof List<?> list) {
			List<Object> out = null;
			for (int i = 0; i < list.size(); i++) {
				Object element = list.get(i);
				Object transformed = element instanceof GlslNode n ? transformTopDown(n, rewriter) : element;
				if (transformed != element && out == null) {
					out = new ArrayList<>(list.subList(0, i));
				}
				if (out != null) {
					out.add(transformed);
				}
			}
			return out == null ? value : List.copyOf(out);
		}
		return value;
	}

	private static Object transformValue(Object value, UnaryOperator<GlslNode> rewriter) {
		if (value instanceof GlslNode n) {
			return transform(n, rewriter);
		}
		if (value instanceof List<?> list) {
			List<Object> out = null;
			for (int i = 0; i < list.size(); i++) {
				Object element = list.get(i);
				Object transformed = element instanceof GlslNode n ? transform(n, rewriter) : element;
				if (transformed != element && out == null) {
					out = new ArrayList<>(list.subList(0, i));
				}
				if (out != null) {
					out.add(transformed);
				}
			}
			return out == null ? value : List.copyOf(out);
		}
		return value;
	}

	private static GlslNode construct(GlslNode node, Object[] args) {
		try {
			return (GlslNode) CONSTRUCTORS.get(node.getClass()).newInstance(args);
		} catch (IllegalArgumentException e) {
			// The constructor rejected a child: a rewriter returned a node of the wrong kind for this slot.
			throw new IllegalArgumentException("rewriter returned a node of the wrong kind inside " + describe(node), e);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot rebuild " + describe(node), e);
		}
	}

	private static String describe(GlslNode node) {
		return node.getClass().getSimpleName() + " at " + node.pos();
	}

	private static boolean isPositionComponent(RecordComponent c) {
		return c.getType() == Pos.class || c.getName().equals("namePositions");
	}

	private static Object get(Object record, RecordComponent c) {
		try {
			return c.getAccessor().invoke(record);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot read record component " + c.getName(), e);
		}
	}
}
