// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Metal's rule behind the Noble copies (2026-11-23): a fragment output's type is checked against the pipeline's colour attachment
 * of its index even with no write, so a float4 output on an RGBA32Uint attachment fails the pipeline. A pipeline whose shader
 * writes only the outputs its integer attachments can take compiles, draws with its matching formats, and leaves the integer
 * attachment untouched. The shapes are those of the game's oit_transmittance fragment: two float4 outputs (locations 0 and 1).
 */
@Tag("gpu")
class IntegerAttachmentOutputGpuTest {

	/** A full-screen triangle, then the float4 outputs of the coefficients; the first output only (the copies' count of one). */
	private static final String ONE_OUTPUT = """
		#include <metal_stdlib>
		using namespace metal;
		struct Vertex {
			float4 position [[position]];
		};
		vertex Vertex corners(uint vid [[vertex_id]]) {
			uint corner = vid % 3;
			float2 p = float2(corner == 1 ? 3.0 : -1.0, corner == 2 ? 3.0 : -1.0);
			Vertex result;
			result.position = float4(p, 0.0, 1.0);
			return result;
		}
		struct Outputs {
			float4 coefficient0 [[color(0)]];
		};
		fragment Outputs coefficients() {
			Outputs result;
			result.coefficient0 = float4(1.0, 0.0, 0.0, 1.0);
			return result;
		}
		""";

	/** The same triangle with the second output as well, which writes an RGBA32Uint attachment. */
	private static final String TWO_OUTPUTS = """
		#include <metal_stdlib>
		using namespace metal;
		struct Vertex {
			float4 position [[position]];
		};
		vertex Vertex corners(uint vid [[vertex_id]]) {
			uint corner = vid % 3;
			float2 p = float2(corner == 1 ? 3.0 : -1.0, corner == 2 ? 3.0 : -1.0);
			Vertex result;
			result.position = float4(p, 0.0, 1.0);
			return result;
		}
		struct Outputs {
			float4 coefficient0 [[color(0)]];
			float4 coefficient1 [[color(1)]];
		};
		fragment Outputs coefficients() {
			Outputs result;
			result.coefficient0 = float4(1.0, 0.0, 0.0, 1.0);
			result.coefficient1 = float4(7.0);
			return result;
		}
		""";

	private static final float[] CLEAR = {0f, 0f, 0f, 0f};

	@Test
	void aShaderWritingOnlyItsAcceptedOutputsDrawsAndLeavesTheIntegerAttachmentClear() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] pixels = gpu.renderFullscreenCleared(ONE_OUTPUT, "corners", "coefficients",
				new int[] {HeadlessGpu.RGBA8_UNORM, HeadlessGpu.RGBA32_UINT}, 4, 4, null, new byte[16], null, CLEAR);
			byte[] first = java.util.Arrays.copyOf(pixels[0], 4);
			assertEquals((byte) 255, first[0], "attachment 0 takes the first output (red)");
			assertEquals((byte) 0, first[1]);
			assertEquals((byte) 0, first[2]);
			assertEquals((byte) 255, first[3]);
			for (byte b : pixels[1]) {
				assertEquals((byte) 0, b, "the integer attachment is not written: it keeps its clear");
			}
		}
	}

	@Test
	void aShaderWritingAnIntegerAttachmentWithAFloatOutputIsRefusedEvenWithItsWriteMaskNone() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			// Attachment 1's write mask is 0: no component is written, and Metal still refuses the float output on it.
			IllegalStateException refused = assertThrows(IllegalStateException.class, () -> gpu.renderFullscreenWithWriteMasks(TWO_OUTPUTS,
				"corners", "coefficients", new int[] {HeadlessGpu.RGBA8_UNORM, HeadlessGpu.RGBA32_UINT}, new int[] {15, 0}, 4, 4, CLEAR));
			assertTrue(refused.getMessage().contains("not compatible"),
				"Metal names the output's type against the integer attachment: " + refused.getMessage());
		}
	}
}
