/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalHooks.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal. 2026-10-12
 * (M4): the storage seams (StorageResolver, storageUsage) added.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The backend's seams: redirecting and labelling render passes (inert: nothing in this build installs them), and since M4 the storage
 * seams a shaderpack engine installs (Vitrail's, at device creation: mixin.VitrailDeviceReportMixin).
 */
public final class MetalHooks {
	/**
	 * Redirected attachments for a pass, and the delegate that draws into them. clears: per color attachment, a clear
	 * color for its load action or null to load it. depth 0 keeps the pass's own depth attachment; otherwise that texture
	 * replaces it (cleared to depthClear unless NaN, else loaded) and the pass covers the whole width x height.
	 */
	public record Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear, PassDelegate delegate) {
		/** No depth clear (depthClear is only read when depth replaces the pass's own). */
		public static final float NO_CLEAR = Float.NaN;

		/** The pass keeps its own attachments (colors, depth, clears); only its pipelines go through the delegate. */
		public static Redirect keep(PassDelegate delegate) {
			return new Redirect(null, null, 0, 0, 0, NO_CLEAR, delegate);
		}

		public boolean keepsAttachments() {
			return this.colors == null;
		}
	}

	/** Decides for each render pass whether it is taken over. */
	public interface PassRedirector {
		@Nullable Redirect redirect(RenderPassDescriptor descriptor);
	}

	static volatile @Nullable PassRedirector redirector;

	private MetalHooks() {
	}

	/** Names a native render encoder (for GPU traces). */
	public interface Labeler {
		void label(long enc, String name);
	}

	/** When set, every render pass's encoder gets its descriptor's label. */
	static volatile @Nullable Labeler labeler;

	public static void setLabeler(@Nullable Labeler l) {
		labeler = l;
	}

	public static void setRedirector(@Nullable PassRedirector r) {
		redirector = r;
	}

	/**
	 * M4: a shaderpack engine's storage images and buffers by uniform name. The frontend binds a placeholder of the declared kind under
	 * such a name; MetalRenderPass binds what this answers instead. 0: not a storage name, keep what the frontend bound.
	 */
	public interface StorageResolver {
		long image(String name);

		long buffer(String name);
	}

	static volatile @Nullable StorageResolver storage;

	/** M4: when it answers true, the texture being created gets shader-write usage (a pack target a compute writes as colorimgN). */
	static volatile @Nullable BooleanSupplier storageUsage;

	public static void setStorage(@Nullable StorageResolver resolver, @Nullable BooleanSupplier usage) {
		storage = resolver;
		storageUsage = usage;
	}
}
