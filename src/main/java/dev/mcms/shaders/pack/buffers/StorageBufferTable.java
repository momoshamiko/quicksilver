// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.buffers;

import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ShaderProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The pack's shader storage buffers (Iris's SSBO directives, shaders.properties "SSBOs"): {@code bufferObject.<index>} with
 * index 0 to 8, sized in bytes, or screen-sized ({@code <bytes> true <scaleX> <scaleY>}: bytes per pixel times the scaled
 * view size, {@code int(width * scaleX) * int(height * scaleY) * bytes}), or fixed with an initial file
 * ({@code <bytes> <filePath>}, fixed-size only). The size is not limited here: the device's largest buffer (Metal's
 * {@code maxBufferLength}, which the runtime reads) and a failed allocation refuse a buffer ({@code StorageBuffers}).
 *
 * <p>A declaration the engine cannot honour is a problem and no buffer: the program that uses it then fails to bind the
 * slot, and the load line says why.
 */
public final class StorageBufferTable {

	/** The index range the directive allows (Iris's SSBO page: unique values from 0 to 8). */
	public static final int MAX_INDEX = 8;

	/**
	 * One buffer.
	 *
	 * @param index    the binding (layout(binding = index) of the pack's buffer block)
	 * @param bytes    the fixed size, or the bytes per pixel when {@code relative}
	 * @param relative whether the size follows the view
	 * @param scaleX   the view width's multiplier (relative only)
	 * @param scaleY   the view height's multiplier (relative only)
	 * @param initial  the initial contents from the pack's file, or null (zero for a fixed buffer, see the docs: unset)
	 */
	public record Spec(int index, long bytes, boolean relative, double scaleX, double scaleY, byte @Nullable [] initial) {

		/** The size in bytes for a view of this many pixels; the fixed size when the buffer is not relative. */
		public long sizeFor(int width, int height) {
			if (!this.relative) {
				return this.bytes;
			}
			long w = (long) (width * this.scaleX), h = (long) (height * this.scaleY);
			return w * h * this.bytes;
		}
	}

	private final List<Spec> specs;
	private final List<String> problems;

	private StorageBufferTable(List<Spec> specs, List<String> problems) {
		this.specs = List.copyOf(specs);
		this.problems = List.copyOf(problems);
	}

	/** A pack with no storage buffers. */
	public static StorageBufferTable empty() {
		return new StorageBufferTable(List.of(), List.of());
	}

	/** The buffers of a pack's properties; initial files are read from {@code pack}. */
	public static StorageBufferTable of(ShaderProperties props, PackSource pack) {
		List<Spec> specs = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (ShaderProperties.BufferObject b : props.bufferObjects()) {
			String where = "bufferObject." + b.index();
			if (b.index() < 0 || b.index() > MAX_INDEX) {
				problems.add(where + ": the index must be 0 to " + MAX_INDEX);
				continue;
			}
			if (specs.stream().anyMatch(s -> s.index() == b.index())) {
				problems.add(where + ": declared twice");
				continue;
			}
			if (b.bytes() <= 0) {
				problems.add(where + ": the size must be positive");
				continue;
			}
			if (b.relative() && (b.scaleX() <= 0 || b.scaleY() <= 0)) {
				problems.add(where + ": the scales must be positive");
				continue;
			}
			byte[] initial = null;
			if (b.file().isPresent()) {
				if (b.relative()) {
					problems.add(where + ": an initial file needs a fixed size, not a screen-sized one");
					continue;
				}
				Optional<byte[]> bytes = pack.readBytes(b.file().get().startsWith("/") ? b.file().get().substring(1) : b.file().get());
				if (bytes.isEmpty()) {
					problems.add(where + "=" + b.file().get() + ": no such file in the shader pack's shaders folder");
					continue;
				}
				if (bytes.get().length > b.bytes()) {
					problems.add(where + ": the file is larger than the buffer");
					continue;
				}
				initial = bytes.get();
			}
			specs.add(new Spec(b.index(), b.bytes(), b.relative(), b.scaleX(), b.scaleY(), initial));
		}
		return new StorageBufferTable(specs, problems);
	}

	/** The declared buffers, by index order of the directives. */
	public List<Spec> specs() {
		return this.specs;
	}

	/** The spec of one index, or null when the pack declares none there. */
	public @Nullable Spec at(int index) {
		for (Spec s : this.specs) {
			if (s.index() == index) {
				return s;
			}
		}
		return null;
	}

	/** Why declarations were refused, one line each. */
	public List<String> problems() {
		return this.problems;
	}
}
