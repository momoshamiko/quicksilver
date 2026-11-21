// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import dev.mcms.shaders.runtime.gbuffers.EngineRedirector;
import org.jspecify.annotations.Nullable;

/**
 * The shadow pass's colour attachments for the redirector (E5): the shadow targets' {@code shadowcolor} buffers in the
 * order of the shadow layout ({@code GbufferPlans.shadowLayout()}: the indices the shadow programs write, ascending), and
 * the {@code shadowtex0} depth map. Refilled per call into one array the object keeps, so a pass allocates nothing; the
 * frame's first pass takes the clears the targets armed ({@link ShadowTargets#clearForPass}).
 */
public final class ShadowPassColours implements EngineRedirector.ShadowColours {

	private final ShadowTargets targets;
	private final int[] layout;
	private final long[] attachments;
	private final float[][] clears;

	/**
	 * @param targets the set's shadow targets (created before the redirector draws)
	 * @param layout  the shadowcolor index of each attachment, in attachment order
	 * @throws IllegalArgumentException a layout index names a buffer the targets do not hold
	 */
	public ShadowPassColours(ShadowTargets targets, int[] layout) {
		for (int index : layout) {
			if (index < 0 || index >= targets.colorCount()) {
				throw new IllegalArgumentException("the shadow layout writes shadowcolor" + index + ", which the targets do not hold");
			}
		}
		this.targets = targets;
		this.layout = layout.clone();
		this.attachments = new long[layout.length];
		this.clears = new float[layout.length][];
	}

	@Override
	public long[] colours(long[] out) {
		for (int a = 0; a < this.layout.length; a++) {
			this.attachments[a] = this.targets.color(this.layout[a]);
		}
		return this.attachments;
	}

	@Override
	public long depth() {
		return this.targets.depth(0);
	}

	@Override
	public int resolution() {
		return this.targets.resolution();
	}

	@Override
	public float @Nullable [][] takeClears() {
		return this.targets.takePassClear() ? this.targets.passClears(this.layout, this.clears) : null;
	}
}
