/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalHooks.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal. 2026-10-12
 * (M4): the storage seams (StorageResolver, storageUsage) added, removed again at E10 with the Vitrail integration that installed
 * them. 2026-11-17 (E9): PassRedirector.keepsMainTargetClears added; ADR 0008: Redirect.scalesFromOwn (its 7-argument constructor
 * sets it), Redirect.INHERIT_CLEAR and inheritsDepthClear.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * The backend's seams: redirecting render passes (the shader engine's redirector, plan.md E4), labelling them, and the draw
 * log's encoder events.
 */
public final class MetalHooks {
	/**
	 * Redirected attachments for a pass, and the delegate that draws into them. clears: per color attachment, a clear
	 * color for its load action or null to load it. depth 0 keeps the pass's own depth attachment; otherwise that texture
	 * replaces it (cleared to depthClear unless NaN, else loaded; {@link #INHERIT_CLEAR}: cleared or loaded as the pass's own
	 * depth attachment asks, with its clear value). A redirect with colours covers the whole width x height. With
	 * {@code scalesFromOwn}, the pass's area and the scissor rects it sets are scaled from its own attachments' size to the
	 * redirect's (ADR 0008's render scale: the main target's passes; at the same size they are the pass's own); without it
	 * they are taken as they are (a pass whose targets have nothing to do with its own, such as the shadow map's).
	 */
	public record Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear,
			PassDelegate delegate, boolean scalesFromOwn) {
		/** No depth clear (depthClear is only read when depth replaces the pass's own). */
		public static final float NO_CLEAR = Float.NaN;
		/** The replacement depth takes the pass's own depth load action and clear value (ADR 0008: the game's own-depth passes). */
		public static final float INHERIT_CLEAR = Float.NEGATIVE_INFINITY;

		/** A redirect of a main-target pass: its area and scissor scale from the pass's own size to the redirect's. */
		public Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear,
				PassDelegate delegate) {
			this(colors, clears, width, height, depth, depthClear, delegate, true);
		}

		/** The pass keeps its own attachments (colors, depth, clears); only its pipelines go through the delegate. */
		public static Redirect keep(PassDelegate delegate) {
			return new Redirect(null, null, 0, 0, 0, NO_CLEAR, delegate, false);
		}

		public boolean keepsAttachments() {
			return this.colors == null;
		}

		/** Whether the replacement depth inherits the pass's own load action and clear value. */
		public boolean inheritsDepthClear() {
			return this.depth != 0 && this.depthClear == INHERIT_CLEAR;
		}
	}

	/** Decides for each render pass whether it is taken over. */
	public interface PassRedirector {
		@Nullable Redirect redirect(RenderPassDescriptor descriptor);

		/**
		 * Whether a pass this redirector takes into other targets leaves the game's main target's deferred clears deferred
		 * (E9): only a redirector whose passes never sample the main target, and whose owner flushes or drops the main colour's
		 * clear before it reads or writes that colour itself, may answer true. Without it every deferred clear that is not the
		 * redirected pass's attachment is written first, as before.
		 */
		default boolean keepsMainTargetClears() {
			return false;
		}
	}

	static volatile @Nullable PassRedirector redirector;

	/**
	 * 2026-11-14 (E4 diagnostics, the draw log): the encoder events the backend reports while the draw log's block is open.
	 * Null otherwise, so an encoder event costs one null check. {@code mode}: 0 a new encoder, 1 continued (same
	 * attachments), 2 continued with the depth clear folded in. {@code loads} per attachment: 0 load, 1 clear, 2 don't care.
	 */
	public interface EncoderTrace {
		/** A render encoder began (or continued); {@code endedPrevious}: it ended the open encoder first. */
		void begin(String why, int mode, boolean endedPrevious, long[] colors, int[] loads, long depth, boolean clearDepth);

		/** The open render pass is split onto other attachments ({@code texture}: the one the split is for, 0 none). */
		void split(String why, long texture);

		/** The split pass's state is restored (its pipeline is set again). */
		void restore(String pipeline);
	}

	public static @Nullable EncoderTrace encoderTrace;

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
}
