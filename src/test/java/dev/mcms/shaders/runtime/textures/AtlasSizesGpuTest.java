// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.metal.BackendPassHarness;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The atlas table matches a view's texture by identity: a view of a registered atlas reads that atlas's size, any other view
 * reads (0, 0), and only the block atlas sets the block size. The textures are the device's own, so the identity is the
 * one the game's views carry. The visit uses the game's classes: the block atlas is keyed by its definition's id
 * ({@link AtlasIds#BLOCKS}) and recognised by its texture's location ({@link TextureAtlas#LOCATION_BLOCKS}).
 */
@Tag("gpu")
class AtlasSizesGpuTest {

	private static final int ATLAS_W = 64, ATLAS_H = 32, OTHER = 16;

	@BeforeAll
	static void device() throws Exception {
		// MetalPipeline's cache dir reads Fabric's game dir, which no test has: the cache is off before any Metal
		// class initialises (the property is read once, in its static init).
		System.setProperty("mcms.metal.mslCache", "false");
		// The game's atlases are made on the render thread's device (TextureAtlas's constructor reads RenderSystem's device).
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // once per JVM: another GPU test class may have initialised it with the device
		}
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
	}

	@Test
	void aViewOfARegisteredAtlasReadsItsSizeAndOtherViewsReadZero() throws Exception {
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture atlas = device.createTexture("test block atlas", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
			ATLAS_W, ATLAS_H, 1, 1);
		GpuTexture other = device.createTexture("test entity texture", GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM, OTHER, OTHER, 1, 1);
		GpuTextureView atlasView = device.createTextureView(atlas);
		GpuTextureView otherView = device.createTextureView(other);
		try {
			AtlasSizes sizes = new AtlasSizes();
			sizes.add(atlas, true);
			int[] out = new int[2];
			sizes.sizeOf(atlasView, out);
			assertArrayEquals(new int[] {ATLAS_W, ATLAS_H}, out, "a view of the atlas reads the atlas's size");
			sizes.sizeOf(otherView, out);
			assertArrayEquals(new int[] {0, 0}, out, "a texture no atlas names reads (0, 0), as Iris and OptiFine document");
			sizes.sizeOf(null, out);
			assertArrayEquals(new int[] {0, 0}, out, "no bound view reads (0, 0)");
			assertEquals(ATLAS_W, sizes.blockWidth(), "the block atlas's width");
			assertEquals(ATLAS_H, sizes.blockHeight(), "the block atlas's height");
		} finally {
			otherView.close();
			atlasView.close();
			other.close();
			atlas.close();
		}
	}

	@Test
	void onlyTheBlockAtlasSetsTheBlockSize() throws Exception {
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture particles = device.createTexture("test particle atlas", GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM, OTHER, ATLAS_H, 1, 1);
		GpuTextureView view = device.createTextureView(particles);
		try {
			AtlasSizes sizes = new AtlasSizes();
			sizes.add(particles, false);
			int[] out = new int[2];
			sizes.sizeOf(view, out);
			assertArrayEquals(new int[] {OTHER, ATLAS_H}, out, "a registered non-block atlas reads its own size");
			assertEquals(0, sizes.blockWidth(), "no block atlas registered: the block width is 0");
			assertEquals(0, sizes.blockHeight(), "no block atlas registered: the block height is 0");
		} finally {
			view.close();
			particles.close();
		}
	}

	@Test
	void refreshFindsTheBlockAtlasByItsLocationUnderTheGamesKey() throws Exception {
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture block = device.createTexture("test block atlas", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
			ATLAS_W, ATLAS_H, 1, 1);
		GpuTextureView view = device.createTextureView(block);
		try {
			AtlasSizes sizes = new AtlasSizes();
			// The game's manager keys the block atlas by AtlasIds.BLOCKS, which is not the atlas's location.
			sizes.refresh(visitor -> visitor.accept(AtlasIds.BLOCKS, uploaded(TextureAtlas.LOCATION_BLOCKS, block)));
			assertEquals(ATLAS_W, sizes.blockWidth(), "the block atlas is recognised by its location: its width is the block width");
			assertEquals(ATLAS_H, sizes.blockHeight(), "the block atlas is recognised by its location: its height is the block height");
			int[] out = new int[2];
			sizes.sizeOf(view, out);
			assertArrayEquals(new int[] {ATLAS_W, ATLAS_H}, out, "the block atlas's view reads its size");
		} finally {
			view.close();
			block.close();
		}
	}

	@Test
	void refreshSkipsAnAtlasTheGameHasNotUploadedAndEmptiesForANullSource() throws Exception {
		GpuDevice device = BackendPassHarness.newDevice();
		GpuTexture particles = device.createTexture("test particle atlas", GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM, OTHER, ATLAS_H, 1, 1);
		GpuTextureView view = device.createTextureView(particles);
		try {
			AtlasSizes sizes = new AtlasSizes();
			// The block atlas has no texture yet: its getTexture throws, and the refresh must not.
			TextureAtlas notUploaded = new TextureAtlas(TextureAtlas.LOCATION_BLOCKS);
			sizes.refresh(visitor -> {
				visitor.accept(AtlasIds.BLOCKS, notUploaded);
				visitor.accept(AtlasIds.PARTICLES, uploaded(TextureAtlas.LOCATION_PARTICLES, particles));
			});
			assertEquals(0, sizes.blockWidth(), "an atlas with no texture is not the block atlas of the table");
			int[] out = new int[2];
			sizes.sizeOf(view, out);
			assertArrayEquals(new int[] {OTHER, ATLAS_H}, out, "the uploaded atlas is in the table");

			sizes.refresh(null);
			sizes.sizeOf(view, out);
			assertArrayEquals(new int[] {0, 0}, out, "a null source empties the table");
		} finally {
			view.close();
			particles.close();
		}
	}

	/** The game's atlas at {@code location}, holding {@code held} as its uploaded texture. */
	private static TextureAtlas uploaded(Identifier location, GpuTexture held) {
		// Not named texture: inside the subclass that name is AbstractTexture's own field, which is null here.
		return new TextureAtlas(location) {
			@Override
			public GpuTexture getTexture() {
				return held;
			}
		};
	}
}
