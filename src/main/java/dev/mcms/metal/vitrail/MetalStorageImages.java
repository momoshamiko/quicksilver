/*
 * Part of MC Metal Shaders (mcms-metal), 2026-10-12 (M4). Adapted from Vitrail 0.12.0-beta,
 * common/src/main/java/dev/vitrail/render/storage/StorageImages.java (ensure, allocate, lookup, rebind, clearMarked, reanchor, move,
 * clearBornImages, Allocated.create, typedView, destroy), Copyright the Vitrail authors, licensed LGPL-3.0-only. This file is therefore
 * LGPL-3.0-only (see mod/mcms-metal/README.md, licences).
 */
package dev.mcms.metal.vitrail;

import com.mojang.renderpearl.api.GpuFormat;
import dev.mcms.metal.MetalStorage;
import dev.vitrail.Vitrail;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.texture.CustomImageView;
import dev.vitrail.render.storage.StorageImages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * One Vitrail StorageImages set (a pack's image. directives) held on Metal: the images, the scratch twins of the volumes that follow the
 * camera, their clears and moves, and the names a draw or a dispatch binds them under. Vitrail's StorageImages keeps the policy's
 * state it owns (the declaration, refusedForGood, the current set); mixin.VitrailStorageImagesMixin hands the rest to this class.
 * Every image is a 1D, 2D or 3D MTLTexture with shader read and write, created and cleared on this backend (MetalStorage).
 */
public final class MetalStorageImages {
	/** StorageImages.CLEAR_AT_BIRTH. */
	private static final boolean CLEAR_AT_BIRTH = Boolean.parseBoolean(System.getProperty("vitrail.clearStorageAtBirth", "true"));
	/** StorageImages.CLEAR_AT_BIRTH_BUDGET: kept so the same volumes are emptied at birth as on Vulkan. */
	private static final long CLEAR_AT_BIRTH_BUDGET = 64L * 1024L * 1024L;

	private final ImageInformation.Reading declared;
	private final List<Image> allocated = new ArrayList<>();
	private volatile Map<String, StorageImages.Bound> bindings = Map.of();
	private volatile Map<String, Image> imagesByName = Map.of();
	private final Map<String, StorageImages.Bound> typedBindings = new ConcurrentHashMap<>();
	/** Names asked for that are no typed view of an image here: the bind of every texture of every pass asks. */
	private final Set<String> untyped = ConcurrentHashMap.newKeySet();
	private int lastWidth;
	private int lastHeight;

	public MetalStorageImages(ImageInformation.Reading declared) {
		this.declared = declared;
	}

	/**
	 * StorageImages.ensure from its device check on: every absolute image once, the relative ones at each new screen size, all or
	 * nothing. refusedForGood runs when an image no screen size can change was refused.
	 */
	public void ensure(int screenWidth, int screenHeight, Predicate<ImageInformation> movable, Runnable refusedForGood) {
		if (this.declared.images().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		try {
			allocate(first, resized, screenWidth, screenHeight, movable, refusedForGood);
		} catch (RuntimeException e) {
			this.allocated.forEach(Image::release);
			this.allocated.clear();
			rebind();
			throw e;
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		rebind();
		clearBornImages();
	}

	private void allocate(boolean first, boolean resized, int screenWidth, int screenHeight, Predicate<ImageInformation> movable,
			Runnable refusedForGood) {
		if (first) {
			for (ImageInformation image : this.declared.images()) {
				if (image.relative()) {
					continue;
				}

				boolean moves = movable.test(image);
				try {
					this.allocated.add(Image.create(image, image.width(), image.height(), Math.max(image.depth(), 1), moves));
					Vitrail.logger().info("storage image {}{}", image.describe(), moves
							? ", moved onto each frame's camera block for " + scratchCost(image) + " more"
							: image.clear() && image.depth() > 1 ? ", left on the frame that filled it" : "");
				} catch (RuntimeException e) {
					refusedForGood.run();
					throw refused(image, e);
				}
			}
		}

		if (first || resized) {
			List<Image> kept = new ArrayList<>();
			for (Image image : this.allocated) {
				if (image.relative) {
					image.release();
				} else {
					kept.add(image);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (ImageInformation image : this.declared.images()) {
				if (!image.relative()) {
					continue;
				}

				int width = Math.max(1, (int) (screenWidth * image.relativeWidth()));
				int height = Math.max(1, (int) (screenHeight * image.relativeHeight()));
				try {
					this.allocated.add(Image.create(image, width, height, 1, false));
					Vitrail.logger().info("storage image {} at {}x{}", image.describe(), width, height);
				} catch (RuntimeException e) {
					throw refused(image, e);
				}
			}
		}
	}

	private static IllegalStateException refused(ImageInformation image, RuntimeException cause) {
		return new IllegalStateException("storage image " + image.describe()
				+ " could not be allocated, and a program declaring it cannot be drawn without it", cause);
	}

	private static String scratchCost(ImageInformation image) {
		long texels = (long) Math.max(image.width(), 1) * Math.max(image.height(), 1) * Math.max(image.depth(), 1);
		long bytes = texels * image.internalFormat().used().bytesPerPixel();
		return bytes >= 1024L * 1024L ? bytes / (1024L * 1024L) + " MiB" : bytes / 1024L + " KiB";
	}

	/** StorageImages.lookup: the image (or its typed view) bound under a name, with the MTLTexture as the view. */
	public StorageImages.@Nullable Bound lookup(String name) {
		if (this.allocated.isEmpty()) return null;
		StorageImages.Bound ordinary = this.bindings.get(name);
		if (ordinary != null) return ordinary;
		StorageImages.Bound cached = this.typedBindings.get(name);
		if (cached != null) return cached;
		if (this.untyped.contains(name)) return null;
		var request = CustomImageView.parse(name);
		Image image = request.isEmpty() ? null : this.imagesByName.get(request.get().original());
		if (image == null) {
			this.untyped.add(name);
			return null;
		}
		return this.typedBindings.computeIfAbsent(name, k -> new StorageImages.Bound(image.typedView(request.get().format()),
				image.declared.name().equals(request.get().original()), request.get().format().integer()));
	}

	private void rebind() {
		Map<String, StorageImages.Bound> bound = new HashMap<>();
		Map<String, Image> named = new HashMap<>();
		for (Image image : this.allocated) {
			boolean integer = image.declared.internalFormat().used().integer();
			bound.putIfAbsent(image.declared.name(), new StorageImages.Bound(image.texture, true, integer));
			image.declared.sampler().ifPresent(sampler -> bound.putIfAbsent(sampler, new StorageImages.Bound(image.texture, false, integer)));
			named.putIfAbsent(image.declared.name(), image);
			image.declared.sampler().ifPresent(sampler -> named.putIfAbsent(sampler, image));
		}

		this.bindings = Map.copyOf(bound);
		this.imagesByName = Map.copyOf(named);
		this.typedBindings.clear();
		this.untyped.clear();
	}

	/** StorageImages.clearMarked: empties every image the pack asked to clear, at the head of the shadow stage. */
	public void clearMarked() {
		MetalStorage.endPass();
		for (Image image : this.allocated) {
			if (image.declared.clear()) {
				image.clear();
			}
		}
	}

	/** StorageImages.reanchor: moves the volumes that follow the camera by the whole blocks it crossed. */
	public void reanchor(int dx, int dy, int dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return;
		}

		MetalStorage.endPass();
		for (Image image : this.allocated) {
			if (image.scratch == 0L) {
				continue;
			}

			// Past the extent nothing of the volume survives the move: emptied, not moved.
			if (Math.abs(dx) >= image.width || Math.abs(dy) >= image.height || Math.abs(dz) >= image.depth) {
				image.clear();
				continue;
			}

			move(image, dx, dy, dz);
		}
	}

	/**
	 * StorageImages.move: two copies through the image's scratch, both carrying the region the second reads, because Metal, like Vulkan,
	 * leaves a copy whose source and destination overlap undefined. Each copy is its own blit; Metal orders them on the scratch.
	 */
	private static void move(Image image, int dx, int dy, int dz) {
		int fromX = Math.max(dx, 0);
		int fromY = Math.max(dy, 0);
		int fromZ = Math.max(dz, 0);
		int spanX = image.width - Math.abs(dx);
		int spanY = image.height - Math.abs(dy);
		int spanZ = image.depth - Math.abs(dz);
		MetalStorage.copyBox(image.texture, image.scratch, fromX, fromY, fromZ, fromX, fromY, fromZ, spanX, spanY, spanZ);
		MetalStorage.endEncoders();
		MetalStorage.copyBox(image.scratch, image.texture, fromX, fromY, fromZ, Math.max(-dx, 0), Math.max(-dy, 0), Math.max(-dz, 0),
				spanX, spanY, spanZ);
	}

	/** StorageImages.layoutIfNeeded and clearBornImages: empties the new volumes nothing else will empty, under Vitrail's budget. */
	private void clearBornImages() {
		List<Image> born = new ArrayList<>();
		for (Image image : this.allocated) {
			if (!image.born) {
				image.born = true;
				born.add(image);
			}
		}

		if (born.isEmpty()) {
			return;
		}

		List<Image> emptying = new ArrayList<>();
		long spent = 0L;
		for (Image image : born) {
			if (image.declared.clear()) {
				continue;
			}

			if (!CLEAR_AT_BIRTH) {
				Vitrail.logger().info("Storage volume {} is left as the allocator handed it back, property=vitrail.clearStorageAtBirth",
						image.declared.name());
				continue;
			}

			long bytes = (long) image.width * image.height * image.depth * image.declared.internalFormat().used().bytesPerPixel();
			if (spent + bytes > CLEAR_AT_BIRTH_BUDGET) {
				Vitrail.logger().info("Storage volume {} is {} MiB and this pass has {} MiB of its {} MiB left, so it is left as the "
						+ "allocator handed it back and a pack reading it before writing it whole reads that", image.declared.name(),
						bytes / (1024L * 1024L), (CLEAR_AT_BIRTH_BUDGET - spent) / (1024L * 1024L), CLEAR_AT_BIRTH_BUDGET / (1024L * 1024L));
				continue;
			}

			spent += bytes;
			emptying.add(image);
		}

		if (emptying.isEmpty()) {
			return;
		}

		MetalStorage.endPass();
		for (Image image : emptying) {
			image.clear();
		}

		Vitrail.logger().info("{} storage volume(s) emptied as they were created, property=vitrail.clearStorageAtBirth", emptying.size());
	}

	public int count() {
		return this.allocated.size();
	}

	/** StorageImages.close's half on the device: every image, scratch and view released after the frames still using them. */
	public void close() {
		this.allocated.forEach(Image::release);
		this.allocated.clear();
		this.bindings = Map.of();
		this.imagesByName = Map.of();
		this.typedBindings.clear();
		this.untyped.clear();
		this.lastWidth = 0;
		this.lastHeight = 0;
	}

	/** StorageImages.unusableViews on Metal: the names asking for a view in a format this device cannot both sample and store. */
	public static List<String> unusableViews(Collection<String> names) {
		List<String> unusable = new ArrayList<>();
		for (String name : names) {
			CustomImageView.parse(name).ifPresent(view -> dev.vitrail.pack.texture.CustomImages.image(view.original())
					.map(image -> image.internalFormat().used())
					.filter(base -> !viewable(base) || !viewable(view.format()))
					.ifPresent(base -> unusable.add(name)));
		}
		return unusable;
	}

	/**
	 * StorageImages.supportsView on Metal: sampled and stored (Apple GPU families 7 and up give every colour format this backend maps
	 * both); a 32-bit integer view also needs atomics, which those GPUs have on R32Uint and R32Sint textures.
	 */
	private static boolean viewable(TargetFormat format) {
		return MetalStorage.writable(gpuFormat(format));
	}

	/** Vitrail's TargetFormat names its GpuFormat one for one (GpuFormats.of). */
	static GpuFormat gpuFormat(TargetFormat format) {
		return GpuFormat.valueOf(format.name());
	}

	private static int kind(TargetFormat format) {
		if (!format.integer()) return MetalStorage.KIND_FLOAT;
		return format.name().endsWith("_SINT") ? MetalStorage.KIND_SINT : MetalStorage.KIND_UINT;
	}

	private static final class Image {
		final ImageInformation declared;
		final boolean relative;
		final int width;
		final int height;
		final int depth;
		long texture;
		/** The second image of the same shape a volume that follows the camera is moved through; 0 for every other. */
		long scratch;
		final List<TargetFormat> allowedViews;
		final Map<TargetFormat, Long> views = new HashMap<>();
		/** Whether clearBornImages has seen it (Vitrail's laidOut). */
		boolean born;

		private Image(ImageInformation declared, int width, int height, int depth, long texture, List<TargetFormat> allowedViews) {
			this.declared = declared;
			this.relative = declared.relative();
			this.width = width;
			this.height = height;
			this.depth = depth;
			this.texture = texture;
			this.allowedViews = allowedViews;
		}

		static Image create(ImageInformation declared, int width, int height, int depth, boolean movable) {
			TargetFormat base = declared.internalFormat().used();
			List<TargetFormat> compatible = Arrays.stream(TargetFormat.values())
					.filter(format -> CustomImageView.compatible(base, format))
					.filter(MetalStorageImages::viewable).toList();
			if (!compatible.contains(base)) {
				Vitrail.logger().warn("Storage image {} is declared {}, which this device does not both sample and store, so it takes no "
						+ "view in another format", declared.name(), base);
				compatible = List.of(base);
			}

			int shape = switch (declared.shape()) {
				case TEXTURE_1D -> MetalStorage.SHAPE_1D;
				case TEXTURE_3D -> MetalStorage.SHAPE_3D;
				case TEXTURE_2D, TEXTURE_RECTANGLE -> MetalStorage.SHAPE_2D;
			};
			int w = Math.max(width, 1), h = Math.max(height, 1), d = Math.max(depth, 1);
			long texture = MetalStorage.newImage(gpuFormat(base), shape, w, h, d, compatible.size() > 1);
			Image image = new Image(declared, w, h, d, texture, compatible);
			// A scratch that cannot be had is not the image's failure: the volume keeps a frame of lag instead.
			if (movable) {
				try {
					image.scratch = MetalStorage.newImage(gpuFormat(base), shape, w, h, d, false);
				} catch (RuntimeException e) {
					Vitrail.logger().warn("storage image {} keeps a frame of lag, its scratch could not be allocated: {}", declared.name(),
							e.toString());
				}
			}
			return image;
		}

		void clear() {
			MetalStorage.clearImage(this.texture, kind(this.declared.internalFormat().used()));
		}

		synchronized long typedView(TargetFormat format) {
			if (format == this.declared.internalFormat().used()) return this.texture;
			Long cached = this.views.get(format);
			if (cached != null) return cached;
			if (!this.allowedViews.contains(format) || this.texture == 0L) {
				Vitrail.logger().warn("Custom image {} has no {} view here, so its own is bound", this.declared.name(), format);
				return this.texture;
			}
			long created = MetalStorage.imageView(this.texture, gpuFormat(format));
			this.views.put(format, created);
			Vitrail.logger().info("Custom image {} uses a {} view of {} memory", this.declared.name(), format,
					this.declared.internalFormat().used());
			return created;
		}

		/** StorageImages.Allocated.destroy: every handle zeroed first, then released after the frames still using it. */
		synchronized void release() {
			long texture = this.texture, scratch = this.scratch;
			java.util.List<Long> views = java.util.List.copyOf(this.views.values());
			this.texture = 0L;
			this.scratch = 0L;
			this.views.clear();
			for (long view : views) MetalStorage.releaseAfterGpu(view);
			if (texture != 0L) MetalStorage.releaseAfterGpu(texture);
			if (scratch != 0L) MetalStorage.releaseAfterGpu(scratch);
		}
	}
}
