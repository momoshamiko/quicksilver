// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The hand draws' registration against the takeover's delegate (review N1): a {@link GbufferDelegate} lives only as
 * long as its set, and {@code HandRenderer.close()} runs when the engine has already left {@code ACTIVE} (the
 * frame-end hook) — or never runs at all (a dimension change to a stored set: Engine releases and installs in one
 * call, the state stays {@code ACTIVE}). So the registration must follow the delegate instance, not a boolean: a new
 * delegate is registered anew and the old one's registration is cleared, whatever {@code active()} answers.
 */
class HandRegistrationTest {
	private final HandRenderer renderer = HandRenderer.get();

	@AfterEach
	void cleanUp() {
		this.renderer.close();
	}

	@Test
	void aSecondDelegateIsRegisteredAnewAndTheFirstCleared() {
		GbufferDelegate first = new GbufferDelegate(null, null);
		this.renderer.publishTo(first);
		Object firstDraws = first.handDraws();
		assertNotNull(firstDraws, "the first delegate holds the hand draws");

		GbufferDelegate second = new GbufferDelegate(null, null);
		this.renderer.publishTo(second);
		assertNull(first.handDraws(), "the released set's delegate holds no hand draws");
		assertNull(first.handProjection(), "the released set's delegate holds no hand projection");
		assertNotNull(second.handDraws(), "the new delegate holds the hand draws");
		assertNotNull(second.handProjection(), "the new delegate holds the hand projection");
		assertSame(firstDraws, second.handDraws(), "the same hand-draws object serves every delegate");

		// republishing to the same delegate registers nothing anew (the projection alone moves)
		second.setHandProjection(null);
		this.renderer.publishTo(second);
		assertNotNull(second.handDraws());
		assertNotNull(second.handProjection());
	}

	@Test
	void closeResetsTheRegistrationWithoutAnActiveDelegate() {
		GbufferDelegate first = new GbufferDelegate(null, null);
		this.renderer.publishTo(first);
		// no delegate is active here (active() is null), as at the frame-end hook after the engine left ACTIVE
		this.renderer.close();
		assertNull(first.handDraws(), "close() clears the registration although active() is null");

		GbufferDelegate second = new GbufferDelegate(null, null);
		this.renderer.publishTo(second);
		assertNotNull(second.handDraws(), "the next set's delegate is registered again after close()");
	}
}
