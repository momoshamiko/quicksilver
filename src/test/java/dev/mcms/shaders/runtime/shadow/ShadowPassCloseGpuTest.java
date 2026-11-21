// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.metal.BackendPassHarness;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shadow pass closes its frontend render pass only after the debug groups the game left open are popped. Runs through the
 * game's frontend device and command encoder ({@link BackendPassHarness#newDevice}), not a stand-in: the frontend refuses a
 * close with groups open, and a refused close leaves the encoder with its pass open.
 */
@Tag("gpu")
class ShadowPassCloseGpuTest {

	@Test
	void aPassWithTwoOpenGroupsClosesOnceTheGroupsArePopped() throws Exception {
		System.setProperty("mcms.metal.mslCache", "false");
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture texture = device.createTexture(() -> "shadow close target", GpuTexture.USAGE_RENDER_ATTACHMENT,
			GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
		GpuTextureView view = device.createTextureView(texture);
		try {
			CommandEncoder commands = device.createCommandEncoder();
			RenderPass pass = commands.createRenderPass(() -> "shadow close", view, Optional.empty(), null,
				OptionalDouble.empty());
			pass.pushDebugGroup(() -> "game group a");
			pass.pushDebugGroup(() -> "game group b");
			assertEquals(2, ShadowPassOps.openDebugGroups(pass), "the frontend's counter reads the two open groups");
			assertDoesNotThrow(() -> ShadowPassOps.closeWithOpenGroups(pass, ShadowPassOps::openDebugGroups),
				"the pass refused its close with debug groups open");
			assertEquals(0, ShadowPassOps.openDebugGroups(pass), "the groups were popped before the close");

			// The encoder is usable again: a second pass opens and closes.
			RenderPass again = commands.createRenderPass(() -> "shadow close again", view, Optional.empty(), null,
				OptionalDouble.empty());
			assertDoesNotThrow(again::close);
		} finally {
			view.close();
			texture.close();
		}
	}
}
