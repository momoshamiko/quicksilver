// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.transform.EngineRewrites;
import org.junit.jupiter.api.Test;

/**
 * Which full-screen vertex stages put the quad over the whole viewport (E9: only those passes may skip loading their
 * targets). The forms are the corpus's: Complementary, BSL and Solas use ftransform(), photon the corners in clip space;
 * Bliss and Mellow shrink the quad after ftransform() for their bloom tiles.
 */
class ViewportCoverageTest {

	private static boolean covers(String vertex) {
		return ViewportCoverage.covers(EngineRewrites.apply(Parser.parse(vertex)));
	}

	@Test
	void ftransformAloneCovers() {
		assertTrue(covers("""
			#version 120
			varying vec2 texcoord;
			void main() {
				gl_Position = ftransform();
				texcoord = gl_MultiTexCoord0.xy;
			}
			"""));
	}

	@Test
	void theCornersInClipSpaceAndTheTwoStepProductCover() {
		assertTrue(covers("""
			#version 120
			void main() {
				gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			"""), "photon's form");
		assertTrue(covers("""
			#version 120
			void main() {
				gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
			}
			"""));
	}

	@Test
	void aQuadShrunkAfterFtransformDoesNotCover() {
		assertFalse(covers("""
			#version 120
			uniform vec2 clampedRes;
			void main() {
				gl_Position = ftransform();
				gl_Position.xy = (gl_Position.xy * 0.5 + 0.5) * 0.51 / clampedRes * vec2(1920.0, 1080.0) * 2.0 - 1.0;
			}
			"""), "Bliss's bloom tile");
		assertFalse(covers("""
			#version 120
			void main() {
				gl_Position = ftransform();
				gl_Position = gl_Position * 0.5 + 0.5;
			}
			"""), "Mellow's second whole write");
	}

	@Test
	void aWriteInABranchAHelperOrAnotherFunctionDoesNotCover() {
		assertFalse(covers("""
			#version 120
			uniform float scale;
			void main() {
				if (scale > 0.5) {
					gl_Position = ftransform();
				} else {
					gl_Position = vec4(0.0);
				}
			}
			"""), "two writes in branches");
		assertFalse(covers("""
			#version 120
			void place() {
				gl_Position = ftransform();
			}
			void main() {
				place();
			}
			"""), "the write sits in a helper, not in main");
		assertFalse(covers("""
			#version 120
			void shrink(inout vec4 p) {
				p.xy *= 0.5;
			}
			void main() {
				gl_Position = ftransform();
				shrink(gl_Position);
			}
			"""), "gl_Position handed to a function");
		assertFalse(covers("""
			#version 120
			uniform float skip;
			void main() {
				gl_Position = ftransform();
				if (skip > 0.5) {
					return;
				}
			}
			"""), "a return in main");
	}
}
