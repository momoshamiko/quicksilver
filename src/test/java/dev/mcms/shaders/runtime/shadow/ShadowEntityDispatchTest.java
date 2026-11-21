// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping.Domain;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The shadow dispatch's two rules without a device: the entity submit's offset, and the phase's domain windows. */
final class ShadowEntityDispatchTest {

	@Test
	void anEntityIsSubmittedAtItsOffsetFromTheCamera() {
		// The game passes state.x - camX (LevelRenderer.submitEntities, javap 26.3 bytecodes 73-99), not the camera's position.
		CameraRenderState camera = new CameraRenderState();
		camera.pos = new Vec3(100.0, 64.0, -50.0);
		EntityRenderState state = new EntityRenderState();
		state.x = 103.5;
		state.y = 65.25;
		state.z = -48.0;
		List<double[]> seen = new ArrayList<>();
		ShadowEntityDispatch.submitStates(List.of(state), camera,
			(s, c, x, y, z, pose, collector) -> seen.add(new double[] {x, y, z}), new PoseStack(), null);
		assertEquals(1, seen.size());
		assertEquals(3.5, seen.get(0)[0], 1e-12, "x is the state's position minus the camera's");
		assertEquals(1.25, seen.get(0)[1], 1e-12, "y is the state's position minus the camera's");
		assertEquals(2.0, seen.get(0)[2], 1e-12, "z is the state's position minus the camera's");
	}

	@Test
	void everyEntityOfTheListIsSubmittedInOrderWithTheSameCamera() {
		CameraRenderState camera = new CameraRenderState();
		camera.pos = new Vec3(10.0, 0.0, 10.0);
		List<EntityRenderState> states = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			EntityRenderState state = new EntityRenderState();
			state.x = 10.0 + i;
			state.y = 0.0;
			state.z = 10.0;
			states.add(state);
		}
		List<Double> xs = new ArrayList<>();
		ShadowEntityDispatch.submitStates(states, camera,
			(s, c, x, y, z, pose, collector) -> {
				assertEquals(camera, c);
				xs.add(x);
			}, new PoseStack(), null);
		assertEquals(List.of(0.0, 1.0, 2.0), xs);
	}

	/** Records each window's entry and exit, and the domain each draw sees while it runs. */
	private static final class Windows implements ShadowEntityDispatch.DomainWindow {
		final List<String> log = new ArrayList<>();
		Domain current = Domain.ENTITY;

		@Override
		public void within(Domain domain, Runnable draws) {
			Domain previous = this.current;
			this.current = domain;
			this.log.add("enter " + domain);
			try {
				draws.run();
			} finally {
				this.current = previous;
				this.log.add("leave " + domain);
			}
		}
	}

	@Test
	void theEntityDrawsRunInTheEntityWindowAndTheBlockEntityDrawsInTheBlockWindow() {
		Windows windows = new Windows();
		ShadowEntityDispatch.route(() -> windows.log.add("entities in " + windows.current),
			() -> windows.log.add("blocks in " + windows.current), windows);
		assertEquals(List.of("enter ENTITY", "entities in ENTITY", "leave ENTITY",
			"enter BLOCK_ENTITY", "blocks in BLOCK_ENTITY", "leave BLOCK_ENTITY"), windows.log);
		assertEquals(Domain.ENTITY, windows.current, "the window restores the domain after the route");
	}
}
