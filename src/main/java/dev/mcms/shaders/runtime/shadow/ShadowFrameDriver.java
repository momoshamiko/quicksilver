// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shadow frame with its failure rule (E5 review item 12; ADR 0006 section 4). A shadow step that throws is logged once,
 * and the frame is dropped for the rest of the set: it runs no more, and {@code onDrop} detaches the light's lists. The
 * frame then keeps the vanilla picture, with no pack shadows.
 *
 * <p>Single owner: the render thread. {@link #run} allocates nothing.
 */
public final class ShadowFrameDriver {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	private @Nullable ShadowFrame frame;
	private final ShadowPassSequence.Content content;
	private final Runnable onDrop;

	/**
	 * @param frame  the set's shadow frame (its operations are bound)
	 * @param content the set's content switches for the pass
	 * @param onDrop run once when the frame is dropped (the engine passes {@link ShadowLists#detach})
	 */
	public ShadowFrameDriver(ShadowFrame frame, ShadowPassSequence.Content content, Runnable onDrop) {
		this.frame = frame;
		this.content = content;
		this.onDrop = onDrop;
	}

	/** Whether the frame still runs (false once a step has failed). */
	public boolean active() {
		return this.frame != null;
	}

	/** Runs this frame's shadow pass. A failing step drops the frame ({@link #drop}); a dropped frame does nothing. */
	public void run() {
		ShadowFrame current = this.frame;
		if (current == null) {
			return;
		}
		try {
			current.run(this.content);
		} catch (Throwable t) {
			drop("the shadow pass", t);
		}
	}

	/**
	 * Drops the frame for the rest of the set: logs the failed step {@code what} with its cause once, forgets the frame and runs
	 * {@code onDrop}. Calls after the first do nothing, so the log and the detach happen once.
	 */
	public void drop(String what, Throwable cause) {
		if (this.frame == null) {
			return;
		}
		this.frame = null;
		LOG.error("[mcms-shaders] {} failed; the set's shadows are skipped from now on", what, cause);
		this.onDrop.run();
	}
}
