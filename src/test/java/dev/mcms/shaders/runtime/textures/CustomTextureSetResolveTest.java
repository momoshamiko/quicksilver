// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Dim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The atlas bindings of a custom texture set. The game is asked once, when the set is created, whether each atlas binding
 * names a real atlas; a binding that names none is a missing line and binds nothing. A draw asks the game only for the view
 * of an atlas that was accepted, and never asks whether it is an atlas.
 */
final class CustomTextureSetResolveTest {

	@TempDir
	Path dir;

	/** The game's views with every call counted; every identifier is an atlas unless it is listed in {@code plain}. */
	private static final class CountingViews implements CustomTextureSet.GameViews {
		final Set<Identifier> plain = new HashSet<>();
		final Map<Identifier, Integer> isAtlasCalls = new HashMap<>();
		final List<Identifier> atlasCalls = new ArrayList<>();

		@Override
		public boolean isAtlas(Identifier atlas) {
			this.isAtlasCalls.merge(atlas, 1, Integer::sum);
			return !this.plain.contains(atlas);
		}

		@Override
		public long atlas(Identifier atlas) {
			this.atlasCalls.add(atlas);
			return 0x1234L;
		}

		@Override
		public long lightmap() {
			return 0L;
		}
	}

	private CustomTextureTable table(String properties) throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders"));
		try (PackSource pack = PackSource.open(dir.resolve("pack"))) {
			return CustomTextureTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
		}
	}

	@Test
	void createAsksTheGameOncePerAtlasAndDrawsAskOnlyForTheView() throws IOException {
		CustomTextureTable t = table("texture.deferred.colortex5=minecraft:textures/atlas/blocks.png\n"
			+ "customTexture.lut=minecraft:textures/atlas/items.png\n");
		t.resolve("deferred1", "colortex5", Dim.D2);
		t.resolve("composite", "lut", Dim.D2);
		CountingViews views = new CountingViews();
		CustomTextureSet set = CustomTextureSet.create(0L, t, path -> null, views);
		assertNotNull(set);
		Identifier blocks = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");
		Identifier items = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/items.png");
		assertEquals(Map.of(blocks, 1, items, 1), views.isAtlasCalls, "one question per atlas, asked when the set is created");
		int blocksId = set.id("deferred1", "colortex5");
		int itemsId = set.id("composite", "lut");
		assertTrue(blocksId >= 0 && itemsId >= 0, "both bindings were recorded");
		assertEquals(0x1234L, set.handle(blocksId));
		assertEquals(0x1234L, set.handle(blocksId));
		assertEquals(0x1234L, set.handle(itemsId));
		assertEquals(List.of(blocks, blocks, items), views.atlasCalls, "each draw asks the game for the current view");
		assertEquals(Map.of(blocks, 1, items, 1), views.isAtlasCalls, "a draw never asks whether an atlas exists");
		assertEquals(List.of(), set.missing());
	}

	@Test
	void anAtlasTheGameDoesNotHaveIsAMissingLineAndDrawsBindNothing() throws IOException {
		CustomTextureTable t = table("texture.deferred.colortex6=minecraft:textures/atlas/notanatlas.png\n");
		t.resolve("deferred", "colortex6", Dim.D2);
		CountingViews views = new CountingViews();
		views.plain.add(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/notanatlas.png"));
		CustomTextureSet set = CustomTextureSet.create(0L, t, path -> null, views);
		assertNotNull(set);
		assertEquals(List.of("minecraft:textures/atlas/notanatlas.png: the game has no such atlas"), set.missing());
		int id = set.id("deferred", "colortex6");
		assertTrue(id >= 0, "the binding is recorded even though it binds nothing");
		assertEquals(0L, set.handle(id), "a binding with no atlas has no view");
		assertEquals(List.of(), views.atlasCalls, "a draw never asks the game for a refused atlas");
	}

	@Test
	void aPathTheGameCannotNameIsAMissingLineWithoutAskingTheGame() throws IOException {
		CustomTextureTable t = table("texture.deferred.colortex6=minecraft:textures/atlas/Blocks.png\n");
		t.resolve("deferred", "colortex6", Dim.D2);
		CountingViews views = new CountingViews();
		CustomTextureSet set = CustomTextureSet.create(0L, t, path -> null, views);
		assertNotNull(set);
		assertEquals(List.of("minecraft:textures/atlas/Blocks.png: not a resource path the game can name"), set.missing());
		assertEquals(Map.of(), views.isAtlasCalls, "an identifier the game cannot name is never looked up");
		assertEquals(0L, set.handle(set.id("deferred", "colortex6")));
		assertEquals(List.of(), views.atlasCalls);
	}
}
