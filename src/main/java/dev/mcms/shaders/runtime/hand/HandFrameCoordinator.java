// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import dev.mcms.shaders.runtime.Engine;
import org.jspecify.annotations.Nullable;

/**
 * The hand frame's lifecycle (plan.md E4, "Hand", the frame-order bullet): one prepared frame per frame at most, and
 * it never stays open into the next frame. States: {@code IDLE} (no frame open), {@code PREPARED} (submitted and
 * prepared at {@code LevelRenderer.render} bytecode 770), {@code DRAWN} (the solid phase drawn at the phase hook; the
 * frame is still open for the translucent phases).
 *
 * <p>The translucent draw at {@code render3dHud} bytecode 141 closes the frame; when it does not run — the
 * {@code renderItemInHand} guards failed, or the frame path exited early — the after-level hook
 * ({@link Engine#afterLevel}, E3's hook right after {@code renderLevel}) closes it instead, so no frame leaks. The
 * buffer's {@code endFrame} runs every frame whatever the state, and a {@link #prepare()} while a frame is still open
 * closes it first (the leak guard the tests check).
 */
public final class HandFrameCoordinator {
	private static final int IDLE = 0, PREPARED = 1, DRAWN = 2;

	private final HandDispatcher dispatcher;
	private int state = IDLE;
	private boolean closed;

	public HandFrameCoordinator(HandDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	/** A frame is open (prepared, with or without the solid phase drawn). */
	public boolean frameOpen() {
		return this.state != IDLE;
	}

	/**
	 * Submits and prepares the hand frame ({@code LevelRenderer.render} bytecode 770, under the
	 * {@code renderItemInHand} guards). A frame still open from a frame that never drew is closed first. False: the
	 * frame is empty (or the coordinator is closed) and nothing is open.
	 */
	public boolean prepare() {
		if (this.closed) {
			return false;
		}
		if (this.state != IDLE) {
			this.dispatcher.closeFrame();
			this.state = IDLE;
		}
		this.dispatcher.submitAndPrepare();
		if (this.dispatcher.preparedEmpty()) {
			// Empty is not unused: the dispatcher's one shared PreparedFrame is already marked in use by
			// prepareFrame (the game closes it too, render3dHud bytecode 205 -> 306); without this close the next
			// prepareFrame throws IllegalStateException("PreparedFrame already in use") inside LevelRenderer.render
			// (spyglass scoping, invisibility with an empty hand).
			this.dispatcher.closeFrame();
			return false;
		}
		this.state = PREPARED;
		return true;
	}

	/** The solid hand, at the phase hook; a no-op when no frame was prepared this frame. */
	public void drawSolid(Object renderPass) {
		if (this.state == PREPARED) {
			this.dispatcher.executeSolid(renderPass);
			this.state = DRAWN;
		}
	}

	/**
	 * The translucent hand, in the redirected engine pass at {@code render3dHud} bytecode 141; closes the frame, so it
	 * never stays open into the next frame. A no-op when no frame was prepared.
	 */
	public void drawTranslucent(Object renderPass) {
		if (this.state == PREPARED || this.state == DRAWN) {
			this.dispatcher.executeTranslucent(renderPass);
			this.dispatcher.closeFrame();
			this.state = IDLE;
		}
	}

	/** The after-level close (E3's hook): a frame the translucent draw never ran is closed here. */
	public void closeIfOpen() {
		if (this.state != IDLE) {
			this.dispatcher.closeFrame();
			this.state = IDLE;
		}
	}

	/** The staged buffer's {@code endFrame} ({@code GameRenderer.render} bytecode 379), every frame while it exists. */
	public void bufferEndFrame() {
		this.dispatcher.bufferEndFrame();
	}

	/** Closes the dispatcher and its buffer (engine off, pack switch, resource reload, device close); idempotent. */
	public void close() {
		if (this.closed) {
			return;
		}
		this.closeIfOpen();
		this.dispatcher.close();
		this.closed = true;
	}
}
