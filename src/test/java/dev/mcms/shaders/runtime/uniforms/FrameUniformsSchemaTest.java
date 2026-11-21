// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * The frame block's coverage of the documented uniform table (engine-pack-format.md section 10; the OptiFine doc's
 * "Uniforms" table; the Iris uniform pages): every uniform the game state gives without per-draw hooks is a field with
 * the GLSL type packs declare, and the uniforms of that table that are NOT fields are exactly the ones left to their
 * documented milestone — per-draw values (E4), shadow matrices (E5), Distant Horizons (E7), texture-atlas values that
 * exist only during a draw (E4), and the ones the 26.3 game API gives no value for yet (marked per name below).
 */
class FrameUniformsSchemaTest {
	private static final Map<String, String> FIELDS = fields();

	private static Map<String, String> fields() {
		Map<String, String> m = new TreeMap<>();
		for (var f : FrameUniforms.schema(0).fields()) {
			m.put(f.name(), f.type());
		}
		return m;
	}

	/** The frame uniform table of section 10, name to the GLSL type packs declare it with. */
	private static final List<String[]> TABLE = List.of(
		new String[] { "heldItemId", "int" }, new String[] { "heldItemId2", "int" },
		new String[] { "heldBlockLightValue", "int" }, new String[] { "heldBlockLightValue2", "int" },
		new String[] { "fogMode", "int" }, new String[] { "fogStart", "float" }, new String[] { "fogEnd", "float" },
		new String[] { "fogShape", "int" }, new String[] { "fogDensity", "float" }, new String[] { "fogColor", "vec3" },
		new String[] { "skyColor", "vec3" }, new String[] { "worldTime", "int" }, new String[] { "worldDay", "int" },
		new String[] { "moonPhase", "int" }, new String[] { "frameCounter", "int" },
		new String[] { "frameTime", "float" }, new String[] { "frameTimeCounter", "float" },
		new String[] { "sunAngle", "float" }, new String[] { "shadowAngle", "float" },
		new String[] { "rainStrength", "float" }, new String[] { "wetness", "float" },
		new String[] { "thunderStrength", "float" }, new String[] { "aspectRatio", "float" },
		new String[] { "viewWidth", "float" }, new String[] { "viewHeight", "float" },
		new String[] { "screenBrightness", "float" }, new String[] { "near", "float" }, new String[] { "far", "float" },
		new String[] { "alphaTestRef", "float" }, new String[] { "centerDepthSmooth", "float" },
		new String[] { "sunPosition", "vec3" }, new String[] { "moonPosition", "vec3" },
		new String[] { "shadowLightPosition", "vec3" }, new String[] { "upPosition", "vec3" },
		new String[] { "cameraPosition", "vec3" }, new String[] { "previousCameraPosition", "vec3" },
		new String[] { "eyeAltitude", "float" }, new String[] { "gbufferModelView", "mat4" },
		new String[] { "gbufferModelViewInverse", "mat4" }, new String[] { "gbufferProjection", "mat4" },
		new String[] { "gbufferProjectionInverse", "mat4" }, new String[] { "gbufferPreviousModelView", "mat4" },
		new String[] { "gbufferPreviousProjection", "mat4" }, new String[] { "isEyeInWater", "int" },
		new String[] { "nightVision", "float" }, new String[] { "blindness", "float" },
		new String[] { "darknessFactor", "float" }, new String[] { "darknessLightFactor", "float" },
		new String[] { "playerMood", "float" }, new String[] { "eyeBrightness", "ivec2" },
		new String[] { "eyeBrightnessSmooth", "ivec2" }, new String[] { "hideGUI", "int" },
		new String[] { "lightningBoltPosition", "vec4" });

	@Test
	void everyGameStateUniformOfTheTableIsAFieldWithItsGlslType() {
		for (String[] entry : TABLE) {
			assertEquals(entry[1], FIELDS.get(entry[0]),
				() -> entry[0] + " must be a field of the frame block with the table's type");
		}
	}

