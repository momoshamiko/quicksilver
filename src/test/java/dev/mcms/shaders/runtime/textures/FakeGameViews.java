// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Stands in for the game's atlas and lightmap views in the custom texture tests: each view is a handle the test sets, and
 * the last atlas identifier asked for is kept, so a test can check which atlas a binding named. An identifier is an atlas
 * unless the test lists it in {@link #plainTextures}.
 */
public final class FakeGameViews implements CustomTextureSet.GameViews {

	/** No game views at all: every atlas and the lightmap read zero. */
	public static FakeGameViews none() {
		return new FakeGameViews();
	}

	public long atlasView;
	public long lightmapView;
	public Identifier lastAtlas;
	/** Identifiers the game has as plain textures, not atlases. */
	public final Set<Identifier> plainTextures = new HashSet<>();

	@Override
	public long atlas(Identifier atlas) {
		this.lastAtlas = atlas;
		return this.atlasView;
	}

	@Override
	public long lightmap() {
		return this.lightmapView;
	}

	@Override
	public boolean isAtlas(Identifier atlas) {
		return !this.plainTextures.contains(atlas);
	}
}
