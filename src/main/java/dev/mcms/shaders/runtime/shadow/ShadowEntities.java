// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The shadow entity list (plan.md E5, "Entities for the shadow"): a second extraction of the level's entities with the
 * light's {@code Frustum}, run at the tail of {@code LevelExtractor.extractVisibleEntities} (after the camera's
 * extraction, so the camera's view scale and its per-entity resets are already done) and kept in an engine list. The
 * game's list is untouched: the camera's {@code LevelRenderState.entityRenderStates} stays as the camera extracted it.
 *
 * <p>Why a second list and not forcing visibility on the camera's list: the camera's list is culled by the camera's
 * frustum and sections, and the pack's shadow must include casters outside them (a caster behind the camera still shades
 * what the light sees). Forcing the camera's list to the light's visibility would also add draws to the camera's own
 * frame. The rules are {@link ShadowEntitySelection}'s.
 *
 * <p>Sodium's entity culling: Sodium wraps {@code EntityRenderer.shouldRender} with its camera-visibility test
 * ({@code SodiumWorldRenderer.isEntityVisible}, javap beta.1 and beta.5 identical). While the light's frustum test runs,
 * {@link #cameraCullingWaived} is true, and {@code SodiumEngineEntityCullMixin} answers that test true, so the light's
 * frustum alone decides.
 *
 * <p>Allocation: the list is cleared and refilled each frame; the entity render states are the game's own extraction
 * (its {@code EntityRenderDispatcher.extractEntity}), as the camera's are. The light's {@code Frustum} is one object per
 * frame (the game's {@code Frustum} has no update in place), and the selection's {@link ShadowEntitySelection.Facts}
 * are short-lived per entity. Both are the same kind as the camera extraction's per-frame objects.
 *
 * <p>Inert while no set is active: {@link #attach} is called at a set's activation and {@link #detach} at its release; with
 * no camera attached, {@link #extract} returns on one null check.
 */
public final class ShadowEntities {

	private static final ShadowEntities INSTANCE = new ShadowEntities();

	public static ShadowEntities get() {
		return INSTANCE;
	}

	private @Nullable ShadowCamera camera;
	private ShadowPassSequence.@Nullable Content content;
	private final List<EntityRenderState> entityStates = new ArrayList<>();
	/** The light's frustum matrix (player space to clip space), copied from the camera each frame. */
	private final Matrix4f lightMatrix = new Matrix4f();
	/**
	 * The {@code Frustum}'s left factor: its planes are {@code frustumStorage * lightMatrix} (javap {@code Frustum.calculateFrustum}:
	 * {@code arg2.mul(arg1, matrix)}). It stays the identity, so the planes come from the light's matrix alone; nothing writes it.
	 */
	private final Matrix4f frustumStorage = new Matrix4f();
	/** True while the light's frustum test runs: Sodium's camera-visibility test answers true (see the class comment). */
	private boolean cameraCullingWaived;

	ShadowEntities() {
	}

	/** Attaches the set's shadow camera and content switches (set activation). */
	public void attach(ShadowCamera shadowCamera, ShadowPassSequence.Content switches) {
		this.camera = shadowCamera;
		this.content = switches;
		this.entityStates.clear();
	}

	/** Detaches at the set's release (off, pack switch, reload, device close); the list is dropped. */
	public void detach() {
		this.camera = null;
		this.content = null;
		this.entityStates.clear();
	}

	/** Whether a set's camera is attached (the entity mixin reads nothing else while none is). */
	public boolean attached() {
		return this.camera != null;
	}

	/** The shadow entity states of this frame, in extraction order (read by the submit step). */
	public List<EntityRenderState> entityStates() {
		return this.entityStates;
	}

	/** Whether Sodium's camera-visibility test is waived for the light's frustum test (read by {@code SodiumEngineEntityCullMixin}). */
	public boolean cameraCullingWaived() {
		return this.cameraCullingWaived;
	}

	/**
	 * The second extraction. Called at the tail of {@code LevelExtractor.extractVisibleEntities}: {@code camera} is the
	 * same camera, {@code deltaTracker} the same tracker, and the level's entities are read in the game's order.
	 *
	 * <p>The per-entity reset of a new entity ({@code tickCount == 0}: {@code xOld}/{@code yOld}/{@code zOld} equal the
	 * position) is the camera loop's write, repeated with the same values: idempotent, so an entity the camera loop
	 * already reset is not changed, and an entity only the shadow sees is reset the same way.
	 */
	public void extract(Minecraft mc, EntityRenderDispatcher dispatcher, Camera camera, DeltaTracker deltaTracker,
			ClientLevel level) {
		ShadowCamera shadow = this.camera;
		ShadowPassSequence.Content switches = this.content;
		this.entityStates.clear();
		if (shadow == null || switches == null || !switches.entityList()) {
			return;
		}
		double camX = camera.position().x;
		double camY = camera.position().y;
		double camZ = camera.position().z;
		this.lightMatrix.set(shadow.viewProjection());
		Frustum frustum = new Frustum(this.lightMatrix, this.frustumStorage);
		frustum.prepare(camX, camY, camZ);
		double cull = shadow.cullRadius();
		Entity cameraEntity = camera.entity();
		LocalPlayer player = mc.player;
		for (Entity entity : level.entitiesForRendering()) {
			boolean carries = player != null && entity.hasIndirectPassenger(player);
			// With the entities switch off only the player and its vehicle can be kept: the others skip the frustum test.
			if (!ShadowEntitySelection.mayKeep(switches.entities(), entity instanceof LocalPlayer, carries)) {
				continue;
			}
			float partialTick = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
			this.cameraCullingWaived = true;
			boolean inFrustum;
			try {
				inFrustum = dispatcher.shouldRender(entity, frustum, camX, camY, camZ, partialTick);
			} finally {
				this.cameraCullingWaived = false;
			}
			ShadowEntitySelection.Facts facts = new ShadowEntitySelection.Facts(inFrustum, carries,
				entity == cameraEntity, entity instanceof LocalPlayer, dispatcher.distanceToSqr(entity));
			if (!ShadowEntitySelection.keep(facts, switches.entities(), switches.player(), cull)) {
				continue;
			}
			if (entity.tickCount == 0) {
				entity.xOld = entity.getX();
				entity.yOld = entity.getY();
				entity.zOld = entity.getZ();
			}
			this.entityStates.add(dispatcher.extractEntity(entity, partialTick));
		}
	}
}
