// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

/**
 * Which gbuffers phase a pass draws in (plan.md E4, "Phases"; engine-pack-format.md section 2): the frame order runs the
 * pack's gbuffers programs in phases with {@code deferred} between them, and each phase's colour attachments are the
 * union of the targets its programs write ({@code colortex0} at attachment 0 so a game pipeline's own shader can write
 * it; split by attachment set above 8, ADR 0006 section 3).
 *
 * <ul>
 * <li>{@link #SKY}: the game's "Sky" pass ({@code gbuffers_skybasic}, {@code gbuffers_skytextured}).
 * <li>{@link #DISTANT}: Distant Horizons' opaque terrain ({@code dh_terrain}), drawn by the engine after the sky and before the solid world
 * (plan.md E7). It writes into the opaque phase's pictures (no flip between the two).
 * <li>{@link #OPAQUE}: the "Solid" main pass up to the phase hook (the opaque gbuffers programs).
 * <li>{@link #TRANSLUCENT}: the same pass after the split (translucent terrain, clouds, weather, world border), and the
 * see-through, always-on-top, screen-effects and 3D-crosshair passes (the translucent phase is current from the hook to
 * the end of the level). Distant Horizons' transparent terrain ({@code dh_water}) is drawn in the phase hook, before the game's
 * translucent draws and the pack's translucent flips (after the level only on the fallback frames).
 * </ul>
 */
public enum GbufferPhase {
	SKY, DISTANT, OPAQUE, TRANSLUCENT
}
