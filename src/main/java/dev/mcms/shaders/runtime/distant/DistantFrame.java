// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The Distant Horizons state one frame's {@code dh_} programs draw with (plan.md E7, "Distant Horizons, as built"): the
 * projection DH drew with (converted to OpenGL's depth convention by {@link DistantProjection#toOpenGl}), its inverse and
 * last frame's, the clip planes and the render distance for {@code QsDh}; the game's view rotation of the frame; and the
 * offset of the draw in progress (DH's {@code modelPos}, the section's corner minus the camera, in blocks).
 *
 * <p>The engine fills it at DH's terrain render (the projection, once per frame), at its own DH call (the view rotation)
 * and at each draw (the offset). Pure: no Minecraft or DH class, so the matrices are unit-tested.
 */
public final class DistantFrame {

	private final Matrix4f viewRotation = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private final Matrix4f drawModelView = new Matrix4f();
	private final float[] drawOffset = new float[3];
	private float nearPlane;
	private float farPlane;
	private int renderDistanceBlocks;
	private boolean projected;

	/** The game's view rotation of the frame (the model-view without the camera translation). */
	public void setViewRotation(Matrix4fc rotation) {
		this.viewRotation.set(rotation);
	}

	/**
	 * Sets the projection DH draws this frame with. {@code reverseZeroToOne} is DH's projection (reverse-Z, zero to one, as
	 * {@code DhApiRenderParam.dhProjectionMatrix} gives it). The previous frame's projection becomes
	 * {@code dhPreviousProjection}; on the first frame it is the current one.
	 */
	public void setProjection(Matrix4fc reverseZeroToOne, float near, float far, int renderDistanceBlocks) {
		Matrix4f current = DistantProjection.toOpenGl(new Matrix4f(reverseZeroToOne));
		this.previousProjection.set(this.projected ? this.projection : current);
		this.projection.set(current);
		this.projection.invert(this.projectionInverse);
		this.nearPlane = near;
		this.farPlane = far;
		this.renderDistanceBlocks = renderDistanceBlocks;
		this.projected = true;
	}

	/** Whether a projection was set (the engine draws no DH terrain before DH's first render). */
	public boolean projected() {
		return this.projected;
	}

	/** The offset of the draw in progress: DH's model position (block units, relative to the camera). */
	public void setDrawOffset(float x, float y, float z) {
		this.drawOffset[0] = x;
		this.drawOffset[1] = y;
		this.drawOffset[2] = z;
	}

	/** The draw's model-view: the view rotation times the translation to the draw's offset. Reused, not copied. */
	public Matrix4f drawModelView() {
		this.drawModelView.set(this.viewRotation).translate(this.drawOffset[0], this.drawOffset[1], this.drawOffset[2]);
		return this.drawModelView;
	}

	/** The projection in OpenGL's convention (the engine's own object: copy it before keeping it across frames). */
	public Matrix4f projection() {
		return this.projection;
	}

	/** The projection's inverse (the engine's own object). */
	public Matrix4fc projectionInverse() {
		return this.projectionInverse;
	}

	/** Last frame's projection (the engine's own object). */
	public Matrix4fc previousProjection() {
		return this.previousProjection;
	}

	/** DH's near clip plane in blocks. */
	public float nearPlane() {
		return this.nearPlane;
	}

	/** DH's far clip plane in blocks. */
	public float farPlane() {
		return this.farPlane;
	}

	/** DH's render distance in blocks. */
	public int renderDistanceBlocks() {
		return this.renderDistanceBlocks;
	}

	/** The {@code QsDh} values of this frame. */
	public DistantUniforms.Values values() {
		return new DistantUniforms.Values(this.nearPlane, this.farPlane, this.renderDistanceBlocks, this.projection,
			this.projectionInverse, this.previousProjection);
	}
}
