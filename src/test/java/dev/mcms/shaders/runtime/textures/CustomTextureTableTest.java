// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.Wrap;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Dim;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Entry;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Source;
import dev.mcms.shaders.pack.textures.TextureImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The custom texture table: sources, filter and wrap rules, stages (Iris's final), unit types and problems. */
final class CustomTextureTableTest {

	@TempDir
	Path dir;

	/** The folder pack under the test directory, with its shaders root. */
	private PackSource open() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders"));
		return PackSource.open(dir.resolve("pack"));
	}

	private void writePng(String relative, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, 0xff000000 | (x * 40) << 16 | (y * 40) << 8 | 7);
			}
		}
		Path p = dir.resolve("pack").resolve("shaders").resolve(relative);
		Files.createDirectories(p.getParent());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		Files.write(p, out.toByteArray());
	}

	private static CustomTextureTable table(PackSource pack, String properties) {
		return CustomTextureTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
	}

	@Test
	void programsMapToTheirStagesAndFinalTakesTheCompositeTextures() {
		assertEquals("gbuffers", CustomTextureTable.stageOf("gbuffers_terrain"));
		assertEquals("gbuffers", CustomTextureTable.stageOf("shadow_solid"));
		assertEquals("shadowcomp", CustomTextureTable.stageOf("shadowcomp"));
		assertEquals("composite", CustomTextureTable.stageOf("composite3"));
		assertEquals("composite", CustomTextureTable.stageOf("final"));
		assertEquals("deferred", CustomTextureTable.stageOf("deferred1"));
		assertEquals("prepare", CustomTextureTable.stageOf("prepare"));
		assertEquals("setup", CustomTextureTable.stageOf("setup"));
		assertEquals("begin", CustomTextureTable.stageOf("begin"));
		assertNull(CustomTextureTable.stageOf("dh_terrain"));
	}

	@Test
	void aPackFileImageIsNearestAndRepeatUnlessItsMcmetaSaysOtherwise() throws IOException {
		writePng("textures/noise.png", 2, 2);
		writePng("textures/plain.png", 2, 2);
		CustomTextureTable t = table(open(), "texture.composite.colortex4=textures/plain.png\n"
			+ "texture.deferred.colortex5=textures/noise.png\n");
		Entry plain = t.resolve("composite", "colortex4", Dim.D2);
		assertNotNull(plain);
		assertEquals(Filter.NEAREST, plain.filter());
		assertEquals(Wrap.REPEAT, plain.wrap());
		assertEquals(2, plain.image().width());
		assertEquals(TextureImage.RGBA8, plain.image().format());
		Entry blurred = t.resolve("deferred", "colortex5", Dim.D2);
		assertNotNull(blurred);
		assertEquals(Filter.NEAREST, blurred.filter(), "no mcmeta for this file: nearest");
	}

	@Test
	void mcmetaBesideTheFileOverridesFilterAndWrap() throws IOException {
		writePng("textures/perlin.png", 2, 2);
		Files.writeString(dir.resolve("pack/shaders/textures/perlin.png.mcmeta"),
			"{\n  \"texture\":\n  {\n    \"blur\": true,\n    \"clamp\": true\n  }\n}");
		CustomTextureTable t = table(open(),
			"texture.composite.colortex4=textures/perlin.png\n");
		Entry e = t.resolve("composite", "colortex4", Dim.D2);
		assertNotNull(e);
		assertEquals(Filter.LINEAR, e.filter(), "blur true is bilinear");
		assertEquals(Wrap.CLAMP_TO_EDGE, e.wrap(), "clamp true clamps");
	}

	@Test
	void rawTextureIsLinearAndClampedAndKeepsItsTexelsAndType() throws IOException {
		float[] values = {0f, 0.5f, 1f, 1f, 0.25f, 0.25f, 0.25f, 1f};
		java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(values.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (float v : values) {
			bytes.putFloat(v);
		}
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/lut.dat"), bytes.array());
		PackSource pack = open();
		CustomTextureTable t = table(pack, "texture.composite.gaux1=textures/lut.dat TEXTURE_1D RGBA32F 2 RGBA FLOAT\n");
		Entry e = t.resolve("composite", "colortex4", Dim.D1);
		assertNotNull(e, "gaux1 is colortex4 and a 1D sampler reads a 1D texture");
		assertEquals(Dim.D1, e.dim());
		assertEquals(Filter.LINEAR, e.filter());
		assertEquals(Wrap.CLAMP_TO_EDGE, e.wrap());
		assertNull(e.image(), "a raw file keeps its bytes until the runtime converts them");
		assertEquals("RGBA32F", e.raw().internalFormat());
		assertEquals(2, e.raw().width());
		assertEquals(1, e.raw().height(), "a 1D texture is a 2D texture of height 1 (the translator's contract)");
		assertArrayEquals(bytes.array(), e.raw().bytes());
		TextureImage converted = TexelCodec.rawImage(e.raw());
		assertNotNull(converted);
		assertEquals(125, converted.format(), "RGBA32F");
		assertEquals(TextureImage.TYPE_2D, converted.type());
		assertNull(t.resolve("composite", "colortex4", Dim.D2), "no 2D texture on that unit");
	}

	@Test
	void rawVolumeIsA3DTextureWithItsSlices() throws IOException {
		byte[] texels = new byte[2 * 2 * 2 * 4];
		for (int i = 0; i < texels.length; i++) {
			texels[i] = (byte) (i * 3);
		}
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/vol.dat"), texels);
		CustomTextureTable t = table(open(),
			"texture.gbuffers.colortex6=textures/vol.dat TEXTURE_3D RGBA8 2 2 2 RGBA UNSIGNED_BYTE\n");
		Entry e = t.resolve("gbuffers_basic", "colortex6", Dim.D3);
		assertNotNull(e);
		assertEquals(2, e.raw().depth());
		assertEquals(Filter.LINEAR, e.filter(), "raw files are bilinear by default");
		TextureImage volume = TexelCodec.rawImage(e.raw());
		assertNotNull(volume);
		assertEquals(TextureImage.TYPE_3D, volume.type());
		assertEquals(2, volume.depth());
		assertArrayEquals(texels, volume.bytes());
	}

	@Test
	void rectangleTextureIsClampedWhateverItsMcmetaSays() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/rect.dat"), new byte[16]);
		Files.writeString(dir.resolve("pack/shaders/textures/rect.dat.mcmeta"), "{\"texture\":{\"clamp\":false}}");
		CustomTextureTable t = table(open(),
			"customTexture.rectTex=textures/rect.dat TEXTURE_RECTANGLE RGBA8 2 2 RGBA UNSIGNED_BYTE\n");
		Entry e = t.resolve("composite", "rectTex", Dim.RECT);
		assertNotNull(e);
		assertEquals(Wrap.CLAMP_TO_EDGE, e.wrap());
		assertEquals(Dim.RECT, e.dim());
	}

	@Test
	void customTextureBindsAnySamplerOfThatNameInEveryStage() throws IOException {
		writePng("textures/lut.png", 4, 4);
		CustomTextureTable t = table(open(), "customTexture.lut=textures/lut.png\n");
		assertNotNull(t.resolve("gbuffers_textured", "lut", Dim.D2));
		assertNotNull(t.resolve("composite", "lut", Dim.D2));
		assertNotNull(t.resolve("setup", "lut", Dim.D2));
		assertNull(t.resolve("composite", "colortex1", Dim.D2), "a different sampler gets nothing");
	}

	@Test
	void stageTextureDoesNotLeakIntoOtherStages() throws IOException {
		writePng("textures/x.png", 2, 2);
		CustomTextureTable t = table(open(), "texture.deferred.colortex2=textures/x.png\n");
		assertNotNull(t.resolve("deferred1", "colortex2", Dim.D2));
		assertNull(t.resolve("composite", "colortex2", Dim.D2));
		assertNull(t.resolve("gbuffers_water", "colortex2", Dim.D2));
	}

	@Test
	void compositeTexturesBindInFinalAsIrisDoes() throws IOException {
		writePng("textures/x.png", 2, 2);
		CustomTextureTable t = table(open(), "texture.composite.colortex3=textures/x.png\n");
		assertNotNull(t.resolve("final", "colortex3", Dim.D2), "Iris binds composite textures in final");
		assertNull(t.resolve("gbuffers_basic", "colortex3", Dim.D2));
	}

	@Test
	void suffixesGiveSeveralTypesOnOneUnitAndTheSamplerTypePicksOne() throws IOException {
		writePng("textures/img.png", 2, 2);
		Files.write(dir.resolve("pack/shaders/textures/line.dat"), new byte[8]);
		CustomTextureTable t = table(open(),
			"texture.composite.gaux2=textures/img.png\n"
				+ "texture.composite.gaux2.1=textures/line.dat TEXTURE_1D RGBA8 2 RGBA UNSIGNED_BYTE\n");
		assertEquals(TextureImage.TYPE_2D, t.resolve("composite", "colortex5", Dim.D2).image().type());
		assertEquals(Dim.D1, t.resolve("composite", "colortex5", Dim.D1).dim());
	}

	@Test
	void minecraftSourcesAreClassified() throws IOException {
		CustomTextureTable t = table(open(),
			"texture.deferred.colortex7=minecraft:dynamic/lightmap_1\n"
				+ "texture.deferred.colortex8=minecraft:textures/atlas/blocks.png\n"
				+ "texture.deferred.colortex9=minecraft:textures/atlas/blocks_n.png\n"
				+ "texture.deferred.colortex10=minecraft:textures/atlas/blocks_s.png\n"
				+ "texture.deferred.colortex11=minecraft:textures/block/dirt.png\n");
		assertEquals(Source.LIGHTMAP, t.resolve("deferred", "colortex7", Dim.D2).source());
		assertEquals(Filter.LINEAR, t.resolve("deferred", "colortex7", Dim.D2).filter());
		assertEquals(Wrap.CLAMP_TO_EDGE, t.resolve("deferred", "colortex7", Dim.D2).wrap());
		assertEquals(Source.ATLAS, t.resolve("deferred", "colortex8", Dim.D2).source());
		assertEquals(Source.DEFAULT_NORMAL, t.resolve("deferred", "colortex9", Dim.D2).source());
		assertEquals(Source.DEFAULT_SPECULAR, t.resolve("deferred", "colortex10", Dim.D2).source());
		assertEquals(Source.RESOURCE, t.resolve("deferred", "colortex11", Dim.D2).source());
		assertNull(t.resolve("deferred", "colortex11", Dim.D2).image(), "resources are read by the render side");
		assertTrue(t.problems().isEmpty());
	}

	@Test
	void minecraftPathsAreNotPackFiles() throws IOException {
		CustomTextureTable t = table(open(),
			"texture.deferred.colortex8=minecraft:textures/atlas/blocks.png\n");
		assertEquals(Filter.NEAREST, t.resolve("deferred", "colortex8", Dim.D2).filter());
		assertEquals(Wrap.REPEAT, t.resolve("deferred", "colortex8", Dim.D2).wrap());
	}

	@Test
	void missingFilesAndBadDirectivesAreProblemsNotEntries() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/bad.dat"), new byte[4]);
		CustomTextureTable t = table(open(),
			"texture.deferred.colortex1=textures/absent.png\n"
				+ "texture.sky.colortex2=minecraft:textures/block/dirt.png\n"
				+ "texture.deferred.colortex3=textures/bad.dat TEXTURE_2D RGBA8 1 1 RGBA NOTATYPE\n");
		assertNull(t.resolve("deferred", "colortex1", Dim.D2));
		assertNull(t.resolve("sky", "colortex2", Dim.D2));
		assertNull(t.resolve("deferred", "colortex3", Dim.D2), "a pixel type the engine does not read");
		assertEquals(3, t.problems().size(), t.problems().toString());
	}

	@Test
	void aRawFileShorterThanItsDimensionsIsRefused() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/short.dat"), new byte[4]);
		CustomTextureTable t = table(open(),
			"texture.deferred.colortex4=textures/short.dat TEXTURE_2D RGBA8 4 4 RGBA UNSIGNED_BYTE\n");
		assertNull(t.resolve("deferred", "colortex4", Dim.D2));
		assertEquals(1, t.problems().size());
	}

	@Test
	void textureNoiseDecodesThePackFile() throws IOException {
		writePng("textures/noise.png", 8, 8);
		CustomTextureTable t = table(open(), "texture.noise=textures/noise.png\n");
		assertNotNull(t.noise());
		assertEquals(8, t.noise().image().width());
	}

	@Test
	void theTableRecordsWhatEachProgramResolvedTo() throws IOException {
		writePng("textures/x.png", 2, 2);
		CustomTextureTable t = table(open(), "texture.composite.colortex2=textures/x.png\n");
		assertTrue(t.bindings().isEmpty());
		t.resolve("composite2", "colortex2", Dim.D2);
		assertTrue(t.bindings().get("composite2").containsKey("colortex2"));
		assertFalse(t.bindings().containsKey("gbuffers_basic"));
	}

	@Test
	void unknownStageIsAProblem() throws IOException {
		writePng("textures/x.png", 2, 2);
		CustomTextureTable t = table(open(), "texture.sky.colortex2=textures/x.png\n");
		assertEquals(1, t.problems().size());
		assertTrue(t.problems().get(0).contains("no stage sky"));
	}

	@Test
	void theNoiseTextureIsWhatNoisetexResolvesToWithItsOwnFilterAndWrap() throws IOException {
		writePng("textures/noise.png", 8, 8);
		Files.writeString(dir.resolve("pack").resolve("shaders/textures/noise.png.mcmeta"),
			"{\"texture\":{\"blur\":true,\"clamp\":true}}");
		CustomTextureTable t = table(open(), "texture.noise=textures/noise.png\n");
		Entry noise = t.resolve("composite", "noisetex", Dim.D2);
		assertNotNull(noise, "noisetex with no stage or custom binding takes texture.noise");
		assertEquals(t.noise(), noise);
		assertEquals(Filter.LINEAR, noise.filter(), "the pack's mcmeta blur applies to the noise");
		assertEquals(Wrap.CLAMP_TO_EDGE, noise.wrap(), "and its clamp");
		assertNotNull(t.bindings().get("composite").get("noisetex"), "the program's binding is recorded");
	}

	@Test
	void aStageTextureNamedNoisetexBeatsTheNoiseFile() throws IOException {
		writePng("textures/noise.png", 8, 8);
		writePng("textures/other.png", 2, 2);
		CustomTextureTable t = table(open(),
			"texture.noise=textures/noise.png\ntexture.composite.noisetex=textures/other.png\n");
		Entry bound = t.resolve("composite", "noisetex", Dim.D2);
		assertNotNull(bound);
		assertEquals(2, bound.image().width(), "the stage's own texture is the one bound");
	}

	@Test
	void anInternalFormatTheEngineDoesNotStoreIsRefusedAtTableBuild() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/raw.dat"), new byte[4]);
		CustomTextureTable t = table(open(),
			"customTexture.raw=textures/raw.dat TEXTURE_2D NOTAFORMAT 1 1 RGBA UNSIGNED_BYTE\n");
		assertNull(t.resolve("composite", "raw", Dim.D2), "no binding: the sampler keeps the engine's source");
		assertTrue(t.problems().get(0).contains("not one the engine stores"), t.problems().toString());
	}

	@Test
	void aPackFileThatIsNoImageIsRefusedWithAProblem() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/junk.png"), new byte[] {1, 2, 3});
		CustomTextureTable t = table(open(), "texture.deferred.colortex6=textures/junk.png\n");
		assertNull(t.resolve("deferred", "colortex6", Dim.D2), "no binding: the sampler keeps the engine's source");
		assertTrue(t.problems().get(0).contains("not an image"), t.problems().toString());
	}

	@Test
	void theWordsTheEngineStoresAreAcceptedAtTableBuild() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/textures"));
		Files.write(dir.resolve("pack/shaders/textures/raw.dat"), new byte[12]);
		CustomTextureTable t = table(open(),
			"customTexture.a=textures/raw.dat TEXTURE_1D R11F_G11F_B10F 1 RGB FLOAT\n"
				+ "customTexture.b=textures/raw.dat TEXTURE_1D RGB10_A2UI 1 RGBA_INTEGER UNSIGNED_INT_2_10_10_10_REV\n");
		assertTrue(t.problems().isEmpty(), t.problems().toString());
		assertNotNull(t.resolve("composite", "a", Dim.D1));
		assertNotNull(t.resolve("composite", "b", Dim.D1), "the 1D rule applies to every raw type");
	}
}
