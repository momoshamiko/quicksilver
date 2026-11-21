// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

/**
 * What a vertex layout's generated prologue needs beyond its attributes (plan.md E4, the Sodium terrain bullet; ADR
 * 0007): the region offset from Sodium's push constants and the {@code u_Globals} offsets the engine computes from
 * Sodium's own {@code globals.glsl} when the set loads. E4-B computes the values and registers them with
 * {@link VertexLayoutRegistry#register}; the engine's request builder passes them to the translator, whose vertex
 * prologue reads the region offset, adds it to the decoded position and subtracts {@code u_CameraOffset} when the block
 * declares it, as Sodium's vertex shader does (a prologue without the subtraction draws terrain that steps in whole
 * blocks, Vitrail issue #514).
 *
 * @param regionSlot     the buffer slot the region block (the push constants) is bound at; -1 when the layout needs none
 * @param regionOffset   the byte offset of the {@code vec3} region offset (the region origin minus the camera position:
 *                       its integer part on beta.5, the whole camera on beta.1) inside that block
 * @param globalsSlot    the buffer slot Sodium's {@code u_Globals} block is bound at; -1 when the layout needs none
 * @param cameraOffsetAt the byte offset of {@code u_CameraOffset} inside {@code u_Globals}; -1 when the block lacks the
 *                       field (Sodium beta.1; beta.5 declares it, ADR 0007)
 * @param modelViewAt    the byte offset of {@code u_ModelViewMatrix} inside {@code u_Globals}, which the draws of such a
 *                       layout take as their model-view: their positions are already camera-relative, so the matrix is
 *                       the view rotation alone (Sodium's own chunk shader multiplies it the same way); -1 when none
 */
public record PrologueFacts(int regionSlot, int regionOffset, int globalsSlot, long cameraOffsetAt, long modelViewAt) {

	/** A layout whose prologue needs nothing beyond its attributes (every game vertex format). */
	public static final PrologueFacts NONE = new PrologueFacts(-1, -1, -1, -1, -1);

	public boolean hasRegion() {
		return this.regionSlot >= 0;
	}

	public boolean hasGlobals() {
		return this.globalsSlot >= 0;
	}

	public boolean subtractsCameraOffset() {
		return this.cameraOffsetAt >= 0;
	}

	/** Whether the draws of this layout take Sodium's model-view from the globals mirror (see {@link #modelViewAt}). */
	public boolean servesModelView() {
		return hasGlobals() && this.modelViewAt >= 0;
	}
}
