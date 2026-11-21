// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shadow entity selection rules (plan.md E5, "Entities for the shadow"; the Ordering page's {@code shadowEntities} and
 * {@code shadowPlayer} switches; see {@link ShadowEntitySelection}).
 */
final class ShadowEntitySelectionTest {

	private static final double NO_CULL = -1;

	/** A plain entity, next to the camera, not the camera's own. */
	private static ShadowEntitySelection.Facts caster(boolean inFrustum) {
		return new ShadowEntitySelection.Facts(inFrustum, false, false, false, 4.0);
	}

	/** The local player's own body (the camera's entity; a {@code LocalPlayer} the camera loop skips is not this). */
	private static ShadowEntitySelection.Facts player() {
		return new ShadowEntitySelection.Facts(true, false, true, true, 0.0);
	}

	/** The entity the player rides: it carries the player, and is an ordinary entity for the camera's list. */
	private static ShadowEntitySelection.Facts vehicle(boolean inFrustum) {
		return new ShadowEntitySelection.Facts(inFrustum, true, false, false, 4.0);
	}

	@Test
	void anEntityInTheLightFrustumIsKeptWhenEntitiesAreOn() {
		assertTrue(ShadowEntitySelection.keep(caster(true), true, false, NO_CULL));
	}

	@Test
	void theLightFrustumAloneDecidesAPlainEntity() {
		// Only the light's frustum is a visibility fact: the camera's sections and its frustum are not inputs.
		assertFalse(ShadowEntitySelection.keep(caster(false), true, true, NO_CULL));
	}

	@Test
	void aPlainEntityIsDroppedWhenEntitiesAreOff() {
		assertFalse(ShadowEntitySelection.keep(caster(true), false, true, NO_CULL));
	}

	@Test
	void aVehicleCarryingThePlayerIsKeptOutsideTheFrustum() {
		// The game keeps a player's vehicle or mount whatever the frustum says (shouldRender || hasIndirectPassenger).
		assertTrue(ShadowEntitySelection.keep(vehicle(false), true, false, NO_CULL));
	}

	@Test
	void theCullRadiusDropsFartherEntitiesOnlyWhenItIsSet() {
		var far = new ShadowEntitySelection.Facts(true, false, false, false, 400.0 * 400.0);
		assertFalse(ShadowEntitySelection.keep(far, true, true, 160.0));
		assertTrue(ShadowEntitySelection.keep(far, true, true, NO_CULL));
	}

	@Test
	void shadowPlayerIsForcedOnWhileShadowEntitiesIsOn() {
		// The Ordering page: "This is forced on if shadowEntities is enabled." The player keeps its shadow with the switch off.
		assertTrue(ShadowEntitySelection.keep(player(), true, false, NO_CULL));
		assertTrue(ShadowEntitySelection.keep(vehicle(true), true, false, NO_CULL));
	}

	@Test
	void withEntitiesOffThePlayerAndItsVehicleFollowShadowPlayer() {
		assertTrue(ShadowEntitySelection.keep(player(), false, true, NO_CULL));
		assertTrue(ShadowEntitySelection.keep(vehicle(true), false, true, NO_CULL));
		assertFalse(ShadowEntitySelection.keep(player(), false, false, NO_CULL));
		assertFalse(ShadowEntitySelection.keep(vehicle(true), false, false, NO_CULL));
	}

	@Test
	void withEntitiesOffAndShadowPlayerOnOnlyThePlayerAndItsVehicleAreKept() {
		assertFalse(ShadowEntitySelection.keep(caster(true), false, true, NO_CULL));
		assertFalse(ShadowEntitySelection.keep(caster(false), false, true, NO_CULL));
	}

	@Test
	void anotherLocalPlayerIsDropped() {
		// The game's extraction skips every LocalPlayer that is not the camera's entity; the shadow does the same.
		var other = new ShadowEntitySelection.Facts(true, false, false, true, 4.0);
		assertFalse(ShadowEntitySelection.keep(other, true, true, NO_CULL));
	}

	@Test
	void thePreFilterNeverDropsAnEntityThatKeepWouldKeep() {
		// ShadowEntities runs mayKeep before the frustum test: wherever it says no, keep must say no for both frustum answers.
		boolean[] flags = {false, true};
		for (boolean entities : flags) {
			for (boolean player : flags) {
				for (boolean localPlayer : flags) {
					for (boolean carries : flags) {
						for (boolean cameraEntity : flags) {
							if (ShadowEntitySelection.mayKeep(entities, localPlayer, carries)) {
								continue;
							}
							for (boolean inFrustum : flags) {
								var facts = new ShadowEntitySelection.Facts(inFrustum, carries, cameraEntity, localPlayer, 0.0);
								assertFalse(ShadowEntitySelection.keep(facts, entities, player, NO_CULL),
									"mayKeep said no, so keep must drop the entity");
							}
						}
					}
				}
			}
		}
		assertTrue(ShadowEntitySelection.mayKeep(false, true, false), "the player is never pre-filtered");
		assertTrue(ShadowEntitySelection.mayKeep(false, false, true), "the vehicle is never pre-filtered");
		assertFalse(ShadowEntitySelection.mayKeep(false, false, false), "a plain entity is pre-filtered with entities off");
	}
}
