// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

/**
 * Which entities the shadow pass draws (plan.md E5, "Entities for the shadow"). The shadow list is a second extraction
 * over the level's entities with the light's {@code Frustum}, after the camera's {@code extractVisibleEntities}, into an
 * engine list. The visibility rule mirrors the camera extraction's ({@code LevelExtractor.extractVisibleEntities}, javap
 * of minecraft-merged-deobf-26.3.jar, 2026-11-14, bytecodes 21-56 and 190-249) except where the light needs another
 * answer:
 *
 * <ul>
 * <li>Visibility is the light's frustum, or the player's indirect passenger ({@code shouldRender || hasIndirectPassenger},
 * bytecodes 21-56, as the camera does). The camera's section-visibility gate ({@code isSectionCompiledAndVisible},
 * bytecodes 78-93) is not an input: a caster behind the camera still shades what the light sees.</li>
 * <li>{@code shadowDistanceRenderMul} culls by distance from the camera when the pack sets it (a non-negative
 * {@link ShadowCamera#cullRadius}); otherwise no distance cull.</li>
 * <li>Another {@code LocalPlayer} is skipped, as the camera does (bytecodes 232-249).</li>
 * <li>The switches (the Ordering page of shaders.properties): {@code shadowEntities} keeps the entities;
 * {@code shadowPlayer} keeps the player and the entity the player rides. The page says {@code shadowPlayer} is forced
 * on while {@code shadowEntities} is on. With the entities off and the player on, only the player and its vehicle or
 * mount are kept.</li>
 * </ul>
 *
 * <p>Pure: the caller reads the facts from the game's entity and passes them in, so the rules are tested without the
 * game. The side effects of the camera loop (the view scale and the {@code xOld}/{@code yOld}/{@code zOld} reset of a
 * new entity) are not repeated here: the view scale is set once per frame by the camera extraction, and the reset is
 * idempotent (the same values), so it is applied only where the camera loop did not reach the entity.
 */
public final class ShadowEntitySelection {

	/**
	 * The facts about one entity the rules read. A kept {@code isLocalPlayer} entity is always the camera's entity (the
	 * camera loop skips every other {@code LocalPlayer}). Distances are squared, from the camera.
	 */
	public record Facts(boolean inLightFrustum, boolean carriesPlayer, boolean isCameraEntity, boolean isLocalPlayer,
			double distanceSquared) {
	}

	private ShadowEntitySelection() {
	}

	/**
	 * Whether the shadow pass keeps the entity.
	 *
	 * @param facts      the entity's facts
	 * @param entities   the pack's {@code shadowEntities} switch
	 * @param player     the pack's {@code shadowPlayer} switch (forced on while {@code entities} is on)
	 * @param cullRadius the render-distance cull in blocks, or a negative value for none ({@link ShadowCamera#cullRadius})
	 */
	public static boolean keep(Facts facts, boolean entities, boolean player, double cullRadius) {
		if (!(facts.inLightFrustum() || facts.carriesPlayer())) {
			return false;
		}
		if (cullRadius >= 0 && facts.distanceSquared() > cullRadius * cullRadius) {
			return false;
		}
		if (facts.isLocalPlayer() && !facts.isCameraEntity()) {
			return false;
		}
		// The Ordering page: shadowPlayer is forced on while shadowEntities is on.
		boolean playerOn = entities || player;
		if (facts.isLocalPlayer() || facts.carriesPlayer()) {
			return playerOn;  // the player's own body (asleep, detached or not), and the vehicle or mount it rides
		}
		return entities;
	}

	/**
	 * Whether {@link #keep} can keep the entity at all, whatever its frustum test says. With the entities switch off only the
	 * player and the vehicle it rides can be kept, so any other entity is dropped before its frustum test (E5 review item 11).
	 */
	public static boolean mayKeep(boolean entities, boolean localPlayer, boolean carriesPlayer) {
		return entities || localPlayer || carriesPlayer;
	}
}
