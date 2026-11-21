// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Dim;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E6-B on the device: the set's ids and handles follow the pack's stages. A composite texture binds in the composite and in
 * the final program (Iris), never in a gbuffers program; a pack file's texture has a handle; the game's atlas and lightmap
 * are the game's views at the moment of each draw (asked for again, never held), so a view the game replaces is the one a
 * later draw binds.
 */
@Tag("gpu")
final class CustomTextureSetGpuTest {

	@TempDir
	Path dir;

	@Test
	void compositeTexturesBindInFinalAndAFileHasItsHandle() throws Exception {
		CustomTextureTable t = table("texture.composite.colortex3=textures/a.png\n");
		t.resolve("composite", "colortex3", Dim.D2);
		t.resolve("final", "colortex3", Dim.D2);
		t.resolve("gbuffers_terrain", "colortex3", Dim.D2);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomTextureSet set = CustomTextureSet.create(gpu.context(), t, path -> null, FakeGameViews.none());
			assertNotNull(set);
			int composite = set.id("composite", "colortex3");
			assertTrue(composite >= 0);
			assertNotEquals(0L, set.handle(composite), "a pack file has its texture");
			assertEquals(composite, set.id("final", "colortex3"), "final takes the composite textures");
			assertEquals(-1, set.id("gbuffers_terrain", "colortex3"), "a gbuffers program never bound it: the engine's source");
			assertEquals(0L, set.handle(-1), "no id, no texture");
			set.close();
			assertEquals(0L, set.handle(composite), "a closed set holds no texture");
		}
	}

	@Test
	void theLightmapIsTheGameViewOfTheMomentAndNeverAHeldPointer() throws Exception {
		CustomTextureTable t = table("texture.composite.colortex4=minecraft:dynamic/lightmap_1\n");
		t.resolve("composite", "colortex4", Dim.D2);
		FakeGameViews views = FakeGameViews.none();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomTextureSet set = CustomTextureSet.create(gpu.context(), t, path -> null, views);
			assertNotNull(set);
			int lightmap = set.id("composite", "colortex4");
			assertTrue(lightmap >= 0);
			assertEquals(0L, set.handle(lightmap), "no lightmap view before the game has one");
			views.lightmapView = 0x5678L;
			assertEquals(0x5678L, set.handle(lightmap), "the view the game has now");
			views.lightmapView = 0x9abcL;
			assertEquals(0x9abcL, set.handle(lightmap), "the game replaced its lightmap: the next draw binds the new one");
			set.close();
		}
	}

	@Test
	void anAtlasIsTheGameViewOfEachDrawAndNamesItsIdentifier() throws Exception {
		CustomTextureTable t = table("texture.deferred.colortex5=minecraft:textures/atlas/blocks.png\n");
		t.resolve("deferred1", "colortex5", Dim.D2);
		FakeGameViews views = FakeGameViews.none();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomTextureSet set = CustomTextureSet.create(gpu.context(), t, path -> null, views);
			assertNotNull(set);
			int atlas = set.id("deferred1", "colortex5");
			assertTrue(atlas >= 0, "deferred1 is a deferred program");
			views.atlasView = 0x1234L;
			assertEquals(0x1234L, set.handle(atlas), "the atlas is the view the game has now");
			assertEquals(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"), views.lastAtlas,
				"the game is asked for the block atlas by its identifier");
			views.atlasView = 0x4321L;
			assertEquals(0x4321L, set.handle(atlas), "a view the game replaced is not held: the next draw asks again");
			set.close();
		}
	}

	private CustomTextureTable table(String properties) throws Exception {
		Path root = dir.resolve("pack");
		Files.createDirectories(root.resolve("shaders/textures"));
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xffff0000);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);
		Files.write(root.resolve("shaders/textures/a.png"), png.toByteArray());
		try (PackSource pack = PackSource.open(root)) {
			return CustomTextureTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
		}
	}
}