	/** The Iris extras the game state gives are fields too (Iris "Camera", "Player Status", "Biome / Dimension"). */
	@Test
	void theGameStateIrisExtrasAreFields() {
		for (String name : new String[] { "cameraPositionFract", "cameraPositionInt", "previousCameraPositionFract",
			"previousCameraPositionInt", "eyePosition", "relativeEyePosition", "playerLookVector",
			"playerBodyVector", "currentPlayerHealth", "maxPlayerHealth", "currentPlayerArmor", "maxPlayerArmor",
			"currentPlayerAir", "maxPlayerAir", "currentPlayerHunger", "maxPlayerHunger", "isSpectator",
			"isRightHanded", "firstPersonCamera", "constantMood", "isRiding", "inSwimmingAnimation", "feetInWater",
			"isElytraFlying", "vehicleInWater", "is_alive", "is_burning", "is_child", "is_glowing", "is_hurt",
			"is_in_lava", "is_in_water", "is_invisible", "is_on_ground", "is_ridden", "is_sneaking", "is_sprinting",
			"is_wet", "currentDate", "currentTime", "currentYearTime", "biome", "biome_category",
			"biome_precipitation", "temperature", "rainfall", "ambientLight", "bedrockLevel", "cloudHeight",
			"hasCeiling", "hasSkylight", "heightLimit", "logicalHeightLimit", "endFlashPosition",
			"endFlashIntensity", "previousEndFlashIntensity" }) {
			assertTrue(FIELDS.containsKey(name), () -> name + " must be a field of the frame block");
		}
	}

	/**
	 * The table's uniforms that are NOT fields are exactly the documented later ones: per-draw geometry values and the
	 * per-draw matrices (E4's draw block), Distant Horizons (E7), and the atlas values a
	 * draw sets. A name in this list that becomes a field later must move to the tests above.
	 */
	@Test
	void theMissingUniformsAreExactlyTheDocumentedLaterOnes() {
		for (String name : new String[] { "modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix",
			"projectionMatrixInverse", "textureMatrix", "normalMatrix", "entityColor", "entityId", "blockEntityId",
			"blendFunc", "instanceId", "renderStage", "atlasSize", "spriteBounds", "colorModulator", "modelOffset",
			"chunkOffset", "bossBattle", "terrainTextureSize", "terrainIconSize", "vehicleId", "currentRenderedItemId",
			"currentSelectedBlockId", "currentSelectedBlockPos", "vehicleLookVector", "relativeVehiclePosition",
			"currentColorSpace", "textureFilteringMode", "dhNearPlane", "dhFarPlane", "dhProjection",
			"dhProjectionInverse", "dhPreviousProjection", "dhRenderDistance" }) {
			assertTrue(!FIELDS.containsKey(name), () -> name + " is a later milestone's uniform, not this block's");
		}
	}

	/** No two fields share a byte (the std140 offsets are the layout the write path fills). */
	@Test
	void offsetsDoNotOverlapAndTheBlockIsAligned() {
		int cursor = 0;
		for (var f : FrameUniforms.schema(0).fields()) {
			assertTrue(f.offset() >= cursor, () -> f.name() + " overlaps the field before it");
			cursor = f.offset() + Std140.size(f.type());
		}
		assertTrue(FrameUniforms.size() >= cursor && FrameUniforms.size() < cursor + 16,
			"the block's size is its fields' extent, padded to the 16-byte alignment");
	}

	/**
	 * std140 (OpenGL 4.6 section 7.6.2.2): a four-component vector takes 16 bytes, and a matrix is an array of
	 * column vectors — the array element stride is rounded to the vec4 alignment, so {@code mat2} is two 16-byte
	 * columns (32 bytes, 16-byte aligned), not two packed {@code vec2}s. The frame block's {@code vec4}
	 * ({@code lightningBoltPosition}) occupies exactly 16 bytes.
	 */
	@Test
	void std140SizesFourComponentVectorsAndMat2() {
		assertEquals(16, Std140.size("vec4"));
		assertEquals(16, Std140.size("ivec4"));
		assertEquals(16, Std140.align("vec4"));
		assertEquals(16, Std140.align("ivec4"));
		assertEquals(16, Std140.align("mat2"));
		assertEquals(32, Std140.size("mat2"));
		var block = Std140.block("T", 0, java.util.List.of(new Std140.Member("a", "vec4"),
			new Std140.Member("m", "mat2"), new Std140.Member("f", "float")));
		assertEquals(0, block.fields().get(0).offset());
		assertEquals(16, block.fields().get(1).offset());
		assertEquals(48, block.fields().get(2).offset());
		assertEquals(64, Std140.paddedSize(block));
		// A mat2 after a float also aligns to 16, not to the vec2's 8.
		var after = Std140.block("T2", 0, java.util.List.of(new Std140.Member("f", "float"),
			new Std140.Member("m", "mat2")));
		assertEquals(0, after.fields().get(0).offset());
		assertEquals(16, after.fields().get(1).offset());
		assertEquals(48, Std140.paddedSize(after));
		int bolt = 0, flash = 0;
		for (var f : FrameUniforms.schema(0).fields()) {
			if (f.name().equals("lightningBoltPosition")) {
				bolt = f.offset();
			} else if (f.name().equals("endFlashPosition")) {
				flash = f.offset();
			}
		}
		assertEquals(16, flash - bolt);
	}
}
