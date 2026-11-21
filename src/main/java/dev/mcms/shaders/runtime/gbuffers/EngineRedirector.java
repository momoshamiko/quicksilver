// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.mcms.metal.MetalBridge;
import dev.mcms.metal.MetalHooks;
import org.jspecify.annotations.Nullable;

/**
 * Takes the game's world passes over while a set is active (plan.md E4, "Pass takeover"; a
 * {@link MetalHooks.PassRedirector}): every game pass that draws world content into the main target's colour is
 * redirected into the pack's targets — the pass's colour attachments become the phase's layout (the union of the
 * targets its programs write, {@code colortex0} at attachment 0), loaded.
 *
 * <p>Depth: "Sky", "Solid" and "Main" use the engine's depth target ({@code Depth32Float}, cleared to 1.0 once at the
 * frame's clear hook) where the game pass used the main target's depth. "Always on top features", "Screen effects" and
 * "3d crosshair" keep their own depth ({@code Redirect.depth == 0} keeps the descriptor's): the game clears it to 0.0,
 * which the game's reversed convention serves ({@link GbufferDelegate#reversedDepth}), so gizmos never test against the
 * world, and a clear of the engine depth would destroy what {@code composite} reads. "See through features" has no depth
 * attachment at all: its pass keeps none, and its pipelines are the ones built without depth. "Outline" draws into the
 * game's outline target and stays the game's. Passes opened inside a {@link GbufferDelegate#passKind} window (E4-C's
 * hand) are redirected whatever their label, with the engine's depth: they are the engine's own.
 *
 * <p>A pass opened inside a {@link PassKind#SHADOW} window (E5, {@link GbufferDelegate#beginKind}) is never sent to the
 * main targets: it draws into the shadow targets ({@link ShadowColours}), its colour attachments are the shadow layout's
 * {@code shadowcolor} buffers (none when no shadow program writes, then a depth-only pass) and its depth is
 * {@code shadowtex0}. The redirector refuses (throws) a shadow pass that has no shadow targets rather than let it draw
 * into the main targets.
 *
 * <p>The recognized labels (javap, minecraft-merged-deobf-26.3.jar, 2026-11-14): "Sky" ({@code SkyRenderer.render}'s
 * pass), "Solid" or "Main" ({@code lambda$addMainPass$1}, classic transparency names it "Solid"), "See through
 * features", "Always on top features" ({@code LevelRenderer}'s execute lambdas), "Screen effects"
 * ({@code lambda$render3dHud$0}) and "3d crosshair" ({@code DebugCrosshairRenderer}'s render lambda). A label the
 * table does not name keeps the game's own pass (the GUI, the post chains, the outline).
 *
 * <p>The redirects are reused: one {@link MetalHooks.Redirect} per (phase, depth kind), whose colour array is the phase's
 * own array from the engine ({@link PhaseColours}); the shadow redirect is reused the same way. A pass allocates nothing.
 */
public final class EngineRedirector implements MetalHooks.PassRedirector {

	/** Supplies the redirected pass's colours: the phase's attachment layout, one handle per attachment. */
	public interface PhaseColours {
		/**
		 * Returns the handles of one attachment group of a phase, in attachment order (its length is the group's attachment
		 * count), or null when the phase's programs write nothing (the pass keeps its own colours then). The engine returns the
		 * same array for a group on every call, refilled. The redirector opens every pass on group 0 of its phase.
		 */
		long @Nullable [] colours(GbufferPhase phase, int group);

		/**
		 * Whether the set's programs serve a phase at all (plan.md E7: the DISTANT phase is served only by a set with a
		 * {@code dh_} program; without one, DH's own pass draws as before). The default serves every phase.
		 */
		default boolean serves(GbufferPhase phase) {
			return true;
		}
	}

	/**
	 * The shadow targets a shadow pass draws into (E5; {@code ShadowTargets} implements it in the engine). Every call
	 * answers the same state: the arrays are the engine's own and are refilled per call, so the redirect is reused.
	 */
	public interface ShadowColours {
		/**
		 * The shadow colour handles in the shadow layout's attachment order: exactly one per attachment, possibly none
		 * (a depth-only pass). The engine returns the same array on every call, refilled. Never null.
		 */
		long[] colours(long[] out);

		/** The handle of {@code shadowtex0}, the depth attachment of the shadow pass; 0 when the device refused the map. */
		long depth();

