// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import java.util.List;

/**
 * The result of mapping one resolved GLSL built-in call to MSL (W2-C, ADR 0006): either the MSL expression text plus
 * the names of prelude helpers the expression needs, or the reason the call is not supported. Unsupported is a
 * per-overload fact of this engine, not an error: the translator logs it and the fallback chain applies.
 */
public sealed interface Mapping {

	/** The MSL expression; helpers are keys into {@link Prelude} (namespace {@code qs}). */
	record Mapped(String msl, List<String> helpers) implements Mapping {
		public Mapped {
			helpers = List.copyOf(helpers);
		}

		public static Mapped of(String msl) {
			return new Mapped(msl, List.of());
		}

		public static Mapped of(String msl, String helper) {
			return new Mapped(msl, List.of(helper));
		}
	}

	/** Why this overload is not mapped; shown to the pack author in the engine's log. */
	record Unsupported(String reason) implements Mapping {
		public Unsupported {
			if (reason == null || reason.isBlank()) throw new IllegalArgumentException("unsupported needs a reason");
		}
	}
}
