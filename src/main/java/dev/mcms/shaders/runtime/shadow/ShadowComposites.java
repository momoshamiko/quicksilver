// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import org.jspecify.annotations.Nullable;

/**
 * The hook the shadow pass's {@code shadowcomp} step calls (E5 review item 15): the set's shadowcomp passes run on the
 * frame's encoder right after the shadow pass and its mip fill. The steps live in {@link ShadowPassOps}, which has no
 * reference to the set's frame, so the engine attaches the set's runner here while the set is active, the same way
 * {@code ShadowTerrain} and {@code ShadowEntities} attach their state. Render thread only.
 */
public final class ShadowComposites {

	/** Runs the set's shadowcomp passes on {@code enc}. */
	public interface Runner {
		void run(long enc);
	}

	private static final ShadowComposites INSTANCE = new ShadowComposites();

	private @Nullable Runner runner;

	private ShadowComposites() {
	}

	/** The process's hook. */
	public static ShadowComposites get() {
		return INSTANCE;
	}

	/** Attaches the active set's runner (replacing any earlier one). */
	public void attach(Runner runner) {
		this.runner = runner;
	}

	/** Detaches the runner (the set's release): the shadow pass then runs no shadowcomp pass. */
	public void detach() {
		this.runner = null;
	}

	/** Runs the attached set's shadowcomp passes; nothing when no set is attached. */
	public void run(long enc) {
		Runner current = this.runner;
		if (current != null) {
			current.run(enc);
		}
	}
}