		/** The side of the shadow targets in texels (square). */
		int resolution();

		/**
		 * The clear colours of the frame's first shadow pass, one per colour attachment (null loads that attachment), when the
		 * pass clears its own attachments, {@code shadowtex0} included (E9: no clear pass then runs ahead of it on the GPU);
		 * null when the pass loads. Taking them disarms them: the frame's later shadow passes load.
		 */
		float @Nullable [][] takeClears();
	}

	/** The engine's depth texture handle (0 would keep every pass's own depth). */
	private final long depth;
	/** The Distant Horizons depth texture (plan.md E7): the DH terrain pass's depth; 0 when the engine has none. */
	private final long distantDepth;
	private final int width;
	private final int height;
	private final PhaseColours colours;
	private final @Nullable ShadowColours shadow;
	private final GbufferDelegate delegate;
	/** Scratch for the shadow colours; the engine's array is used when it returns one. */
	private final long[] shadowScratch = new long[PhaseLayout.MAX_ATTACHMENTS];
	/** Per phase: the redirect with the engine's depth, and the redirect that keeps the pass's own depth (built once per array). */
	private final MetalHooks.@Nullable Redirect[] engineRedirects = new MetalHooks.Redirect[GbufferPhase.values().length];
	private final MetalHooks.@Nullable Redirect[] ownRedirects = new MetalHooks.Redirect[GbufferPhase.values().length];
	/**
	 * ADR 0008: with the render scale above 1.0, the passes that keep their own depth ("Always on top features", "Screen
	 * effects", "3d crosshair") draw with the engine's render-size depth for those passes ({@link #overlayDepth(long)}),
	 * cleared or loaded as each pass asks for its own; 0 keeps the passes' own depth (the window size, as before).
	 */
	private long overlayDepth;
	private final MetalHooks.@Nullable Redirect[] overlayRedirects = new MetalHooks.Redirect[GbufferPhase.values().length];
	/**
	 * The shadow pass's redirect that loads its attachments, and the one that clears them (the frame's first pass), each
	 * rebuilt only when its colours, clears, depth or size change.
	 */
	private MetalHooks.@Nullable Redirect shadowRedirect;
	private MetalHooks.@Nullable Redirect shadowClearRedirect;
	/** The Distant Horizons terrain pass's redirects by phase (DISTANT for the opaque pass, TRANSLUCENT for the transparent one). */
	private final MetalHooks.Redirect[] distantRedirects = new MetalHooks.Redirect[GbufferPhase.values().length];
	/** Whether the Distant Horizons terrain pass starting now is the transparent one (set at its render head: {@link #distantPass}). */
	private boolean distantTransparent;
	private long mainColour;

	/**
	 * @param colours       the phase layouts (the engine's targets; handles flip, so this is read per pass)
	 * @param depth         the engine's depth texture
	 * @param distantDepth  the engine's Distant Horizons depth texture (0 when none)
	 * @param width         the targets' width in pixels
	 * @param height        the targets' height in pixels
	 * @param shadow        the shadow targets a shadow pass draws into; null while the engine has none (a shadow pass then throws)
	 * @param delegate      the delegate the redirected passes' pipelines go through
	 */
	public EngineRedirector(PhaseColours colours, long depth, long distantDepth, int width, int height,
			@Nullable ShadowColours shadow, GbufferDelegate delegate) {
		this.colours = colours;
		this.depth = depth;
		this.distantDepth = distantDepth;
		this.width = width;
		this.height = height;
		this.shadow = shadow;
		this.delegate = delegate;
	}

	@Override
	public MetalHooks.@Nullable Redirect redirect(RenderPassDescriptor descriptor) {
		MetalHooks.Redirect redirect = takeover(descriptor);
		if (redirect != null && DrawLog.on()) {
			this.delegate.notePass(descriptor.label().get(), redirect);
		}
		return redirect;
	}

	/**
	 * True (E9): a redirected pass draws into the engine's targets and never samples the game's main target, and the engine's
	 * composite hook flushes the main colour's deferred clear before it reads that colour ({@code copyWorld}) and drops it
	 * before it overwrites it (the final pass, {@code copyToMain}, the upscale).
	 */
	@Override
	public boolean keepsMainTargetClears() {
		return true;
	}

