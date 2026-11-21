// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.pack.textures.TextureImage;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The game's resources for the pack's {@code minecraft:} custom textures (E6-B): the file the game's resource manager finds
 * for the path (resource packs included), decoded to RGBA8, and the game's own texture views of the atlases and the
 * lightmap, looked up on every call (a view the game replaces is never held here). Called on the render thread.
 */
public final class GameResources {

	private static final String PREFIX = "minecraft:";

	/** The game's views of the atlases and the lightmap, resolved by the game each time they are asked for. */
	public static final CustomTextureSet.GameViews VIEWS = new CustomTextureSet.GameViews() {
		@Override
		public boolean isAtlas(Identifier atlas) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getTextureManager() == null) {
				return false;
			}
			return mc.getTextureManager().getTexture(atlas) instanceof TextureAtlas;
		}

		@Override
		public long atlas(Identifier atlas) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getTextureManager() == null) {
				return 0L;
			}
			try {
				// The game's view getter throws IllegalStateException until the game has created the view: 0 until then.
				return MetalBridge.viewHandle(mc.getTextureManager().getTexture(atlas).getTextureView());
			} catch (IllegalStateException e) {
				return 0L;
			}
		}

		@Override
		public long lightmap() {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.gameRenderer == null) {
				return 0L;
			}
			var view = mc.gameRenderer.levelLightmap();
			return view == null ? 0L : MetalBridge.viewHandle(view);
		}
	};

	private GameResources() {
	}

	/** The decoded texels of a {@code minecraft:<path>} resource, or null when the game has no such file or it is not an image. */
	public static @Nullable TextureImage read(String minecraftPath) {
		if (!minecraftPath.startsWith(PREFIX)) {
			return null;
		}
		Identifier id = Identifier.tryBuild("minecraft", minecraftPath.substring(PREFIX.length()));
		if (id == null) {
			return null;  // a pack typo (upper case, spaces) names no resource; the sampler keeps the engine's source
		}
		var resource = Minecraft.getInstance().getResourceManager().getResource(id);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return TextureImage.decodeImage(in.readAllBytes()).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}
}
