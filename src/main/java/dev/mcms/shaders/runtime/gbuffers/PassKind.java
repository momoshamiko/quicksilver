// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

/**
 * Which pass a draw belongs to (plan.md E4, the pipeline mapping bullet): the game's terrain location ids serve the world
 * and the shadow pass, and the hand's entity and item pipelines are the world's, so the pass kind is part of the mapping
 * key that selects the pack program.
 *
 * <ul>
 * <li>{@link #GBUFFERS}: the world passes (sky, main solid, main translucent, see-through, always-on-top, screen
 * effects, the 3D crosshair). This is the default outside a {@link GbufferDelegate#passKind} window.
 * <li>{@link #HAND}: the first-person hand (E4-C). The same entity and item pipelines map to {@code gbuffers_hand} and
 * {@code gbuffers_hand_water}, and the hand projection is current.
 * <li>{@link #SHADOW}: the shadow pass (E5): the terrain and entity pipelines map to the {@code shadow_*} programs.
 * </ul>
 */
public enum PassKind {
	GBUFFERS, HAND, SHADOW
}