	/** The takeover decision for one pass; {@link #redirect} wraps it for the draw log. */
	private MetalHooks.@Nullable Redirect takeover(RenderPassDescriptor descriptor) {
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate == null) {
			return null;  // inert: no set is active
		}
		if (delegate.kind() == PassKind.SHADOW) {
			// A shadow window: the pass is the engine's and goes into the shadow targets, whatever its label or colour.
			this.delegate.reversedDepth(false);
			return shadowRedirect();
		}
		if (delegate.kind() != PassKind.GBUFFERS) {
			// A pass-kind window is open: this pass is the engine's own (the hand).
			this.delegate.reversedDepth(false);
			return redirectOf(GbufferPhase.TRANSLUCENT, true);
		}
		String label = descriptor.label().get();
		if (label == null) {
			return null;
		}
		if (DISTANT_TERRAIN_PASS.equals(label)) {
			// DH's own terrain pass draws into the engine's DISTANT phase: its colour is DH's, the pack's pictures take it. Without
			// a set that serves DISTANT, the pass is DH's own (its colour and its depth), as before the engine served DH.
			return distantRedirect();
		}
		GbufferPhase phase = phaseOf(label);
		if (phase == null) {
			return null;
		}
		// The world passes draw into the main target's colour; a same-labelled pass on another target is not ours.
		if (!drawsOnMainColour(descriptor)) {
			return null;
		}
		return switch (label) {
			// The main target's depth becomes the engine's.
			case "Sky", "Solid", "Main" -> {
				this.delegate.reversedDepth(false);
				yield redirectOf(phase, true);
			}
			// The passes that keep their own depth: the game's convention (cleared to 0.0). With the render scale above 1.0 the
			// engine's render-size depth for them replaces it, with each pass's own clear or load (ADR 0008).
			case "Always on top features", "Screen effects", "3d crosshair" -> {
				this.delegate.reversedDepth(true);
				yield this.overlayDepth != 0 ? overlayRedirectOf(phase) : redirectOf(phase, false);
			}
			// "See through features" has no depth attachment; the pass keeps none.
			default -> {
				this.delegate.reversedDepth(false);
				yield redirectOf(phase, false);
			}
		};
	}

	/**
	 * Which DH terrain pass the next render pass is (its render head, {@link #distantPass}): the transparent pass draws into the
	 * TRANSLUCENT phase, the opaque one into DISTANT. Both draw into the engine's DH depth, which the transparent pass tests against.
	 */
	public void distantPass(boolean transparent) {
		this.distantTransparent = transparent;
	}

	/**
	 * The redirect of Distant Horizons' terrain pass (plan.md E7): the phase's colours, loaded (the frame's clear and the sky are already
	 * there), with the engine's Distant Horizons depth (cleared to 1.0 by the frame's clear). Null when the set does not serve that phase
	 * (dh_terrain for DISTANT, dh_water for TRANSLUCENT) or the engine has no DH depth: the pass then draws as DH drew it before the engine
	 * served DH.
	 */
	MetalHooks.@Nullable Redirect distantRedirect() {
		GbufferPhase phase = this.distantTransparent ? GbufferPhase.TRANSLUCENT : GbufferPhase.DISTANT;
		if (this.distantDepth == 0 || !this.colours.serves(phase)) {
			return null;
		}
		long[] colors = this.colours.colours(phase, 0);
		if (colors == null) {
			return null;
		}
		this.delegate.reversedDepth(false);
		this.delegate.phase(phase);
		MetalHooks.Redirect redirect = this.distantRedirects[phase.ordinal()];
		if (redirect == null || redirect.colors() != colors) {
			// Not scaled from the pass's own size (ADR 0008 review): DH's own framebuffer is DH's, not the main target.
			redirect = new MetalHooks.Redirect(colors, null, this.width, this.height, this.distantDepth,
				MetalHooks.Redirect.NO_CLEAR, this.delegate, false);
			this.distantRedirects[phase.ordinal()] = redirect;
		}
		return redirect;
	}

	/** The label of Distant Horizons' terrain pass (its {@code getRenderPassName}, javap 2026-11-15). */
	static final String DISTANT_TERRAIN_PASS = "distantHorizons:TerrainRenderer";

	/** The phase a label's pass draws in; null when the label names no pass the engine takes over. */
	private static @Nullable GbufferPhase phaseOf(String label) {
		return switch (label) {
			case "Sky" -> GbufferPhase.SKY;
			case "Solid", "Main" -> GbufferPhase.OPAQUE;
			case "See through features", "Always on top features", "Screen effects", "3d crosshair" ->
				GbufferPhase.TRANSLUCENT;
			default -> null;
		};
	}

	private boolean drawsOnMainColour(RenderPassDescriptor descriptor) {
		if (descriptor.colorAttachments().isEmpty()) {
			return false;
		}
		var attachment = descriptor.colorAttachments().get(0);
		return attachment != null && MetalBridge.viewHandle(attachment.textureView()) == this.mainColour;
	}

	/** The main target's colour view handle, read once at install (the passes are matched against it). */
	public void mainColour(long viewHandle) {
		this.mainColour = viewHandle;
	}

	/** ADR 0008: the render-size depth the own-depth passes take while the render scale is above 1.0; 0 keeps their own. */
	public void overlayDepth(long handle) {
		this.overlayDepth = handle;
	}

	/** The reused redirect of a phase with the engine's render-size depth for the own-depth passes, inheriting their clears. */
	private MetalHooks.@Nullable Redirect overlayRedirectOf(GbufferPhase phase) {
		long[] colors = this.colours.colours(phase, 0);
		if (colors == null) {
			return null;
		}
		int index = phase.ordinal();
		MetalHooks.Redirect redirect = this.overlayRedirects[index];
		if (redirect == null || redirect.colors() != colors || redirect.depth() != this.overlayDepth) {
			redirect = new MetalHooks.Redirect(colors, null, this.width, this.height, this.overlayDepth,
				MetalHooks.Redirect.INHERIT_CLEAR, this.delegate);
			this.overlayRedirects[index] = redirect;
		}
		this.delegate.phase(phase);
		return redirect;
	}

	/**
	 * The redirect of a shadow pass: the shadow colours and {@code shadowtex0} at the shadow resolution. The frame's first
	 * pass clears them in its load actions when the targets armed it ({@link ShadowColours#takeClears}: {@code shadowtex0}
	 * to 1.0, each colour attachment the pack clears to its clear colour); every other pass loads. Never the main targets.
	 * Package-private so the tests can check the targets without a render pass descriptor.
	 *
	 * @throws IllegalStateException the engine has no shadow targets, or the depth map was refused: the pass is refused
	 *                               rather than drawn into the main targets
	 */
	MetalHooks.Redirect shadowRedirect() {
		ShadowColours targets = this.shadow;
		if (targets == null) {
			throw new IllegalStateException("a shadow pass opened without shadow targets: refusing to draw it into the"
				+ " main targets");
		}
		long depthHandle = targets.depth();
		if (depthHandle == 0) {
			throw new IllegalStateException("the shadow depth map (shadowtex0) is not created: refusing to draw the"
				+ " shadow pass into the main targets");
		}
		long[] colors = targets.colours(this.shadowScratch);
		int size = targets.resolution();
		float[][] clears = targets.takeClears();
		MetalHooks.Redirect redirect = clears != null ? this.shadowClearRedirect : this.shadowRedirect;
		if (redirect == null || redirect.colors() != colors || redirect.clears() != clears || redirect.depth() != depthHandle
				|| redirect.width() != size) {
			// The shadow pass opens on the main target, but its targets are the square map: nothing scales from its own size.
			redirect = new MetalHooks.Redirect(colors, clears, size, size, depthHandle,
				clears != null ? 1f : MetalHooks.Redirect.NO_CLEAR, this.delegate, false);
			if (clears != null) {
				this.shadowClearRedirect = redirect;
			} else {
				this.shadowRedirect = redirect;
			}
		}
		return redirect;
	}

	/** The reused redirect of a phase with the engine's depth ({@code engineDepth}) or with the pass's own. */
	private MetalHooks.@Nullable Redirect redirectOf(GbufferPhase phase, boolean engineDepth) {
		long[] colors = this.colours.colours(phase, 0);
		if (colors == null) {
			return null;
		}
		MetalHooks.Redirect[] cache = engineDepth ? this.engineRedirects : this.ownRedirects;
		int index = phase.ordinal();
		MetalHooks.Redirect redirect = cache[index];
		if (redirect == null || redirect.colors() != colors) {
			// clears null: every attachment loads (the clear hook cleared the pack's targets once this frame).
			redirect = new MetalHooks.Redirect(colors, null, this.width, this.height, engineDepth ? this.depth : 0,
				MetalHooks.Redirect.NO_CLEAR, this.delegate);
			cache[index] = redirect;
		}
		this.delegate.phase(phase);
		return redirect;
	}
}
