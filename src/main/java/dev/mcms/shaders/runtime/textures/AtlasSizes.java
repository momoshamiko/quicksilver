// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Arrays;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sizes of the game's texture atlases, for the {@code atlasSize} uniform (engine-pack-format.md section 10). The value
 * is the size in pixels of the atlas a draw binds as its main texture, and (0, 0) when the bound texture is not an atlas:
 * OptiFine's uniforms page says "texture atlas size (only set when the atlas texture is bound)", and Iris's rendering
 * uniforms page says it "will return 0 when not".
 *
 * <p>{@link #refresh} reads the game's atlases when a set installs and after each resource load (the game makes its atlases
 * then), and {@link #sizeOf} matches a view's texture against that table by identity. A draw therefore costs a few comparisons
 * and allocates nothing. The block atlas ({@link TextureAtlas#LOCATION_BLOCKS}) is kept apart too: the custom uniforms read it
 * once per frame.
 */
public final class AtlasSizes {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** The most atlases the table holds; the game has about a dozen. A larger count logs once and drops the rest. */
	public static final int CAPACITY = 64;

	private final GpuTexture[] textures = new GpuTexture[CAPACITY];
	private final int[] widths = new int[CAPACITY];
	private final int[] heights = new int[CAPACITY];
	private int count;
	private int blockWidth, blockHeight;
	private boolean overflowLogged;
	/** The visitor of the game's atlases, made once so that a refresh allocates nothing. */
	private final BiConsumer<Identifier, TextureAtlas> visitor = this::visit;

	/** The game's atlases, visited as its atlas manager visits them: each atlas with its key in the manager. */
	@FunctionalInterface
	public interface Source {
		void forEach(BiConsumer<Identifier, TextureAtlas> visitor);
	}

	/**
	 * Replaces the table with the atlases a source gives, or empties it for a null source. The engine calls it when a set
	 * installs and after each resource load, because the game makes its atlases on load: it is not called per frame.
	 *
	 * @param source the game's atlases, or null while the game has none to give
	 */
	public void refresh(@Nullable Source source) {
		Arrays.fill(this.textures, 0, this.count, null);
		this.count = 0;
		this.blockWidth = 0;
		this.blockHeight = 0;
		if (source != null) {
			source.forEach(this.visitor);
		}
	}

	/**
	 * Adds one visited atlas. The game keys its atlases by their definition's id, which is not the texture's location, so the
	 * block atlas is recognised by its location ({@link TextureAtlas#LOCATION_BLOCKS}). An atlas with no texture yet (the game
	 * has not uploaded it) is skipped: its getTexture throws.
	 */
	private void visit(Identifier key, TextureAtlas atlas) {
		GpuTexture texture;
		try {
			texture = atlas.getTexture();
		} catch (IllegalStateException notUploaded) {
			return;
		}
		if (!texture.isClosed()) {
			add(texture, atlas.location().equals(TextureAtlas.LOCATION_BLOCKS));
		}
	}

	/**
	 * Adds one atlas to the table: its texture, its size at mip 0, and whether it is the block atlas.
	 *
	 * @param texture the atlas's texture (matched by identity against the views a draw binds)
	 * @param block   true for the block atlas
	 */
	public void add(GpuTexture texture, boolean block) {
		if (this.count == CAPACITY) {
			if (!this.overflowLogged) {
				this.overflowLogged = true;
				LOG.warn("[mcms-shaders] more than {} texture atlases; the atlasSize of the rest reads (0, 0)", CAPACITY);
			}
			return;
		}
		int width = texture.getWidth(0), height = texture.getHeight(0);
		this.textures[this.count] = texture;
		this.widths[this.count] = width;
		this.heights[this.count] = height;
		this.count++;
		if (block) {
			this.blockWidth = width;
			this.blockHeight = height;
		}
	}

	/**
	 * The size of the atlas a view shows: {@code out[0]} is its width and {@code out[1]} its height. Both are 0 when the view is
	 * null or its texture is not one of the table's atlases. Allocates nothing.
	 *
	 * @param view the view a draw binds as its main texture, or null
	 * @param out  two ints: width and height
	 */
	public void sizeOf(@Nullable GpuTextureView view, int[] out) {
		out[0] = 0;
		out[1] = 0;
		if (view == null) {
			return;
		}
		GpuTexture texture = view.texture();
		for (int i = 0; i < this.count; i++) {
			if (this.textures[i] == texture) {
				out[0] = this.widths[i];
				out[1] = this.heights[i];
				return;
			}
		}
	}

	/** The block atlas's width in pixels, 0 while the table holds none. */
	public int blockWidth() {
		return this.blockWidth;
	}

	/** The block atlas's height in pixels, 0 while the table holds none. */
	public int blockHeight() {
		return this.blockHeight;
	}
}
