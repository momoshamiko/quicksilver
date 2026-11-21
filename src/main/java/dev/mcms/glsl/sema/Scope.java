// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A lexical scope: names to symbols, with a parent. Declaration before use is the caller's walk order; this
 * class only resolves names that were declared (or pre-seeded built-ins).
 */
final class Scope {
	private final Scope parent;
	private final Map<String, Symbol> names = new HashMap<>();

	Scope(Scope parent) {
		this.parent = parent;
	}

	Scope parent() {
		return parent;
	}

	/** Declares a name; null return means the name is free, otherwise the clash. */
	Symbol declare(Symbol symbol) {
		return names.putIfAbsent(symbol.name(), symbol);
	}

	/** Replaces a declaration (function groups grow their overload list). */
	void replace(Symbol symbol) {
		names.put(symbol.name(), symbol);
	}

	/** The closest declaration of a name, or null. */
	Symbol resolve(String name) {
		for (Scope s = this; s != null; s = s.parent) {
			Symbol found = s.names.get(name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/** True when this exact scope holds the name. */
	boolean declares(String name) {
		return names.containsKey(name);
	}

	/** The names of this scope in declaration order (diagnostics). */
	Iterable<Map.Entry<String, Symbol>> entries() {
		return new ArrayList<>(names.entrySet());
	}
}
