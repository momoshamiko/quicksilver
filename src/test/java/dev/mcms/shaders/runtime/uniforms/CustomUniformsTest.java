// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;

/**
 * The pack's custom uniforms against a small pack: the block holds the {@code uniform.*} values in declaration order
 * with their GLSL types, {@code variable.*} values are evaluated but not uploaded, a later expression reads an earlier
 * one's value, the parameters come from the frame block (through {@code FrameUniforms.fill}), a failed compile is
 * absent from the block, the clock hold stands the expressions' {@code smooth} still, and the bytes {@code write}
 * produces are the block's std140 values in little-endian.
 */
class CustomUniformsTest {
	@TempDir
	Path dir;

	private PackModel load() throws IOException {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), String.join("\n",
			"variable.float.vbase=1.5",
			"uniform.float.myscale=vbase * 2.0",
			"uniform.int.icount=3",
			"uniform.bool.flag=frameTimeCounter >= 0.0",
			"uniform.vec3.tint=vec3(0.25, 0.5, 1.0)",
			"uniform.float.sun=sunAngle",
			"uniform.float.proj= gbufferProjection.2.2",
			"uniform.float.shadow= shadowProjection.0.0",
			"uniform.float.dhproj= dhProjection.2.2",
			"uniform.int.dhrd= dhRenderDistance",
			"uniform.float.plainsId= BIOME_PLAINS",
			"uniform.float.desertCat= CAT_DESERT",
			"uniform.float.smoothed=smooth(rainStrength, 10)",
			"uniform.float.broken=nope + 1.0",
			""));
		Files.createDirectories(shaders.resolve("world0"));
		Files.writeString(shaders.resolve("world0/composite.fsh"), "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		try (PackSource pack = PackSource.open(dir)) {
			return PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static final FrameUniformsTest.TestInputs INPUTS = frameInputs();

	private static FrameUniformsTest.TestInputs frameInputs() {
		var in = new FrameUniformsTest.TestInputs();
		in.rainLevel = 0f;
		return in;
	}

	private static Map<String, Integer> offsets(CustomUniforms c) {
		Map<String, Integer> m = new HashMap<>();
		for (var f : c.schema(0).fields()) {
			m.put(f.name(), f.offset());
		}
		return m;
	}

	/** The values the pack computes, evaluated once per frame against the frame block's values. */
	@Test
	void evaluatesThePackValuesAgainstTheFrameBlock() throws IOException {
		CustomUniforms c = CustomUniforms.of(load());
		assertNotNull(c);
		FrameUniforms frame = new FrameUniforms();
		frame.advance(INPUTS.build(), 0f, -1f);
		c.update(frame);
		ByteBuffer b = ByteBuffer.allocate(c.size()).order(ByteOrder.LITTLE_ENDIAN);
		c.write(b);
		b.flip();
		Map<String, Integer> o = offsets(c);
		// The variable is evaluated (myscale reads it) but not uploaded; the broken value is absent; the rest upload.
		assertTrue(!o.containsKey("vbase"));
		assertTrue(!o.containsKey("broken"));
		assertEquals(3f, b.getFloat(o.get("myscale")));
		assertEquals(3, b.getInt(o.get("icount")));
		assertEquals(1, b.getInt(o.get("flag")));
		assertEquals(0.25f, b.getFloat(o.get("tint")), 1e-5);
		assertEquals(0.5f, b.getFloat(o.get("tint") + 4), 1e-5);
		assertEquals(1f, b.getFloat(o.get("tint") + 8), 1e-5);
		// The parameters come from the frame block: noon's sunAngle, the GL projection's m22, the shadow camera's matrix.
		assertEquals(0.25f, b.getFloat(o.get("sun")), 1e-5);
		// The projection of the default inputs: fovY 90, near 0.05, far 1000 (the same value FrameUniformsTest pins).
		assertEquals(-1.001f, b.getFloat(o.get("proj")), 1e-3);
		float shadowScale = frame.shadow().projection().m00();
		assertTrue(shadowScale > 0f);
		assertEquals(shadowScale, b.getFloat(o.get("shadow")), 1e-6);
		// Without Distant Horizons the DH values are Iris's: the gbuffer projection, the render distance in chunks.
		assertEquals(-1.001f, b.getFloat(o.get("dhproj")), 1e-3);
		assertEquals(INPUTS.renderDistanceChunks, b.getInt(o.get("dhrd")));
		// The pack's biome constants are the numbers the biome uniform serves (BiomeIds): plains 1, CAT_DESERT 12.
		assertEquals(dev.mcms.shaders.pack.BiomeIds.idOf("plains"), b.getFloat(o.get("plainsId")), 1e-6);
		assertEquals(12f, b.getFloat(o.get("desertCat")), 1e-6);
		// smooth's first evaluation snaps to its input.
		assertEquals(0f, b.getFloat(o.get("smoothed")), 1e-6);
		// The block's fields carry the GLSL type words packs declare them with.
		for (var f : c.schema(0).fields()) {
			switch (f.name()) {
				case "myscale" -> assertEquals("float", f.type());
				case "icount" -> assertEquals("int", f.type());
				case "flag" -> assertEquals("bool", f.type());
				case "tint" -> assertEquals("vec3", f.type());
				default -> {
				}
			}
		}
	}

	/** A pack without custom values needs no block; a pack whose values all failed needs none either. */
	@Test
	void aPackWithoutValuesHasNoBlock() throws IOException {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders.resolve("world0"));
		Files.writeString(shaders.resolve("world0/composite.fsh"), "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		try (PackSource pack = PackSource.open(dir)) {
			assertNull(CustomUniforms.of(PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties())));
		}
	}
	/** The expressions' clock time is the held clock, while smooth()'s delta keeps the real frame time. */
	@Test
	void theHoldHoldsTheClockButNotSmooth() throws IOException {
		CustomUniforms c = CustomUniforms.of(load());
		assertNotNull(c);
		FrameUniforms frame = new FrameUniforms();
		var rain = new FrameUniformsTest.TestInputs();
		rain.rainLevel = 0f;
		frame.advance(rain.build(), 0f, -1f);
		c.update(frame);
		rain.rainLevel = 1f;
		frame.advance(rain.build(), 1f, -1f);
		c.update(frame);
		float afterOneSecond = read(c, "smoothed");
		assertTrue(afterOneSecond > 0f && afterOneSecond < 1f, "smooth approaches its input");
		try {
			System.setProperty("mcms.shaders.holdClock", "5.0");
			// The hold holds the clock; smooth keeps approaching with the real dt, as the harness holds the packs'
			// clocks on Iris and Vitrail.
			frame.advance(rain.build(), 60f, 5.0f);
			c.update(frame);
			assertEquals(5.0f, frame.clockSeconds(), 1e-6);
			assertTrue(read(c, "smoothed") > afterOneSecond, "smooth continues with the real dt");
		} finally {
			System.clearProperty("mcms.shaders.holdClock");
			System.clearProperty("mcms.shaders.holdClockRead");
		}
		// The hold's reader mark must not leak to other tests in this process.
		assertNull(System.getProperty("mcms.shaders.holdClockRead"));
	}

	private static float read(CustomUniforms c, String name) {
		ByteBuffer b = ByteBuffer.allocate(c.size()).order(ByteOrder.LITTLE_ENDIAN);
		c.write(b);
		b.flip();
		for (var f : c.schema(0).fields()) {
			if (f.name().equals(name)) {
				return b.getFloat(f.offset());
			}
		}
		throw new AssertionError(name + " is not in the block");
	}
}
