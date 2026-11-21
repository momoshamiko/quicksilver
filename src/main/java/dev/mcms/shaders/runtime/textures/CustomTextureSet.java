// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Entry;
import dev.mcms.shaders.pack.textures.TextureImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The GPU side of a pack's custom textures (E6-B), created once per set on the render thread: one Metal texture for every
 * binding the table declares that the pack file or the game's resources supply, uploaded from its decoded texels. The
 * atlas and the lightmap are not copies: {@link #handle} asks the game for its current view each time
 * ({@link GameViews}), so a view the game replaces is never held.
 *
 * <p>A program's sampler finds its texture with {@link #id}, which reads the table's recorded bindings and returns a small
 * integer; {@link #handle} turns the id into the texture. An id of -1 means the pack binds nothing to that sampler, and the
 * caller keeps the engine's own source.
 */
public final class CustomTextureSet implements AutoCloseable {

	/** Reads a {@code minecraft:} resource's texels, or null when the game has no such file (the render side's). */
	@FunctionalInterface
	public interface ResourceReader {
		@Nullable TextureImage read(String minecraftPath);
	}

	/**
	 * The game's views: the atlas and lightmap views are asked for on each draw that samples them (0 while the game has none).
	 */
	public interface GameViews {
		/** Whether the game has an atlas texture under the identifier; asked once per atlas binding, when the set is created. */
		boolean isAtlas(Identifier atlas);

		/** The view of an atlas texture (the block atlas, or the atlas a pack names), asked for on each draw. */
		long atlas(Identifier atlas);

		/** The game's lightmap view. */
		long lightmap();
	}

	/** MTLTextureUsageShaderRead, MTLStorageModePrivate: the textures are read by shaders and uploaded once. */
	private static final int USAGE_SHADER_READ = 1, STORAGE_PRIVATE = 2;

	private final CustomTextureTable table;
	private final List<Entry> entries;
	private final IdentityHashMap<Entry, Integer> ids = new IdentityHashMap<>();
	private final long[] handles;
	/**
	 * The atlas identifiers of the atlas entries, built once: null for other entries, a path that names none, or an atlas the
	 * game lacks.
	 */
	private final @Nullable Identifier[] atlases;
	private final GameViews views;
	private final List<String> missing = new ArrayList<>();

	private CustomTextureSet(CustomTextureTable table, GameViews views) {
		this.table = table;
		this.entries = table.all();
		this.views = views;
		this.handles = new long[this.entries.size()];
		this.atlases = new Identifier[this.entries.size()];
		for (int i = 0; i < this.entries.size(); i++) {
			Entry e = this.entries.get(i);
			this.ids.put(e, i);
			if (e.source() == CustomTextureTable.Source.ATLAS) {
				String path = e.path().substring("minecraft:".length());
				this.atlases[i] = Identifier.tryBuild("minecraft", path);
				if (this.atlases[i] == null) {
					this.missing.add(e.path() + ": not a resource path the game can name");
				}
			}
		}
	}

	/**
	 * Creates every binding's texture the table declares. A resource the game does not have binds the default of its
	 * variant (a {@code _n} resource the flat normal, a {@code _s} one zero) or, otherwise, nothing and a line in
	 * {@link #missing()}. Returns null when the device refused a texture (the caller releases nothing: it holds no handles).
	 */
	public static @Nullable CustomTextureSet create(long ctx, CustomTextureTable table, ResourceReader resources, GameViews views) {
		CustomTextureSet set = new CustomTextureSet(table, views);
		for (int i = 0; i < set.entries.size(); i++) {
			Entry e = set.entries.get(i);
			TextureImage image = switch (e.source()) {
				case PACK_FILE -> packFileOf(e, set);
				case DEFAULT_NORMAL -> TextureImage.defaultNormal();
				case DEFAULT_SPECULAR -> TextureImage.defaultSpecular();
				case RESOURCE -> resourceOrDefault(e, resources, set);
				case ATLAS -> {
					checkAtlas(set, i);
					yield null;
				}
				case LIGHTMAP -> null;  // the game's view, not a copy
			};
			if (image == null) {
				continue;
			}
			long texture = upload(ctx, image);
			if (texture == 0) {
				set.close();
				return null;
			}
			set.handles[i] = texture;
		}
		return set;
	}

	/** A pack file's texels: a decoded image, or a raw file converted to its internal format (null and reported when it is neither). */
	private static @Nullable TextureImage packFileOf(Entry e, CustomTextureSet set) {
		if (e.image() != null) {
			return e.image();
		}
		TextureImage raw = e.raw() == null ? null : TexelCodec.rawImage(e.raw());
		if (raw == null) {
			set.missing.add(e.path() + ": the raw file's internal format or bytes cannot be converted");
		}
		return raw;
	}

	private static @Nullable TextureImage resourceOrDefault(Entry e, ResourceReader resources, CustomTextureSet set) {
		TextureImage image = resources.read(e.path());
		if (image != null) {
			return image;
		}
		if (e.path().endsWith("_n.png")) {
			return TextureImage.defaultNormal();
		}
		if (e.path().endsWith("_s.png")) {
			return TextureImage.defaultSpecular();
		}
		set.missing.add(e.path() + ": the game has no such texture");
		return null;
	}

	/** Asks the game once whether an atlas binding names a real atlas; one that does not is dropped, with a line in missing. */
	private static void checkAtlas(CustomTextureSet set, int i) {
		Identifier atlas = set.atlases[i];
		if (atlas != null && !set.views.isAtlas(atlas)) {
			set.atlases[i] = null;
			set.missing.add(set.entries.get(i).path() + ": the game has no such atlas");
		}
	}

	/** One texture of an image: a Metal texture of the image's format and type, with its texels uploaded; 0 when refused. */
	private static long upload(long ctx, TextureImage image) {
		long texture = ShaderNative.textureNew(ctx, image.format(), image.type(), image.width(), image.height(), image.depth(),
			1, USAGE_SHADER_READ, STORAGE_PRIVATE);
		if (texture == 0) {
			return 0;
		}
		if (!ShaderNative.textureUpload(ctx, texture, image.bytes(), image.bytesPerRow(), image.bytesPerImage(), 0, 0, 0, 0, 0,
				image.width(), image.height(), image.depth())) {
			MetalBridge.release(texture);
			return 0;
		}
		return texture;
	}

	/**
	 * The id of the binding a program's sampler reads, or -1 when the pack binds none to it. The id is valid for the set's
	 * life; the table's bindings are read live, so a program compiled after the set was created still resolves.
	 */
	public int id(String program, String sampler) {
		Map<String, Entry> bound = this.table.bindings().get(program);
		if (bound == null) {
			return -1;
		}
		Entry e = bound.get(sampler);
		if (e == null) {
			return -1;
		}
		Integer id = this.ids.get(e);
		return id == null ? -1 : id;
	}

	/** The binding of an id from {@link #id}, or null for -1. */
	public @Nullable Entry entry(int id) {
		return id < 0 || id >= this.entries.size() ? null : this.entries.get(id);
	}

	/**
	 * The texture of a binding id: the created texture, or the game's current atlas or lightmap view (asked for now), or 0
	 * (an id of -1, a binding the device or the game did not provide). The caller falls back to the engine's own source on 0.
	 */
	public long handle(int id) {
		if (id < 0 || id >= this.entries.size()) {
			return 0;
		}
		return switch (this.entries.get(id).source()) {
			case ATLAS -> this.atlases[id] == null ? 0L : this.views.atlas(this.atlases[id]);
			case LIGHTMAP -> this.views.lightmap();
			default -> this.handles[id];
		};
	}

	/** The texture of the {@code texture.noise} binding (the pack's noise), or 0 when the pack names none. */
	public long noise() {
		Entry e = this.table.noise();
		if (e == null) {
			return 0;
		}
		Integer id = this.ids.get(e);
		return id == null ? 0 : this.handles[id];
	}

	/** The bindings that could not be created (a resource the game lacks, a raw file that does not convert), one line each. */
	public List<String> missing() {
		return List.copyOf(this.missing);
	}

	/** Releases every texture this set created; the game's views are not released (they are the game's). */
	@Override
	public void close() {
		for (int i = 0; i < this.handles.length; i++) {
			if (this.handles[i] != 0) {
				MetalBridge.release(this.handles[i]);
				this.handles[i] = 0;
			}
		}
	}

}
