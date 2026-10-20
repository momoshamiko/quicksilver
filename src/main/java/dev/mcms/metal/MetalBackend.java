/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalBackend.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal.
 * 2026-10-20 (RIL-497): a failure while the Metal device is made becomes a BackendCreationException, so the game falls back.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/** Native Metal rendering for Minecraft's renderpearl layer, next to Mojang's OpenGL and Vulkan (MoltenVK) backends. */
public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() {
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		MetalDevice device;
		try {
			device = new MetalDevice();
		} catch (LinkageError | RuntimeException e) {
			// RIL-497: Minecraft tries its next backend only after a BackendCreationException (Minecraft.<init>). Anything else, such
			// as libmcmsmetal.dylib failing to load (an ExceptionInInitializerError from Native), would end the start. A mixin at the
			// constructor's tail (VitrailDeviceReportMixin) runs after `current = this`, so a failure there must not leave the
			// fallback backend with a current Metal device: every Vitrail seam reads it (MetalStorage.active).
			MetalDevice.current = null;
			Throwable why = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
			BackendCreationException unavailable = new BackendCreationException("Metal unavailable: " + why, BackendCreationException.Reason.PLATFORM_ERROR);
			unavailable.initCause(e);
			throw unavailable;
		}
		return new FrontendGpuDevice(device);
	}
}
