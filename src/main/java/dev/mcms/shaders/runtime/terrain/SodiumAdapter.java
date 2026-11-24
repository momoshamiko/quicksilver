// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The per-release adapter for the two Sodium members whose signatures differ between the releases ADR 0007 allows
 * (ADR 0007 decision 2): {@code UniformBufferManager.update} is {@code (ChunkRenderMatrices, FogParameters)} on
 * 0.9.3-beta.1 and gains a {@code CameraTransform} on 0.9.3-beta.5, and {@code RemovableMultiForest.traverse} gains a
 * boolean (the E5 shadow list). A class compiled against one release links the other release's signature at the first
 * call ({@code NoSuchMethodError}), so the adapter looks the member up with method handles from the live Sodium
 * classes — no engine class names a differing signature — and the engine picks the shape from Sodium's mod version.
 *
 * <p>{@link #allowedRelease} is the gate the mixin plugin asks (the same allow-list): the build part after {@code +}
 * is stripped from Sodium's version word ({@code 0.9.3-beta.1+mc26.3} style), and any release not on the list keeps
 * the engine's Sodium mixins off with one ERROR naming the release, so the game runs as without a pack.
 */
public final class SodiumAdapter {

	/** The releases the engine's Sodium members are checked against (ADR 0007, ADR 0009; javap on every allowed jar). */
	public static final java.util.List<String> ALLOWED_RELEASES = java.util.List.of("0.9.3-beta.1", "0.9.3-beta.5", "0.9.3");

	/** Public lookup: the members looked up are public, and a cross-loader lookup (the beta.1 jar test)
	 * resolves their parameter types in the member's own loader, not ours. */
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

	private @Nullable MethodHandle update;
	private @Nullable MethodHandle traverse;
	private @Nullable String release;

	/** Sodium's release word of the installed jar with the build part after {@code +} stripped, or null without Sodium. */
	public static @Nullable String release() {
		return FabricLoader.getInstance().getModContainer("sodium")
			.map(c -> c.getMetadata().getVersion().getFriendlyString().split("\\+", 2)[0]).orElse(null);
	}

	/** Whether the installed Sodium release is on the allow-list (null without Sodium: not allowed). */
	public static boolean allowedRelease(@Nullable String version) {
		return version != null && ALLOWED_RELEASES.contains(version.split("\\+", 2)[0]);
	}

	/** Whether this allowed Sodium release uses the beta.5+/0.9.3 signatures ({@code CameraTransform} and shadow flag). */
	public static boolean usesCameraOffsetShape(@Nullable String version) {
		if (version == null) {
			return false;
		}
		String base = version.split("\\+", 2)[0];
		return "0.9.3-beta.5".equals(base) || "0.9.3".equals(base);
	}

	/**
	 * Whether this Sodium release needs Quicksilver's arena debug-texture leak mixins: the 26.3 builds checked with javap, 0.9.2
	 * and every 0.9.3 prerelease. Their {@code BufferArena} constructor registers a debug texture that nothing releases; stable
	 * 0.9.3 closes it in {@code BufferArena.close} (Sodium commit 1d8b33dc, ADR 0009), and 0.9.3 and 0.9.4-beta.1 share that fixed
	 * code, so they get no workaround. Evidence: results/2026-11-23-arena-bytecode/README.md. The build part after {@code +} is
	 * ignored; null (no Sodium) gets false.
	 */
	public static boolean needsArenaLeakWorkaround(@Nullable String version) {
		if (version == null) {
			return false;
		}
		return verifiedLeakRelease(version.split("\\+", 2)[0]);
	}

	/**
	 * Whether a release word (build part stripped) is a build the arena bytecode was checked on: exactly 0.9.2, or a 0.9.3
	 * prerelease. Every other word gets false, including 0.9.1, 0.8.x, 0.9.2 prereleases, stable 0.9.3 and 0.9.4-beta.1.
	 */
	static boolean verifiedLeakRelease(String base) {
		return "0.9.2".equals(base) || base.startsWith("0.9.3-");
	}

	/**
	 * Binds the adapter at engine start: looks {@code update} and {@code traverse} up on the live classes for the
	 * installed release. Returns false when a member the release needs is missing, which fails the set's load.
	 */
	public boolean bind() {
		String version = release();
		if (!allowedRelease(version)) {
			return false;
		}
		this.release = version;
		boolean cameraOffsetShape = usesCameraOffsetShape(version);
		try {
			ClassLoader loader = SodiumAdapter.class.getClassLoader();
			Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager", false, loader);
			Class<?> matrices = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices", false, loader);
			Class<?> fog = Class.forName("net.caffeinemc.mods.sodium.client.util.FogParameters", false, loader);
			MethodType updateType = MethodType.methodType(void.class, matrices, fog);
			if (cameraOffsetShape) {
				Class<?> camera = Class.forName("net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform", false, loader);
				updateType = updateType.appendParameterTypes(camera);
			}
			this.update = LOOKUP.findVirtual(manager, "update", updateType);
			Class<?> forest = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest", false, loader);
			Class<?> visitor = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor", false, loader);
			Class<?> viewport = Class.forName("net.caffeinemc.mods.sodium.client.render.viewport.Viewport", false, loader);
			MethodType traverseType = MethodType.methodType(void.class, visitor, viewport, float.class);
			if (cameraOffsetShape) {
				traverseType = traverseType.appendParameterTypes(boolean.class);
			}
			this.traverse = LOOKUP.findVirtual(forest, "traverse", traverseType);
			return true;
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
			this.update = null;
			this.traverse = null;
			return false;
		}
	}

	/** The release this adapter bound, or null when unbound. */
	public @Nullable String releaseName() {
		return this.release;
	}

	/**
	 * Calls {@code UniformBufferManager.update} for the bound release. The camera transform is the beta.5+/0.9.3 shape's
	 * third argument and is ignored on beta.1; null is allowed when the caller has none.
	 */
	public void updateGlobals(Object manager, Object matrices, Object fog, @Nullable Object cameraTransform) {
		MethodHandle h = this.update;
		if (h == null) {
			throw new IllegalStateException("the Sodium adapter is not bound");
		}
		try {
			if (usesCameraOffsetShape(this.release)) {
				h.invoke(manager, matrices, fog, cameraTransform);
			} else {
				h.invoke(manager, matrices, fog);
			}
		} catch (Throwable t) {
			throw new IllegalStateException("u_Globals update failed on Sodium " + this.release, t);
		}
	}

	/**
	 * Calls {@code RemovableMultiForest.traverse} for the bound release (the E5 shadow list). The boolean is the
	 * beta.5+/0.9.3 shape's last argument and is ignored on beta.1.
	 */
	public void traverseForest(Object forest, Object visitor, Object viewport, float distance, boolean flag) {
		MethodHandle h = this.traverse;
		if (h == null) {
			throw new IllegalStateException("the Sodium adapter is not bound");
		}
		try {
			if (usesCameraOffsetShape(this.release)) {
				h.invoke(forest, visitor, viewport, distance, flag);
			} else {
				h.invoke(forest, visitor, viewport, distance);
			}
		} catch (Throwable t) {
			throw new IllegalStateException("the section traverse failed on Sodium " + this.release, t);
		}
	}

	/** The member lookup's outcome for one release word: the first failure, or {@code present} when all resolved. */
	public record Lookup(@Nullable String failure) {

		/** Whether every member the release needs resolved. */
		public boolean present() {
			return this.failure == null;
		}
	}

	/** Looks the two members up for a given release word and loader shape (the release check, testable per jar). */
	public static Lookup members(String version, ClassLoader loader) {
		boolean cameraOffsetShape = usesCameraOffsetShape(version);
		try {
			Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager", false, loader);
			Class<?> matrices = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices", false, loader);
			Class<?> fog = Class.forName("net.caffeinemc.mods.sodium.client.util.FogParameters", false, loader);
			MethodType updateType = MethodType.methodType(void.class, matrices, fog);
			if (cameraOffsetShape) {
				updateType = updateType.appendParameterTypes(
					Class.forName("net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform", false, loader));
			}
			LOOKUP.findVirtual(manager, "update", updateType);
			Class<?> forest = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest", false, loader);
			Class<?> visitor = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor", false, loader);
			Class<?> viewport = Class.forName("net.caffeinemc.mods.sodium.client.render.viewport.Viewport", false, loader);
			MethodType traverseType = MethodType.methodType(void.class, visitor, viewport, float.class);
			if (cameraOffsetShape) {
				traverseType = traverseType.appendParameterTypes(boolean.class);
			}
			LOOKUP.findVirtual(forest, "traverse", traverseType);
			return new Lookup(null);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
			return new Lookup(e.toString());
		}
	}
}
