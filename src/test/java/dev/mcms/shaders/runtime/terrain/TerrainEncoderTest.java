// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import org.junit.jupiter.api.Test;

/**
 * The engine's terrain encoder against Sodium's own (plan.md E4's test list): the first 20 bytes of every vertex must be
 * bit-identical to what Sodium's compact encoder writes for the same quad. Both allowed jars (ADR 0007) run by execution
 * from their own files through a child-first loader, as {@link SodiumAdapterTest} does (E4 review item 11: the beta.5 case
 * used the beta.1 encoder from the classpath before).
 * The 24 extension bytes must carry the block's values and the encoder's geometry
 * (the normal, the tangent with its handedness, the mid-block offsets from the stored centre). A split quad's copy keeps
 * its attributes, and its offsets follow its new positions.
 */
class TerrainEncoderTest {

	private static final String COMPACT_ENCODER = "net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder";
	private static final String VERTEX = COMPACT_ENCODER + "$Vertex";
	private static final String COMPACT = "net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex";

	private static final float[][] POSITIONS = { { -7.5f, 0.0f, 15.5f }, { 0.25f, -8.0f, 24.0f },
		{ 8.0f, 31.75f, -0.125f }, { 16.0f, 16.0f, 16.0f } };
	private static final float[][] UVS = { { 0.25f, 0.25f }, { 0.75f, 0.25f }, { 0.75f, 0.75f }, { 0.25f, 0.75f } };

