// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hand frame's lifecycle (plan.md E4, "Hand", the frame-order bullet): one prepared frame per frame at most, the
 * translucent draw closes it, the after-level hook closes what never drew (guards, early exits), the buffer's
 * {@code endFrame} runs every frame, and no prepared frame leaks into the next frame. A fake dispatcher behind
 */
class HandFrameCoordinatorTest {
	private static final Object PASS = new Object();
	private static final class FakeDispatcher implements HandDispatcher {
		final List<String> calls = new ArrayList<>();
		boolean empty = true;
		/** True while a frame is prepared and not closed: a second prepare would throw in the game (its one shared
		 * PreparedFrame is marked in use). */
		boolean frameInUse;

		@Override public void setFrameInputs(net.minecraft.client.renderer.state.level.CameraRenderState camera,
				net.minecraft.client.renderer.state.GameRenderState state,
				net.minecraft.client.renderer.state.level.PlayerRenderState player) {
		}

		@Override public void submitAndPrepare() {
			if (this.frameInUse) {
				throw new IllegalStateException("PreparedFrame already in use");
			}
			this.calls.add("submitAndPrepare");
			this.frameInUse = true;
		}

		@Override public boolean preparedEmpty() {
			return this.empty;
		}

		@Override public void executeSolid(Object renderPass) {
			this.calls.add("solid");
		}

		@Override public void executeTranslucent(Object renderPass) {
			this.calls.add("translucent");
		}

		@Override public void closeFrame() {
			this.calls.add("closeFrame");
			this.frameInUse = false;
		}
		@Override public void bufferEndFrame() {
			this.calls.add("bufferEndFrame");
		}

		@Override public void close() {
			this.calls.add("close");
		}
	}

	@Test
	void aNormalFramePreparesDrawsSolidDrawsTranslucentAndCloses() {
		FakeDispatcher d = new FakeDispatcher();
		d.empty = false;
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertTrue(c.prepare());
		c.drawSolid(PASS);
		c.drawTranslucent(PASS);
		c.bufferEndFrame();
		assertEquals(List.of("submitAndPrepare", "solid", "translucent", "closeFrame", "bufferEndFrame"), d.calls);
		assertFalse(c.frameOpen());
	}

	@Test
	void translucentWithoutSolidStillDrawsAndCloses() {
		FakeDispatcher d = new FakeDispatcher();
		d.empty = false;
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertTrue(c.prepare());
		c.drawTranslucent(PASS);
		assertEquals(List.of("submitAndPrepare", "translucent", "closeFrame"), d.calls);
	}

	@Test
	void aGuardSkippedFrameDrawsNothingAndEndsOnlyTheBuffer() {
		FakeDispatcher d = new FakeDispatcher();
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		// the renderItemInHand guards failed: the coordinator's prepare is never reached
		c.drawSolid(PASS); // the phase hook: a no-op without a prepared frame
		c.drawTranslucent(PASS); // the redirect: a no-op too
		c.closeIfOpen();
		c.bufferEndFrame();
		assertEquals(List.of("bufferEndFrame"), d.calls);
	}

	@Test
	void anEarlyExitAfterPrepareIsClosedAtTheAfterLevelHook() {
		FakeDispatcher d = new FakeDispatcher();
		d.empty = false;
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertTrue(c.prepare());
		c.drawSolid(PASS);
		// the translucent draw never ran (an early exit before render3dHud's call at bytecode 141)
		c.closeIfOpen();
		c.bufferEndFrame();
		assertEquals(List.of("submitAndPrepare", "solid", "closeFrame", "bufferEndFrame"), d.calls);
		assertFalse(c.frameOpen());
	}

	@Test
	void aFrameLeftOpenByASkippedCloseCannotLeakIntoTheNextFrame() {
		FakeDispatcher d = new FakeDispatcher();
		d.empty = false;
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertTrue(c.prepare());
		// neither the translucent draw nor the after-level hook ran: the next prepare must close the old frame first
		assertTrue(c.prepare());
		assertEquals(List.of("submitAndPrepare", "closeFrame", "submitAndPrepare"), d.calls);
		c.drawTranslucent(PASS);
		assertEquals(List.of("submitAndPrepare", "closeFrame", "submitAndPrepare", "translucent", "closeFrame"), d.calls);
	}

	@Test
	void anEmptyFrameIsClosedSoTheNextPrepareDoesNotThrow() {
		FakeDispatcher d = new FakeDispatcher();
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertFalse(c.prepare()); // the prepared frame is empty (spyglass scoping, invisibility with an empty hand)
		assertEquals(List.of("submitAndPrepare", "closeFrame"), d.calls);
		c.drawSolid(PASS);
		c.drawTranslucent(PASS);
		c.closeIfOpen();
		assertEquals(List.of("submitAndPrepare", "closeFrame"), d.calls);
		// the game's one shared PreparedFrame was marked in use by prepareFrame: with the close above, the next
		// frame's prepare does not throw (the fake throws while a frame is in use)
		d.empty = false;
		assertTrue(c.prepare());
	}

	@Test
	void closeEndsAnOpenFrameThenTheDispatcherOnce() {
		FakeDispatcher d = new FakeDispatcher();
		d.empty = false;
		HandFrameCoordinator c = new HandFrameCoordinator(d);
		assertTrue(c.prepare());
		c.close();
		c.close(); // idempotent
		assertEquals(List.of("submitAndPrepare", "closeFrame", "close"), d.calls);
		assertFalse(c.prepare()); // closed for good
		assertEquals(List.of("submitAndPrepare", "closeFrame", "close"), d.calls);
	}
}
