// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sixteen distinct sampler states (the limit of Metal's sampler slots with the constexpr samplers counted) in one fragment
 * stage: twenty textures take them in turn, each texture's texel reaches the sum, and the program draws on the device.
 */
@Tag("gpu")
final class SamplerStatesGpuTest {

	@Test
	void sixteenDistinctStatesDrawAndEveryTextureIsBound() {
		List<SamplerState> all = SamplerStatesTest.distinctStates();
		int textures = 20;
		List<SamplerState> stateOfTexture = new java.util.ArrayList<>();
		for (int i = 0; i < textures; i++) {
			stateOfTexture.add(all.get(i % 16));  // sixteen states, reused by the last four textures
		}
		ProgramTranslation t = TranslateSupport.translateOk(SamplerStatesTest.request(stateOfTexture));
		HeadlessGpu.InputTexture[] inputs = new HeadlessGpu.InputTexture[textures];
		for (int i = 0; i < textures; i++) {
			// Texture i holds one texel with red i + 1: the sum over all twenty is 210 of 255.
			inputs[i] = HeadlessGpu.InputTexture.tex2d(HeadlessGpu.RGBA8_UNORM, 1, 1,
				new byte[] {(byte) (i + 1), 0, 0, (byte) 255});
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, inputs, new byte[0], new byte[0][]);
			assertEquals(210, out[0][0] & 0xff, "the sum of the twenty texels\n" + t.msl());
		}
	}
}
