/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalDevice.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; system
 * properties -Dmcopt.metal.* renamed to -Dmcms.metal.*; cut the hooks into the rest of mcopt (cpu.Cpu pass flags, the two culling
 * caches MetalTerrain added to each reserved arena and its D32 usage bit, own.AnimCopy/ResidentAnim residency); driver string names this mod.
 * 2026-10-12 (M4): shader-write usage on request (MetalHooks.storageUsage), and the device the storage seam works on (current).
 * 2026-10-13 (M5): the S4 memory log starts here (MetalHealth, -Dmcms.metal.memLog), and -Dmcms.metal.textureLog logs each texture's
 * creation with the classes that asked for it.
 * 2026-10-16: the driver string names the mod by its public name, Quicksilver.
 */
package dev.mcms.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalDevice implements GpuDeviceBackend {
	/**
	 * Sodium grows its terrain geometry in arenas of up to 256 MiB, created the moment a region no longer fits (flight 5.34 s:
	 * 179 MB). A fresh buffer's memory is found and wired in the submission of the first command buffer using it, the whole
	 * buffer at once, and when free memory is short the kernel compresses other memory first: 36-45 ms of one frame on the mini
	 * with a 4 GiB heap. So one arena of this size is kept provisioned ahead of need, off the render thread (Native.poolAdd),
	 * and handed to the next arena request it can hold, with its full size: Sodium sizes the arena from buffer.size(). Costs
	 * its size in memory while it waits. -Dmcms.metal.arenaReserve=0 turns it off.
	 */
	private static final long ARENA_RESERVE = Long.getLong("mcms.metal.arenaReserve", 256L << 20);
	/** Wiring costs about 0.03 ms per MiB with memory free and 0.2-0.25 ms per MiB when short: only big arenas stall a frame. */
	private static final long ARENA_RESERVE_MIN = 64L << 20;
	/** A provisioned arena waits in the pool (at most one at a time). */
	private boolean arenaReserved;
	private final long ctx;
	private final MetalEncoder encoder;
	/** M4: the device this backend made, for the storage seam (MetalStorage), which Vitrail reaches through static calls; null after close. */
	static volatile @Nullable MetalDevice current;
	private final DeviceInfo info;

	MetalDevice() throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			this.ctx = Native.create(name, 256, err, 1024);
			if (this.ctx == 0) throw new BackendCreationException("Metal unavailable: " + MemoryUtil.memUTF8(err), BackendCreationException.Reason.PLATFORM_ERROR);
			this.info = new DeviceInfo(
				MemoryUtil.memUTF8(name), "Apple", "Metal (Quicksilver 0.1.1, from mcopt 0.3.0-alpha.2)", true, "Metal", 1.0F,
				new DeviceLimits(16, 256, 16384, Native.maxBufferLength(this.ctx), Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
				// Every multi-draw form is a native loop of Metal draws, so all of them are "supported".
				new DeviceFeatures(true, true, true, true, true, true, true, true),
				Set.of(),
				new HintsAndWorkarounds(false, false, true, false),
				DeviceType.INTEGRATED);
		}
		this.encoder = new MetalEncoder(this.ctx);
		// -Dmcms.metal.cbdiag=MS: stall diagnostics on stderr (big buffer allocations, slow command buffers); see mcmetal.m.
		String diag = System.getProperty("mcms.metal.cbdiag");
		if (diag != null) Native.diagEnable(Double.parseDouble(diag));
		// -Dmcms.metal.gpuCount[=PATH]: measurement only, per render encoder and debug group: samples passing depth (visibility
		// counts), draws and primitives per pipeline, one JSON line each to PATH (true: gpucount.jsonl in the game dir); see mcmetal.m.
		// -Dmcms.metal.gpuCount.every=N: only every Nth frame (the frames between run as without the flag).
		// -Dmcms.metal.gpuCount.groups=false: one visibility slot per encoder (no new slot at debug groups).
		if (MetalPipeline.GPU_COUNT) Native.gpuCountEnable(MetalPipeline.GPU_COUNT_PATH, Integer.getInteger("mcms.metal.gpuCount.every", 1),
			!"false".equals(System.getProperty("mcms.metal.gpuCount.groups")));
		MetalHealth.start(this.ctx);  // -Dmcms.metal.memLog=SECONDS (spec S4)
		current = this;
	}

	/** M4: what the storage seam (MetalStorage) records into and allocates from. */
	MetalEncoder encoder() {
		return this.encoder;
	}

	long ctx() {
		return this.ctx;
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new MetalSurface(this.ctx, this.encoder, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return MetalSampler.create(this.ctx, u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		// MTLTextureUsage: ShaderRead 1, ShaderWrite 2, RenderTarget 4. Copies need no usage bit in Metal. ShaderWrite only where a shaderpack
		// engine asks for it (a target a compute writes as colorimgN): it can cost the texture its lossless compression.
		java.util.function.BooleanSupplier storage = MetalHooks.storageUsage;
		int mtlUsage = ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? 1 : 0) | ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? 4 : 0)
			| (storage != null && storage.getAsBoolean() ? 3 : 0);
		// Shaderpacks sample the game's depth (depthtex0) too.
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long handle = Native.textureNew(this.ctx, MetalConst.pixelFormat(format), width, height, layers, mips, Math.max(1, mtlUsage), cube ? 1 : 0);
		if (handle == 0) throw new IllegalStateException("Couldn't create " + width + "x" + height + " " + format + " texture " + label);
		MetalTexture texture = new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mips);
		texture.shaderWrite = (mtlUsage & 2) != 0;
		if (MetalTexture.LOG) {
			// -Dmcms.metal.textureLog: who asked for each texture, the first three callers outside the backend and the API
			String callers = StackWalker.getInstance().walk(frames -> frames.map(f -> f.getClassName() + "." + f.getMethodName())
				.filter(n -> !n.startsWith("dev.mcms.metal.") && !n.startsWith("com.mojang.renderpearl.")).limit(3)
				.collect(java.util.stream.Collectors.joining(" < ")));
			System.out.println("mcms-metal texture: + " + Long.toHexString(handle) + " " + width + "x" + height + "x" + layers + " mips " + mips + " " + format
				+ " usage " + usage + " '" + texture.getLabel() + "' from " + callers);
		}
		return texture;
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
		return new MetalTexture.View(this.encoder, (MetalTexture) texture, baseMip, mips);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		MetalEvents.Operation event = MetalEvents.begin("allocate", -1, size);
		// Sodium's ArenaAggregator names every geometry and index arena this way.
		boolean arena = label != null && "Arena buffer".equals(label.get());
		MetalBuffer buffer;
		try {
			long handle = 0, actual = size;
			if (arena && ARENA_RESERVE > 0 && size >= ARENA_RESERVE_MIN) {
				// At most twice what was asked for: Sodium fills an arena over time, a much bigger one would mostly sit empty.
				if (size <= ARENA_RESERVE && size * 2 >= ARENA_RESERVE) handle = Native.poolTake(size, ARENA_RESERVE);
				if (handle != 0) {
					actual = Native.bufferLength(handle);
					this.arenaReserved = false;
				}
				// The next one a second later: provisioning takes memory, and this frame is already finding some for the arena's use.
				if (!this.arenaReserved) this.arenaReserved = Native.poolAdd(this.ctx, ARENA_RESERVE, handle != 0 ? 1000 : 0);
			}
			buffer = new MetalBuffer(this.encoder, handle != 0 ? handle : Native.bufferNew(this.ctx, size), usage, actual);
		} finally {
			MetalEvents.end(event);
		}
		return buffer;
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		// A brand-new buffer isn't referenced by any command yet, so the data can go straight into its memory.
		MetalBuffer buffer = new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, data.remaining()), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
		// Called on a worker thread by the frontend; Metal pipeline creation is thread-safe, so compile right here.
		MetalPipeline pipeline = MetalPipeline.compile(this.encoder, info);
		return () -> pipeline;
	}

	@Override
	public void close() {
		this.encoder.waitIdle();
		if (current == this) current = null;
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new GpuQueryPool() {
			@Override
			public int size() {
				return size;
			}

			@Override
			public OptionalLong getValue(int index) {
				return OptionalLong.empty();
			}

			@Override
			public OptionalLong[] getValues(int index, int count) {
				OptionalLong[] values = new OptionalLong[count];
				java.util.Arrays.fill(values, OptionalLong.empty());
				return values;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}
}