	/** A unit square in the z = 0 plane; u runs along +x and v falls as y grows (the atlas is unmirrored, v down). */
	private static final float[][] SQUARE = { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 1, 0 }, { 0, 1, 0 } };
	private static final float[][] SQUARE_UV = { { 0.1f, 0.9f }, { 0.9f, 0.9f }, { 0.9f, 0.1f }, { 0.1f, 0.1f } };

	@Test
	void theTwentyCompactBytesAreBitIdenticalToSodiumsEncoderOn093() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.093");
		assumeTrue(jar != null, "the 0.9.3 jar path was not set");
		for (float texelBias = 0; texelBias <= 1; texelBias += 0.25f) {
			TerrainVertex[] quad = quad(POSITIONS, UVS, texelBias);
			byte[] mine = write(new TerrainEncoder(), quad, 0x25, 42);
			byte[] sodium = writeSodiumFromJar(jar, quad, 0x25, 42);
			assertCompactBytesEqual(sodium, mine, "0.9.3, bias " + texelBias);
		}
	}

	@Test
	void theTwentyCompactBytesAreBitIdenticalToSodiumsEncoderOnBeta5() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta5");
		assumeTrue(jar != null, "the beta.5 jar path was not set");
		for (float texelBias = 0; texelBias <= 1; texelBias += 0.25f) {
			TerrainVertex[] quad = quad(POSITIONS, UVS, texelBias);
			byte[] mine = write(new TerrainEncoder(), quad, 0x25, 42);  // (material bits, section index)
			byte[] sodium = writeSodiumFromJar(jar, quad, 0x25, 42);
			assertCompactBytesEqual(sodium, mine, "beta.5, bias " + texelBias);
		}
	}

	@Test
	void theTwentyCompactBytesAreBitIdenticalToSodiumsEncoderOnBeta1() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta1");
		assumeTrue(jar != null, "the beta.1 jar path was not set");
		for (float texelBias = 0; texelBias <= 1; texelBias += 0.25f) {
			TerrainVertex[] quad = quad(POSITIONS, UVS, texelBias);
			byte[] mine = write(new TerrainEncoder(), quad, 0x25, 42);
			byte[] sodium = writeSodiumFromJar(jar, quad, 0x25, 42);
			assertCompactBytesEqual(sodium, mine, "beta.1, bias " + texelBias);
		}
	}

	@Test
	void theExtensionBytesCarryIdFluidNormalTangentMidBlockAndSpriteCentre() {
		TerrainVertex[] quad = quad(SQUARE, SQUARE_UV, 0);
		for (int i = 0; i < 4; i++) {
			quad[i].blockId = 8;
			quad[i].fluid = false;
			quad[i].emission = 11;
			quad[i].centreX = 0.5f;
			quad[i].centreY = 0.25f;
			quad[i].centreZ = 0.75f;
			quad[i].midU = 0.5f;
			quad[i].midV = 0.25f;
		}
		byte[] out = write(new TerrainEncoder(), quad, 0, 3);
		// Offsets from each vertex to the centre (0.5, 0.25, 0.75), in 1/64 m.
		int[] expectedX = { 32, -32, -32, 32 };
		int[] expectedY = { 16, 16, -48, -48 };
		for (int v = 0; v < 4; v++) {
			byte[] vertex = slice(out, v, 0, TerrainVertexType.STRIDE);
			int idWord = word(vertex, TerrainVertexType.A_ENTITY);
			assertEquals(9, idWord & 0xFFFF, "block id + 1");
			assertEquals(0, idWord >>> 16, "mc_Entity.y: not a fluid");
			assertEquals(11, Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK + 3) * 127.0f), "emission");
			assertEquals(expectedX[v], Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK) * 127.0f), "mid block x");
			assertEquals(expectedY[v], Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK + 1) * 127.0f), "mid block y");
			assertEquals(48, Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK + 2) * 127.0f), "mid block z");
			// The quad lies in the z = 0 plane with U along +x and V down: the normal is +z, the tangent +x, and the
			// bitangent cross(T, N) = -y equals the V direction, so the handedness is +1.
			assertEquals(0, Math.round(snorm(vertex, TerrainVertexType.A_NORMAL)), "normal x");
			assertEquals(0, Math.round(snorm(vertex, TerrainVertexType.A_NORMAL + 1)), "normal y");
			assertEquals(1, Math.round(snorm(vertex, TerrainVertexType.A_NORMAL + 2)), "normal z");
			assertEquals(1, Math.round(snorm(vertex, TerrainVertexType.A_TANGENT)), "tangent x");
			assertEquals(1, Math.round(snorm(vertex, TerrainVertexType.A_TANGENT + 3)), "handedness of an unmirrored face");
			assertEquals(0.5f, floatAt(vertex, TerrainVertexType.A_MID_TEXCOORD), 1.0e-7f, "sprite centre u");
			assertEquals(0.25f, floatAt(vertex, TerrainVertexType.A_MID_TEXCOORD + 4), 1.0e-7f, "sprite centre v");
		}
	}

	@Test
	void aMirroredFaceHasHandednessMinusOne() {
		// The same square with U running against +x: the bitangent is then cross(T, N) reversed, so -1.
		float[][] mirrored = { { 0.9f, 0.9f }, { 0.1f, 0.9f }, { 0.1f, 0.1f }, { 0.9f, 0.1f } };
		byte[] out = write(new TerrainEncoder(), quadWithBlock(SQUARE, mirrored), 0, 3);
		byte[] vertex = slice(out, 0, 0, TerrainVertexType.STRIDE);
		assertEquals(-1, Math.round(snorm(vertex, TerrainVertexType.A_TANGENT)), "tangent runs along -x");
		assertEquals(-1, Math.round(snorm(vertex, TerrainVertexType.A_TANGENT + 3)), "handedness of a mirrored face");
	}

	@Test
	void aDegenerateQuadKeepsANonZeroNormal() {
		// The fourth corner repeats the first: the diagonals are still not parallel, so the normal is +z, not zero.
		float[][] triangle = { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 1, 0 }, { 0, 0, 0 } };
		byte[] out = write(new TerrainEncoder(), quadWithBlock(triangle, SQUARE_UV), 0, 3);
		for (int v = 0; v < 4; v++) {
			byte[] vertex = slice(out, v, 0, TerrainVertexType.STRIDE);
			assertEquals(1, Math.round(snorm(vertex, TerrainVertexType.A_NORMAL + 2)), "normal z of corner " + v);
		}
	}

	@Test
	void aFluidQuadCarriesTheFluidFlagInMcEntityY() {
		TerrainVertex[] quad = quadWithBlock(SQUARE, SQUARE_UV);
		for (TerrainVertex v : quad) {
			v.fluid = true;
		}
		byte[] out = write(new TerrainEncoder(), quad, 0, 3);
		assertEquals(1, word(slice(out, 0, 0, TerrainVertexType.STRIDE), TerrainVertexType.A_ENTITY) >>> 16, "mc_Entity.y");
	}

	@Test
	void separateAoMovesTheBrightnessToAlphaAndLeavesTheTintUnscaled() {
		int tint = 0xFF4080C0;
		assertEquals(0x7F4080C0, TerrainEncoder.colour(tint, 0.5f, true), "AO in alpha (Sodium's byte: 0.5 * 255 truncated)");
		// red, green and blue unscaled: the bit-identical tests above cover the other form (Sodium's own mulRGB)
		assertEquals(tint, TerrainEncoder.colour(tint, 1.0f, true), "full brightness keeps the tint and an opaque alpha");
	}

	@Test
	void thePacksVertexColourDirectivesFollowIrisDefaults() {
		var unset = TerrainEngine.VertexColourRule.of(java.util.Map.of());
		assertEquals(new TerrainEngine.VertexColourRule(true, false), unset, "neither directive: no shade, AO in red, green and blue");
		assertEquals(new TerrainEngine.VertexColourRule(false, false),
			TerrainEngine.VertexColourRule.of(java.util.Map.of("oldLighting", true)), "oldLighting=true keeps the shade");
		assertEquals(new TerrainEngine.VertexColourRule(true, true),
			TerrainEngine.VertexColourRule.of(java.util.Map.of("oldLighting", false, "separateAo", true)), "the list packs' pair");
	}

	@Test
	void aSplitQuadKeepsItsAttributesAndItsOffsetsFollowItsNewPositions() {
		TerrainVertex[] original = quadWithBlock(SQUARE, SQUARE_UV);
		for (int i = 0; i < 4; i++) {
			original[i].blockId = 5;
			original[i].fluid = true;
			original[i].emission = 3;
			original[i].centreX = 0.5f;
			original[i].centreY = 0.25f;
			original[i].centreZ = 0.75f;
			original[i].midU = 0.75f;
			original[i].midV = 0.5f;
		}
		// What the collector does: a fresh quad from the factory, the base fields through Vertex.copyVertexTo, the
		// extension through the engine's hook (the mixin's TAIL calls the same static method). The split then moves the
		// corners, so the copies sit at new positions while the centre stays put.
		TerrainVertex[] split = { new TerrainVertex(), new TerrainVertex(), new TerrainVertex(), new TerrainVertex() };
		float[][] moved = { { 0.25f, 0.0f, 0.0f }, { 1.0f, 0.25f, 0.0f }, { 1.0f, 1.0f, 0.0f }, { 0.0f, 1.0f, 0.0f } };
		for (int i = 0; i < 4; i++) {
			Vertex.copyVertexTo(original[i], split[i]);
			TerrainVertex.copyExtension(original[i], split[i]);
			split[i].x = moved[i][0];
			split[i].y = moved[i][1];
			split[i].z = moved[i][2];
		}
		byte[] out = write(new TerrainEncoder(), split, 0, 9);
		byte[] v0 = slice(out, 0, 0, TerrainVertexType.STRIDE);
		assertEquals(6, word(v0, TerrainVertexType.A_ENTITY) & 0xFFFF, "block id + 1 survives the split");
		assertEquals(1, word(v0, TerrainVertexType.A_ENTITY) >>> 16, "the fluid flag survives the split");
		assertEquals(0.75f, floatAt(slice(out, 2, 0, TerrainVertexType.STRIDE), TerrainVertexType.A_MID_TEXCOORD),
			1.0e-7f, "sprite centre survives the split");
		// Centre (0.5, 0.25, 0.75) minus the moved corners, in 1/64 m: x 16, -32, -32, 32; y 16, 0, -48, -48; z 48.
		int[] expectedX = { 16, -32, -32, 32 };
		int[] expectedY = { 16, 0, -48, -48 };
		for (int v = 0; v < 4; v++) {
			byte[] vertex = slice(out, v, 0, TerrainVertexType.STRIDE);
			assertEquals(expectedX[v], Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK) * 127.0f), "split x " + v);
			assertEquals(expectedY[v], Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK + 1) * 127.0f), "split y " + v);
			assertEquals(48, Math.round(snorm(vertex, TerrainVertexType.A_MID_BLOCK + 2) * 127.0f), "split z " + v);
		}
	}

	private static TerrainVertex[] quadWithBlock(float[][] positions, float[][] uvs) {
		TerrainVertex[] quad = quad(positions, uvs, 0);
		for (TerrainVertex v : quad) {
			v.blockId = 8;
		}
		return quad;
	}

	private static TerrainVertex[] quad(float[][] positions, float[][] uvs, float texelBias) {
		TerrainVertex[] quad = new TerrainVertex[4];
		for (int i = 0; i < 4; i++) {
			quad[i] = new TerrainVertex();
			quad[i].x = positions[i][0];
			quad[i].y = positions[i][1];
			quad[i].z = positions[i][2];
			quad[i].color = 0xFF80C0E0;
			quad[i].ao = i == 0 ? 0.5f : 1.0f;
			quad[i].u = uvs[i][0] + texelBias / 1024.0f;
			quad[i].v = uvs[i][1] - texelBias / 2048.0f;
			quad[i].light = 0x00F000A0;
		}
		return quad;
	}

	private static void assertCompactBytesEqual(byte[] sodium, byte[] mine, String label) {
		for (int v = 0; v < 4; v++) {
			assertArrayEquals(slice(sodium, v, 0, 20), slice(mine, v, 0, 20), "vertex " + v + " differs in the compact bytes (" + label + ")");
		}
	}

	private static byte[] write(TerrainEncoder engine, TerrainVertex[] quad, int materialBits, int sectionIndex) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment memory = arena.allocate(TerrainVertexType.STRIDE * 4);
			engine.write(memory.address(), materialBits, quad, sectionIndex);
			return memory.toArray(ValueLayout.JAVA_BYTE);
		}
	}

	/**
	 * Sodium's compact encoder of one allowed jar, run by execution: its own vertex class and encoder, loaded child-first
	 * from the jar, fed with the same field values as the engine's quad. The 80 bytes come back in one array (20 per
	 * vertex). The test classpath holds beta.1 only, so beta.5 comes from its own jar (ADR 0007: the releases never share
	 * a classpath).
	 */
	private static byte[] writeSodiumFromJar(String jar, TerrainVertex[] quad, int materialBits, int sectionIndex)
			throws Exception {
		try (ChildFirst loader = new ChildFirst(new URL[] { new File(jar).toURI().toURL() })) {
			Class<?> vertexClass = loader.loadClass(VERTEX);
			Class<?> encoderInterface = loader.loadClass(COMPACT_ENCODER);
			Object encoder = loader.loadClass(COMPACT).getMethod("getEncoder").invoke(
				loader.loadClass(COMPACT).getConstructor().newInstance());
			Object array = Array.newInstance(vertexClass, 4);
			for (int i = 0; i < 4; i++) {
				Object v = vertexClass.getConstructor().newInstance();
				vertexClass.getField("x").setFloat(v, quad[i].x);
				vertexClass.getField("y").setFloat(v, quad[i].y);
				vertexClass.getField("z").setFloat(v, quad[i].z);
				vertexClass.getField("u").setFloat(v, quad[i].u);
				vertexClass.getField("v").setFloat(v, quad[i].v);
				vertexClass.getField("color").setInt(v, quad[i].color);
				vertexClass.getField("ao").setFloat(v, quad[i].ao);
				vertexClass.getField("light").setInt(v, quad[i].light);
				Array.set(array, i, v);
			}
			Method write = encoderInterface.getMethod("write", long.class, int.class, array.getClass(), int.class);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment memory = arena.allocate(20 * 4);
				write.invoke(encoder, memory.address(), materialBits, array, sectionIndex);
				return memory.toArray(ValueLayout.JAVA_BYTE);
			}
		}
	}

	private static byte[] slice(byte[] bytes, int vertex, int from, int length) {
		int stride = bytes.length == 20 * 4 ? 20 : TerrainVertexType.STRIDE;
		byte[] out = new byte[length];
		System.arraycopy(bytes, vertex * stride + from, out, 0, length);
		return out;
	}

	private static int word(byte[] vertexBytes, int offset) {
		return Byte.toUnsignedInt(vertexBytes[offset]) | Byte.toUnsignedInt(vertexBytes[offset + 1]) << 8
			| Byte.toUnsignedInt(vertexBytes[offset + 2]) << 16 | vertexBytes[offset + 3] << 24;
	}

	private static float floatAt(byte[] vertexBytes, int offset) {
		return Float.intBitsToFloat(word(vertexBytes, offset));
	}

	private static float snorm(byte[] vertexBytes, int offset) {
		return vertexBytes[offset] / 127.0f;
	}

	/** A loader whose own jar answers before the parent classpath, so the jar's own Sodium classes are the ones looked up. */
	private static final class ChildFirst extends URLClassLoader {
		ChildFirst(URL[] urls) {
			super("sodium-encoder", urls, ClassLoader.getSystemClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null && name.startsWith("net.caffeinemc.mods.sodium.")) {
					try {
						loaded = findClass(name);
					} catch (ClassNotFoundException e) {
						loaded = null;
					}
				}
				if (loaded == null) {
					loaded = super.loadClass(name, false);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}
}
