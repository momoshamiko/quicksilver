// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Path normalization and include-style resolution under the {@code shaders/} root. */
class PackPathsTest {
	@Test
	void normalizeCleansSeparatorsAndDotSegments() {
		assertEquals("a/b/c", PackPaths.normalize("a//b/./c/"));
		assertEquals("a/b", PackPaths.normalize("/a/b"));
		assertEquals("", PackPaths.normalize("/"));
		assertEquals("", PackPaths.normalize("."));
		assertEquals("", PackPaths.normalize(""));
		assertEquals("a/b", PackPaths.normalize("a/b"));
	}

	@Test
	void normalizeResolvesDotDot() {
		assertEquals("b", PackPaths.normalize("a/../b"));
		assertEquals("c", PackPaths.normalize("a/b/../../c"));
		assertEquals("../../x", PackPaths.normalize("../../x"));

		assertEquals("..", PackPaths.normalize(".."));
	}

	@Test
	void resolveRelativeUsesIncludingFilesFolder() {
		assertEquals(Optional.of("lib/b.glsl"), PackPaths.resolve("lib/a.glsl", "b.glsl"));
		assertEquals(Optional.of("b.glsl"), PackPaths.resolve("a.glsl", "b.glsl"));
		assertEquals(Optional.of("world0/lib/b.glsl"), PackPaths.resolve("world0/a.fsh", "lib/b.glsl"));
	}

	@Test
	void resolveAbsoluteStartsAtShadersRoot() {
		assertEquals(Optional.of("lib/common.glsl"), PackPaths.resolve("world0/a.fsh", "/lib/common.glsl"));
		assertEquals(Optional.of("lib/common.glsl"), PackPaths.resolve("lib/deep/a.glsl", "/lib/common.glsl"));
	}

	@Test
	void resolveParentRelative() {
		assertEquals(Optional.of("lib/shared.glsl"), PackPaths.resolve("world0/a.fsh", "../lib/shared.glsl"));
		assertEquals(Optional.of("a.glsl"), PackPaths.resolve("lib/a.glsl", "../a.glsl"));
	}

	@Test
	void resolveEmptyWhenLeavingRoot() {
		assertEquals(Optional.empty(), PackPaths.resolve("a.glsl", "../x"));
		assertEquals(Optional.empty(), PackPaths.resolve("lib/a.glsl", "../../x"));
		assertEquals(Optional.empty(), PackPaths.resolve("a.glsh", "/../x"));
		assertEquals(Optional.empty(), PackPaths.resolve("a.glsl", "/"));
		assertEquals(Optional.empty(), PackPaths.resolve("a.glsl", ""));
	}


	@Test
	void dotDotInsideIncludeNameResolvesLexically() {
		assertEquals(Optional.of("lib/x.glsl"), PackPaths.resolve("lib/a.glsl", "x.glsl/../x.glsl"));
	}
}
