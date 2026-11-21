// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether one of Distant Horizons' terrain calls may run now (plan.md E7). DH writes its uniform buffers through the frontend command
 * encoder, which refuses that while a render pass is open; the refusal makes DH disable its renderer for the session. So a gated DH call runs
 * only where no game render pass is open on the encoder ({@code MetalBridge.inRenderPass}). A call that would run inside an open pass is skipped
 * for its frame and counted; the first refusal of the session is logged, once. The opaque call goes through this gate. The transparent call
 * inside the phase hook does not: it runs with the game's open pass suspended (Engine.drawTransparentInPhase), so no pass is open for DH.
 */
public final class DistantCallGate {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	private int refusals;

	/**
	 * Whether the named call may run: true only with no render pass open.
	 *
	 * @param renderPassOpen whether a render pass is open on the encoder when the call would run
	 * @param call           the call's name, for the log ({@code "opaque"} or {@code "transparent"})
	 */
	public boolean allow(boolean renderPassOpen, String call) {
		if (!renderPassOpen) {
			return true;
		}
		if (this.refusals++ == 0) {
			LOG.warn("[mcms-shaders] Distant Horizons' {} terrain call is skipped: a render pass is open where it would run (DH would disable"
				+ " itself). DH's own calls stay cancelled while the pack serves DH, so the frame has no DH terrain of that layer", call);
		}
		return false;
	}

	/** The number of calls refused this session. */
	public int refusals() {
		return this.refusals;
	}
}
