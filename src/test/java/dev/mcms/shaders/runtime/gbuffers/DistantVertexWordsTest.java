// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.mcms.shaders.runtime.distant.DistantVertices;
import org.junit.jupiter.api.Test;

/**
 * The vertex descriptor the Distant Horizons pack pipeline is built with (the vertex half of the int descriptor mc_pipeline_new reads):
 * one buffer at the engine's vertex-data slot (27) with DH's 16-byte stride, and six attributes at the offsets and with the Metal
 * formats the DH layout names. The Metal enum values are MTLVertexFormat's: UShort3 = 14, UShort = 49, UChar4Normalized = 9,
 * UChar = 45.
 */
class DistantVertexWordsTest {

	@Test
	void theDistantFormatDescribesTheSixAttributesOfTheSixteenByteVertex() {
		int[] words = GbufferPlans.vertexWordsOf(DistantPipelines.FORMAT, DistantVertices.layout());
		assertArrayEquals(new int[] {
			1, 27, 16, 0,                  // one buffer: index 27, stride 16, per vertex
			6,                             // six attributes: location, buffer, offset, format
			0, 27, 0, 14,                  // POSITION: vPosition, UShort3 at 0
			1, 27, 6, 49,                  // LIGHTMAP: meta, UShort at 6
			2, 27, 8, 9,                   // COLOR: vColor, UChar4Normalized at 8
			3, 27, 12, 45,                 // DH material: irisMaterial, UChar at 12
			4, 27, 13, 45,                 // NORMAL: irisNormal, UChar at 13
			5, 27, 6, 49,                  // TEXCOORD2: meta, UShort at 6
		}, words);
	}
}
