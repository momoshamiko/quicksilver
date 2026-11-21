// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class DistantProjectionTest {

	private static final float EPS = 1e-4f;

	/**
	 * DH's reverse-Z projection built from an OpenGL one: the same eye-space map, with the clip z row replaced so that
	 * {@code row3 = P.row3} and {@code row2 = (P.row3 - P.row2) / 2}. Its depth runs near 1, far 0.
	 */
	static Matrix4f reverseFromOpenGl(Matrix4f p) {
		Matrix4f r = new Matrix4f(p);
		for (int c = 0; c < 4; c++) {
			r.set(c, 3, p.get(c, 3));
			r.set(c, 2, (p.get(c, 3) - p.get(c, 2)) / 2.0f);
		}
		return r;
	}

	static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				assertEquals(expected.get(c, r), actual.get(c, r), EPS, "entry column " + c + " row " + r);
			}
		}
	}

	@Test
	void reverseZeroToOneConvertsBackToOpenGl() {
		Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 4096f, false);
		assertMatrixEquals(gl, DistantProjection.toOpenGl(reverseFromOpenGl(gl)));
	}

	@Test
	void nearMapsToMinusOneAndFarToOneAfterConversion() {
		float near = 0.05f;
		float far = 4096f;
		Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, near, far, false);
		Matrix4f converted = DistantProjection.toOpenGl(reverseFromOpenGl(gl));
		assertEquals(-1f, ndcZ(converted, new Vector3f(0, 0, -near)), EPS);
		assertEquals(1f, ndcZ(converted, new Vector3f(0, 0, -far)), EPS);
	}

	@Test
	void theDhProjectionIsReverseZBeforeConversion() {
		float near = 0.05f;
		float far = 4096f;
		Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, near, far, false);
		Matrix4f dh = reverseFromOpenGl(gl);
		assertEquals(1f, ndcZ(dh, new Vector3f(0, 0, -near)), EPS, "DH's near is 1");
		assertEquals(0f, ndcZ(dh, new Vector3f(0, 0, -far)), EPS, "DH's far is 0");
	}

	/** The normalised device z of an eye-space point under a projection (clip z over w). */
	static float ndcZ(Matrix4f projection, Vector3f eye) {
		Vector4f clip = projection.transform(new Vector4f(eye, 1f));
		return clip.z / clip.w;
	}

	@Test
	void modelViewTranslatesByOffsetMinusCameraAndKeepsTheRotation() {
		Matrix4f rotation = new Matrix4f().rotateX(0.3f).rotateY(1.1f);
		double ox = 1024.0, oy = -64.0, oz = 2048.0;
		double cx = 1000.5, cy = 70.25, cz = 2000.75;
		Matrix4f mv = DistantProjection.modelView(rotation, ox, oy, oz, cx, cy, cz);
		// The pack's vertex stage: gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex. The rotation cancels.
		Matrix4f inverse = new Matrix4f(rotation).invert();
		Vector3f v = new Vector3f(3f, 40f, 17f);
		Vector3f position = inverse.mul(mv, new Matrix4f()).transformPosition(new Vector3f(v));
		assertEquals(v.x + (ox - cx), position.x, 1e-3f);
		assertEquals(v.y + (oy - cy), position.y, 1e-3f);
		assertEquals(v.z + (oz - cz), position.z, 1e-3f);
	}

	@Test
	void theInputRotationIsNotChanged() {
		Matrix4f rotation = new Matrix4f().rotateY(0.5f);
		Matrix4f before = new Matrix4f(rotation);
		DistantProjection.modelView(rotation, 10, 20, 30, 1, 2, 3);
		assertMatrixEquals(before, rotation);
	}

	@Test
	void aRowMajorArrayFromDhIsReadAsTheMatrixItDescribes() {
		Matrix4f matrix = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.25f, 4096f);
		// DH's getValuesAsArray: element 4 * row + column holds m(row, column), which JOML calls get(column, row).
		float[] rowMajor = new float[16];
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				rowMajor[4 * row + column] = matrix.get(column, row);
			}
		}
		assertMatrixEquals(matrix, DistantProjection.fromRowMajor(rowMajor, new Matrix4f()));
		// The column-major reading would hand the pack the transpose: the perspective matrix is not symmetric, so the two differ.
		assertFalse(new Matrix4f().set(rowMajor).equals(matrix, EPS), "a plain column-major read must not match");
	}
}
