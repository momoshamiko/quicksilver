/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalSurface.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; properties -Dmcopt.metal.* renamed to -Dmcms.metal.* (paceAdapt now
 * defaults to on); cut the mcopt.rec recorder hook; with VSync on the frame's work is committed before nextDrawable (-Dmcms.metal.earlyCommit).
 * 2026-10-13 (M5): with VSync on, the render thread waits for the display's next refresh before nextDrawable (-Dmcms.metal.vsyncPace).
 * After M5: the surface names its view to the native side, whose display link follows the display of the view's window (RIL-476);
 * -Dmcms.metal.vsyncLowLatency (experiment) moves the VSync-on wait into present(). 2026-10-16 (RIL-481): -Dmcms.metal.colorspace
 * tags the layer with a colour space, and the surface logs what the layer, its window and the window's screen use. 2026-10-17
 * (RIL-481): without that property, the layer is tagged Display P3 when the mod mac-colorspace-rollback is loaded. 2026-10-18
 * (ADR 0002, the settings page): the colour space is a field that the page's Colors row re-applies to the live layer
 * (retagCurrent), the page's Metal HUD row shows or hides Apple's HUD on it (hudCurrent), and the surface keeps a handle to itself.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet. With VSync on, the
 * frame's work goes to the GPU before that wait (see EARLY_COMMIT).
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dmcms.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcms.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dmcms.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcms.metal.paceMarginMs", "2")) / 1000;
	/** -Dmcms.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("mcms.metal.drawables", 0);
	/**
	 * -Dmcms.metal.paceSync=true: the paced presents (vsync off) go out with display sync on, so each latches at a vblank. On
	 * the laptop's 120 Hz 5K display at ~1800 fps, paced presents without display sync reach the screen in pairs ~1.9 ms apart
	 * every 16.7 ms (a 60 Hz cadence); with vsync the grid is a clean 8.33 ms.
	 */
	private static final boolean PACE_SYNC = Boolean.getBoolean("mcms.metal.paceSync");
	/**
	 * -Dmcms.metal.paceAdapt (default on): the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 */
	static final boolean PACE_ADAPT = Boolean.parseBoolean(System.getProperty("mcms.metal.paceAdapt", "true"));
	/**
	 * -Dmcms.metal.earlyCommit (default on): with VSync on, commit the frame's work before the render thread waits in nextDrawable.
	 * That wait lasts until the display frees a drawable, just after a refresh; a frame committed only then had less than one
	 * refresh for its GPU work, and in fullscreen at 3456x2234 8-26% of frames were shown for two refreshes
	 * (results/2026-10-11-m0c-speed, results/2026-10-11-m0c-vsync-diag).
	 */
	static final boolean EARLY_COMMIT = Boolean.parseBoolean(System.getProperty("mcms.metal.earlyCommit", "true"));
	/**
	 * -Dmcms.metal.vsyncPace (default on, M5): with VSync on, the render thread waits for the next refresh on the display's grid, plus
	 * VSYNC_PACE_OFFSET_S, before it fetches the drawable (mc_vsync_wait), after the early commit, so each frame takes one refresh.
	 * Without it the wait in nextDrawable sets the rhythm, and the layer hands drawables back unevenly: in fullscreen the 1% low was
	 * 63.9 and 65.6 against 102.4-113.2 with it (results/2026-10-13-m5-vsync-screen, results/2026-10-11-m0c-vsync-log).
	 */
	static final boolean VSYNC_PACE = Boolean.parseBoolean(System.getProperty("mcms.metal.vsyncPace", "true"));
	/**
	 * -Dmcms.metal.vsyncPaceOffsetMs (default 2): how long after the refresh the waiting render thread wakes. The drawable it needs
	 * often comes back only after the refresh; at 0 ms frames still waited for it (p99 1.3 ms in fullscreen), at 2 ms rarely.
	 */
	private static final double VSYNC_PACE_OFFSET_S = Double.parseDouble(System.getProperty("mcms.metal.vsyncPaceOffsetMs", "2")) / 1000;
	/**
	 * -Dmcms.metal.vsyncLowLatency (experiment after M5, off by default; {@code true} for a 1.5 ms margin, or the margin in ms): with
	 * VSync on, the VSync-on wait moves from before the drawable to after the frame's present is committed (present(), before the
	 * game polls input for the next frame), and ends at the next refresh minus a lead: the second longest wake-to-GPU-done time of
	 * the last 32 frames plus the margin (mc_vsync_wait_next). Negative: off.
	 */
	private static final double VSYNC_LOW_LATENCY_MARGIN_S = lowLatencyMargin(System.getProperty("mcms.metal.vsyncLowLatency"));
	/**
	 * The colour space the layer tags its frames with (RIL-481; values in {@link #colorspaceName()}). none: no tag; the window's
	 * sRGB then decides, so the frames are matched from sRGB, as on OpenGL and Vulkan (results/2026-10-17-colors); srgb: matched
	 * from sRGB to the colour space of the layer's context (typically the display's), what MoltenVK asks for; p3: matched from
	 * Display P3, so the frames' values show as Display P3. Bits 16-17 of mc_layer_configure's flags; the settings page changes it
	 * while the game runs (retag).
	 */
	private int colorspace = colorspace(colorspaceName());
	/** ADR 0002: the surface the settings page re-tags and shows the HUD on; null when there is none (OpenGL, or after close). */
	private static volatile @Nullable MetalSurface current;
	/** The colour report last logged: a configure that changes none of it logs nothing. */
	private String colorReport;
	/** Frames whose VSync-on wait found no refresh grid (logged the first time, then at powers of two). */
	private long vsyncUnpaced;
	private boolean paced, vsync;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
		Native.vsyncView(this.view);  // RIL-476: the refresh grid follows the display that shows this view's window
		// test only: -Dmcms.metal.vsyncPaceTest=MODE with vsyncPaceTestAt=SECONDS and vsyncPaceTestFor=SECONDS takes the VSync-on
		// wait's refresh grid away for a while (mc_vsync_test); separate properties, because Prism's instance.cfg cannot hold a comma
		int testMode = Integer.getInteger("mcms.metal.vsyncPaceTest", 0);
		if (testMode != 0) {
			Native.vsyncTest(testMode, Double.parseDouble(System.getProperty("mcms.metal.vsyncPaceTestAt", "90")),
				Double.parseDouble(System.getProperty("mcms.metal.vsyncPaceTestFor", "10")));
		}
		// ADR 0002: the HUD shows only when MTL_HUD_ENABLED was set at launch; then the page's choice decides, so Off also hides a
		// HUD that the launcher enabled
		if (QuicksilverSettings.hudAtLaunch()) Native.layerHud(this.layer, QuicksilverSettings.hud() ? 1 : 0);
		current = this;
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.paced = !vsync && PACE;
		this.vsync = vsync;
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync || PACE_SYNC && this.paced ? 1 : 0) | DRAWABLES << 8 | this.colorspace << 16);
		logColor();
	}

	/** RIL-481: one {@code mcms-metal colorspace:} line with the layer's, its window's and the screen's colour space, when they change. */
	private void logColor() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(1, 256);
			Native.layerColorspaceReport(this.view, this.layer, out, 256);
			String report = MemoryUtil.memUTF8(out);
			if (!report.equals(this.colorReport)) {
				this.colorReport = report;
				System.out.println("mcms-metal colorspace: " + report);
			}
		}
	}

	/** ADR 0002: tags the layer again from -Dmcms.metal.colorspace, or from the default when the page cleared it. */
	private void retag() {
		this.colorspace = colorspace(colorspaceName());
		Native.layerColorspace(this.layer, this.colorspace);
		this.logColor();
	}

	/** ADR 0002: the page's Colors row changed -Dmcms.metal.colorspace (render thread); does nothing without a Metal surface. */
	static void retagCurrent() {
		MetalSurface surface = current;
		if (surface != null) surface.retag();
	}

	/** ADR 0002: the page's Metal HUD row (render thread); shows only when MTL_HUD_ENABLED was set at launch. */
	static void hudCurrent(boolean on) {
		MetalSurface surface = current;
		if (surface != null) Native.layerHud(surface.layer, on ? 1 : 0);
	}

	/**
	 * -Dmcms.metal.colorspace when set (none, srgb or p3). Else p3 when the mod mac-colorspace-rollback is loaded: it gives OpenGL
	 * windows and the Vulkan swapchain the Display P3 look and does nothing on Metal, so Metal follows it (chosen 2026-10-17).
	 * Else none, as Minecraft shows the frames on OpenGL and Vulkan.
	 */
	private static String colorspaceName() {
		String asked = System.getProperty("mcms.metal.colorspace");
		if (asked != null) return asked;
		if (!FabricLoader.getInstance().isModLoaded("mac-colorspace-rollback")) return "none";
		System.out.println("mcms-metal colorspace: the layer is tagged Display P3, because mac-colorspace-rollback is loaded");
		return "p3";
	}

	private static int colorspace(String value) {
		return switch (value) {
			case "none" -> 0;
			case "srgb" -> 1;
			case "p3" -> 2;
			default -> throw new IllegalArgumentException("-Dmcms.metal.colorspace: none, srgb or p3, not " + value);
		};
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		if (this.paced && !Native.pace(PACE_MARGIN_S)) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView); // no nextDrawable here: the present side acquires it
			return;
		}
		// VSync on: the GPU starts on the frame now, not after the wait for a drawable, which lasts until a refresh
		if (this.vsync && EARLY_COMMIT) this.encoder.commitEarly();
		if (this.vsync && VSYNC_PACE && VSYNC_LOW_LATENCY_MARGIN_S < 0) {
			long tv = WaitStats.ON ? System.nanoTime() : 0;
			this.countUnpaced(Native.vsyncWait(VSYNC_PACE_OFFSET_S));
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - tv, 0);
		}
		long t2 = WaitStats.ON ? System.nanoTime() : 0;
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t2, 0);
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
		if (this.vsync && VSYNC_PACE && VSYNC_LOW_LATENCY_MARGIN_S >= 0) {
			long tv = WaitStats.ON ? System.nanoTime() : 0;
			this.countUnpaced(Native.vsyncWaitNext(VSYNC_LOW_LATENCY_MARGIN_S));
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - tv, 0);
		}
	}

	/** -1 / -2 from the VSync-on wait: no refresh grid; logged the first time, then at powers of two. */
	private void countUnpaced(double waited) {
		if (waited < 0 && Long.bitCount(++this.vsyncUnpaced) == 1) {
			System.out.println("mcms-metal: the VSync-on wait found no refresh grid (" + (waited == -1 ? "no refresh period from the display link yet"
				: "no refresh seen for 0.25 s") + "); " + this.vsyncUnpaced + " frame(s) so far went without it");
		}
	}

	private static double lowLatencyMargin(String value) {
		if (value == null || value.equals("false")) return -1;
		return value.isEmpty() || value.equals("true") ? 0.0015 : Double.parseDouble(value) / 1000;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		if (current == this) current = null;
		Native.vsyncView(0);
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
