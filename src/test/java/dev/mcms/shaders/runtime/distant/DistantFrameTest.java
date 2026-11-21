// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * The per-frame Distant Horizons state (plan.md E7): the projection converted to OpenGL's convention, its inverse, last frame's
 * projection (the first frame's own), the clip planes, and the model-view of each draw.
 */
class DistantFrameTest {

	private static final float EPS = 1e-5f;

	/** A DH-style projection (any invertible matrix serves: the frame converts it, it does not build it). */
	private static Matrix4f projectionA() {
		return new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.25f, 4096f);
	}

	private static Matrix4f projectionB() {
		return new Matrix4f().perspective((float) Math.toRadians(90), 4f / 3f, 0.5f, 2048f);
	}

	@Test
	void nothingIsProjectedBeforeTheFirstSet() {
		assertFalse(new DistantFrame().projected());
	}

	@Test
	void theProjectionIsTheOpenGlConversionAndTheInverseInvertsIt() {
		DistantFrame frame = new DistantFrame();
		frame.setProjection(projectionA(), 0.25f, 4096f, 256);
		assertTrue(frame.projected());
		Matrix4f expected = DistantProjection.toOpenGl(projectionA());
		assertTrue(frame.projection().equals(expected, EPS), "the projection is DH's, in OpenGL's convention");
		assertTrue(frame.values().projectionInverse().equals(expected.invert(new Matrix4f()), EPS), "the inverse");
	}

	@Test
	void thePreviousProjectionIsTheCurrentOneOnTheFirstFrameAndLastFramesAfter() {
		DistantFrame frame = new DistantFrame();
		frame.setProjection(projectionA(), 0.25f, 4096f, 256);
		assertTrue(frame.values().previousProjection().equals(DistantProjection.toOpenGl(projectionA()), EPS),
			"the first frame has no earlier projection");
		frame.setProjection(projectionB(), 0.5f, 2048f, 128);
		assertTrue(frame.values().previousProjection().equals(DistantProjection.toOpenGl(projectionA()), EPS),
			"the previous projection is the earlier frame's");
		assertTrue(frame.projection().equals(DistantProjection.toOpenGl(projectionB()), EPS));
	}

	@Test
	void theValuesCarryTheClipPlanesAndTheRenderDistance() {
		DistantFrame frame = new DistantFrame();
		frame.setProjection(projectionA(), 0.25f, 4096f, 256);
		DistantUniforms.Values values = frame.values();
		assertEquals(0.25f, values.nearPlane());
		assertEquals(4096f, values.farPlane());
		assertEquals(256, values.renderDistanceBlocks());
	}

	@Test
	void theDrawModelViewIsTheViewRotationTimesTheDrawOffset() {
		DistantFrame frame = new DistantFrame();
		Matrix4f rotation = new Matrix4f().rotateY(0.5f).rotateX(-0.2f);
		frame.setViewRotation(rotation);
		frame.setDrawOffset(1f, 2f, 3f);
		Matrix4f expected = new Matrix4f(rotation).translate(1f, 2f, 3f);
		assertTrue(frame.drawModelView().equals(expected, EPS));
		frame.setDrawOffset(-4f, 0f, 8f);
		assertTrue(frame.drawModelView().equals(new Matrix4f(rotation).translate(-4f, 0f, 8f), EPS),
			"each draw takes its own offset");
	}
}
