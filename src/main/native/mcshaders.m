// Quicksilver: original code (ADR 0006), Apache-2.0.
// The shader engine's native side: Metal libraries, pipelines and passes for pack programs. It is linked into the same image
// as the backend (mcmetal.m, libmcmsmetal.dylib); the Java side binds these functions through MetalBridge.nativeLookup().
// 2026-11-13 (W2-C): mcs_texture_upload copies one layer per blit into a 2D array (the staging buffer holds one image per layer).
// 2026-11-13 (W2-C): mcs_texture_buffer_new (texture buffers, MSL texture_buffer<T>, over a shared buffer holding a copy of the
// texels); mcs_version 4.
// 2026-11-13 (W2-C): mcs_texture_new takes the layer count of a cube array (MTLTextureTypeCubeArray), as it does for the 2D arrays.
// 2026-11-13 (W2-C review): mcs_texture_buffer_new refuses bytes shorter than width texels and aligns the row to the device's minimum linear texture alignment.
// 2026-11-13 (E3): mcs_render_label names the open render encoder of a frame for the encoder log; mcs_version 5.
// 2026-11-13 (E3 review): mcs_render_viewport sets the open render encoder's viewport (scale.<program>, the final pass's window size); mcs_version 6.
// 2026-11-17 (E9, ADR 0008): mcs_spatial_scale and mcs_spatial_release, MetalFX's spatial scaler for the render scale; mcs_version 7.

#import "mcshaders.h"
#import <MetalFX/MetalFX.h>

/*
 * The engine's compile options for pack programs (decided in W1-C, ADR 0006):
 * - languageVersion: chosen by the caller. The engine compiles pack programs with MSL 3.1 or newer (ADR 0006 §2); the
 *   backend's own pipelines stay on 3.0 with fast math (mc_library_new, unchanged).
 * - mathMode: chosen by the caller. Pack programs (render and compute) use MTLMathModeRelaxed (ShaderNative.MATH_PACKS, E7,
 *   2026-11-16); W1-C chose MTLMathModeFast, like the backend's pipelines. GLSL promises no IEEE 754 semantics for
 *   floating-point arithmetic (GLSL 4.60 specification §4.1.4 leaves precision, contraction and reassociation to the
 *   implementation), but OpenGL on this device keeps infinities and NaNs, and packs draw them: Bliss 2.1.2's sky table
 *   averages the lookup of normalize(vec2(0)), which is NaN under Iris (the lookup reads 0). Fast math folded that NaN
 *   away in a constant-array loop even through an IEEE divide (a GPU probe: 1.0 under fast, NaN under relaxed and
 *   safe); relaxed keeps fast math's optimisations and honours infinities and NaNs. Measured on this GPU (Apple
 *   silicon, 2026-11-13, MSL 3.1) by MathModeGpuTest and the review of it: division, subtraction and float comparisons
 *   are bit-identical in MATH_SAFE and MATH_FAST; isnan() and isinf() classify plain run-time values under fast math;
 *   fast math folds self-compares (x != x false, x == x true for a run-time NaN), refines x/x to 1.0 at x = 0, gives
 *   0.0 for inf - inf and a non-NaN normalize() of the zero vector. The emitter maps GLSL's isnan idiom `x != x` to
 *   isnan(x) and normalize to an IEEE-divide helper (Prelude), so neither relies on the mode.
 * - preserveInvariance: on. GLSL's invariant qualifier (GLSL 4.60 specification §4.3.5) is how packs get identical
 *   positions from two programs that draw the same geometry twice (a shadow pass and the lit pass that compares against
 *   its depth); Metal's option carries that guarantee across separately compiled libraries, which is exactly the engine's
 *   situation (one library per program). It only covers positions whose declaration carries [[invariant]] (the MSL
 *   attribute), so the emitter must write [[position, invariant]] on every translated vertex output. InvarianceGpuTest
 *   renders one triangle with inexact matrix values through two separately compiled libraries (both with [[invariant]])
 *   and checks bit-identical Depth32Float values; its control run without the option measures whether this GPU's compiler
 *   would differ (see the test for the measured answer).
 *
 * Measured on this GPU while building the W1-C tests (the translator's transforms depend on these):
 * - An access::read_write image write is NOT visible to later reads in the same dispatch until
 *   threadgroup_barrier(mem_flags::mem_texture); GLSL imageStore/imageLoad coherence requires the emitter to insert it.
 * - A fragment store into an RGBA16Float target rounds toward zero, not to nearest (one half step below the
 *   round-to-nearest value for values in between); comparisons must allow one half ulp for half targets.
 * - sample_compare passes when ref CMP sampled depth (like OpenGL's shadow samplers: ref <= texel under less_equal).
 *
 * Every object created here deliberately skips the backend's live-object tracking (mcmetal.m's tkResource/tk): the
 * memLog counters report the game's resources, not the engine's.
 */

// The version of this file's entry points; the Java bindings check it when they load.
int mcs_version(void) {
	return 7;
}

/*
 * Names the encoder's open render encoder (the engine's own full-screen passes), so it appears in the encoder log
 * (-Dmcms.metal.encLog), in traces and in pass reports the way the backend's own passes do: their label comes from the
 * render pass descriptor, which the engine's natively opened encoders have no descriptor label for (mc_render_begin opens
 * from raw attachments). E3, plan.md "the engine's encoders must appear in the backend's encoder log". No-op when no
 * render encoder is open. The label is copied.
 */
void mcs_render_label(Enc *enc, const char *label) {
	@autoreleasepool {
		if (enc->render) enc->render.label = [NSString stringWithUTF8String:label ? label : ""];
	}
}

/*
 * Sets the open render encoder's viewport to the box (x, y, w, h) in pixels of its targets, depth 0 to 1. The engine's
 * scale.<program> names a sub-rectangle of a buffer (OptiFine shaders.properties: the viewport changes, the buffer does
 * not). No-op when no render encoder is open. Called after the pass opens (mc_render_begin sets the full target).
 */
void mcs_render_viewport(Enc *enc, double x, double y, double w, double h) {
	if (enc->render) [enc->render setViewport:(MTLViewport) {x, y, w, h, 0, 1}];
}

/*
 * ADR 0008: MetalFX's spatial scaler upscales the pack's final image (in, at the render size) into the game's main colour
 * (out, at the window size). Written from Apple's MetalFX documentation (MTLFXSpatialScalerDescriptor, MTLFXSpatialScaler).
 * One scaler for the process (the engine draws one upscale a frame, on the render thread), kept while the two sizes and
 * formats stay; it holds no texture between encodes, so a resize frees the old textures at once.
 */
static id<MTLFXSpatialScaler> spatialScaler;
static id<MTLDevice> spatialDevice;
static NSUInteger spatialInW, spatialInH, spatialOutW, spatialOutH;
static MTLPixelFormat spatialInFormat, spatialOutFormat;

void mcs_spatial_release(void) {
	[spatialScaler release];
	spatialScaler = nil;
	spatialDevice = nil;
}

/*
 * Encodes the upscale on the frame's command buffer: mc_frame_cmd ends the open render, blit and compute encoders, and the
 * scaler encodes its own work after them; the next pass opens a new encoder. The colour processing mode is perceptual: the
 * pack's final image is tone mapped and display-referred (Apple's guidance for the spatial scaler's input). refuse != 0
 * answers as a device without MetalFX would (the fallback's own run). Returns 1 when this call built a new scaler, 0 when
 * it reused one, or why it encoded nothing: -1 MetalFX does not support the device (or refuse), -2 the scaler did not
 * build, -3 in lacks the scaler's colour texture usage, -4 out lacks its output texture usage, -5 out is not private.
 */
int mcs_spatial_scale(Enc *enc, id<MTLTexture> in, id<MTLTexture> out, int refuse) {
	if (!enc || !enc->ctx || !in || !out) return -2;
	@autoreleasepool {
		@try {
			id<MTLDevice> device = enc->ctx->device;
			if (refuse || ![MTLFXSpatialScalerDescriptor supportsDevice:device]) return -1;
			if (in.width < 1 || in.height < 1 || out.width < 1 || out.height < 1) return -2;
			int built = 0;
			if (!spatialScaler || spatialDevice != device || in.width != spatialInW || in.height != spatialInH
					|| out.width != spatialOutW || out.height != spatialOutH || in.pixelFormat != spatialInFormat
					|| out.pixelFormat != spatialOutFormat) {
				mcs_spatial_release();
				MTLFXSpatialScalerDescriptor *d = [[MTLFXSpatialScalerDescriptor new] autorelease];
				d.inputWidth = in.width;
				d.inputHeight = in.height;
				d.outputWidth = out.width;
				d.outputHeight = out.height;
				d.colorTextureFormat = in.pixelFormat;
				d.outputTextureFormat = out.pixelFormat;
				d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
				spatialScaler = [d newSpatialScalerWithDevice:device];
				if (!spatialScaler) return -2;
				spatialDevice = device;
				spatialInW = in.width;
				spatialInH = in.height;
				spatialOutW = out.width;
				spatialOutH = out.height;
				spatialInFormat = in.pixelFormat;
				spatialOutFormat = out.pixelFormat;
				built = 1;
			}
			MTLTextureUsage colorUsage = spatialScaler.colorTextureUsage, outputUsage = spatialScaler.outputTextureUsage;
			// A refusal keeps no scaler: the engine falls back to its copy draw and would not use it (ADR 0008 review).
			int refusal = (in.usage & colorUsage) != colorUsage ? -3 : (out.usage & outputUsage) != outputUsage ? -4
				: out.storageMode != MTLStorageModePrivate ? -5 : 0;
			if (refusal != 0) {
				mcs_spatial_release();
				return refusal;
			}
			id<MTLCommandBuffer> c = mc_frame_cmd(enc);
			spatialScaler.colorTexture = in;
			spatialScaler.outputTexture = out;
			spatialScaler.inputContentWidth = in.width;
			spatialScaler.inputContentHeight = in.height;
			[spatialScaler encodeToCommandBuffer:c];
			spatialScaler.colorTexture = nil;
			spatialScaler.outputTexture = nil;
			return built;
		} @catch (NSException *ex) {
			mcs_spatial_release();
			return -2;
		}
	}
}

// localizedDescription is the concise diagnostic; description adds the domain and UserInfo dictionary, which can push the
// useful text past the buffer's end.
static void copyError(NSError *e, char *err, int cap) {
	if (err && cap > 0) {
		NSString *text = e ? (e.localizedDescription.length ? e.localizedDescription : e.description) : @"unknown error";
		const char *utf8 = text.UTF8String;
		strlcpy(err, utf8 ? utf8 : "unknown error", cap);
	}
}
// mcmetal.m's TK_LIBRARY: the engine's libraries are counted with the backend's objects (mc_track_count).
extern id mc_track_object(id obj, int kind);
enum { MCS_TK_LIBRARY = 7 };
id<MTLLibrary> mcs_library_new(Ctx *ctx, const char *source, char *err, int errCap, int languageVersion, int mathMode,
	int preserveInvariance) {
	if (!ctx || !source) {
		if (err && errCap > 0) strlcpy(err, "invalid library arguments", errCap);
		return nil;
	}
	@autoreleasepool {
		@try {
			NSString *src = [NSString stringWithUTF8String:source];
			if (!src) {
				if (err && errCap > 0) strlcpy(err, "invalid UTF-8 shader source", errCap);
				return nil;
			}
			MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
			o.languageVersion = (MTLLanguageVersion) languageVersion;
			o.mathMode = (MTLMathMode) mathMode;
			o.preserveInvariance = preserveInvariance != 0;
			NSError *e = nil;
			id<MTLLibrary> lib = [ctx->device newLibraryWithSource:src options:o error:&e];
			if (!lib) copyError(e, err, errCap);
			return mc_track_object(lib, MCS_TK_LIBRARY);
		} @catch (NSException *ex) {
			if (err && errCap > 0) strlcpy(err, ex.reason ? ex.reason.UTF8String : "Metal exception", errCap);
			return nil;
		}
	}
}

int mcs_function_names(id<MTLLibrary> library, char *out, int cap) {
	if (!out || cap <= 0) return 0;
	out[0] = 0;
	if (!library) return 0;
	int used = 0, written = 0;
	@autoreleasepool {  // functionNames is autoreleased: read it (and only it) inside the pool
		for (NSString *name in library.functionNames) {
			const char *n = name.UTF8String;
			size_t len = strlen(n);
			if (used && used + 1 >= cap) break;  // no room for the separator and any name
			if (used) out[used++] = '\n';
			if (used + len >= (size_t) cap) break;
			memcpy(out + used, n, len);
			used += (int) len;
			written++;
		}
	}
	out[used] = 0;
	return written;  // names actually written: a too-small cap truncates, it does not lie
}

id<MTLComputePipelineState> mcs_compute_pipeline_new(Ctx *ctx, id<MTLLibrary> library, const char *entry, char *err,
	int errCap, int *maxThreads) {
	if (maxThreads) *maxThreads = 0;
	if (!ctx || !library || !entry) {
		if (err && errCap > 0) strlcpy(err, "invalid compute pipeline arguments", errCap);
		return nil;
	}
	@autoreleasepool {
		@try {
			NSString *entryName = [NSString stringWithUTF8String:entry];
			if (!entryName) {
				if (err && errCap > 0) strlcpy(err, "invalid UTF-8 entry name", errCap);
				return nil;
			}
			id<MTLFunction> fn = [[library newFunctionWithName:entryName] autorelease];
			if (!fn) {
				if (err && errCap > 0) strlcpy(err, "the library has no function of that name", errCap);
				return nil;
			}
			NSError *e = nil;
			id<MTLComputePipelineState> pso = [ctx->device newComputePipelineStateWithFunction:fn error:&e];
			if (!pso) {
				copyError(e, err, errCap);
				return nil;
			}
			if (maxThreads) *maxThreads = (int) pso.maxTotalThreadsPerThreadgroup;
			return pso;
		} @catch (NSException *ex) {
			if (err && errCap > 0) strlcpy(err, ex.reason ? ex.reason.UTF8String : "Metal exception", errCap);
			return nil;
		}
	}
}

id<MTLTexture> mcs_texture_new(Ctx *ctx, int format, int type, int width, int height, int depth, int mips, int usage,
	int storage) {
	// Validate before Metal: newTextureWithDescriptor aborts the process on some invalid arguments (for example
	// mipmapLevelCount = 0 or MTLStorageModeManaged on Apple silicon) instead of returning nil.
	if (!ctx || width < 1 || height < 1 || depth < 1 || mips < 1) return nil;
	if (storage == MTLStorageModeManaged) return nil;
	if ((type == MTLTextureTypeCube || type == MTLTextureTypeCubeArray) && width != height) return nil;
	int effH = type == MTLTextureType1D || type == MTLTextureType1DArray ? 1 : height;
	int effD = type == MTLTextureType3D ? depth : 1;
	int maxDim = width > effH ? width : effH;
	if (effD > maxDim) maxDim = effD;
	int maxMips = 32 - __builtin_clz((unsigned) maxDim);
	if (mips > maxMips) return nil;
	@autoreleasepool {
		@try {
			MTLTextureDescriptor *d = [[MTLTextureDescriptor new] autorelease];
			d.textureType = (MTLTextureType) type;
			d.pixelFormat = (MTLPixelFormat) format;
			d.width = width;
			d.height = effH;
			if (type == MTLTextureType3D) d.depth = depth;
			else if (type == MTLTextureType1DArray || type == MTLTextureType2DArray || type == MTLTextureTypeCubeArray)
				d.arrayLength = depth;
			d.mipmapLevelCount = mips;
			d.usage = (MTLTextureUsage) usage;
			d.storageMode = (MTLStorageMode) storage;
			return [ctx->device newTextureWithDescriptor:d];
		} @catch (NSException *ex) {
			return nil;
		}
	}
}

int mcs_texture_upload(Ctx *ctx, id<MTLTexture> texture, const void *bytes, uint64_t bytesPerRow, uint64_t bytesPerImage,
	int slice, int mip, int x, int y, int z, int w, int h, int d) {
	if (!ctx || !texture || !bytes || w < 1 || h < 1 || d < 1 || slice < 0 || mip < 0 || x < 0 || y < 0 || z < 0) return 0;
	@autoreleasepool {
		@try {
			// A 2D array takes one copy per layer (its depth is the layer count, not a Z extent); 3D and 2D copy once.
			// The staging buffer holds d images: a 2D image is bytesPerRow * h, a 3D image is bytesPerImage.
			uint64_t image = bytesPerImage > 0 ? bytesPerImage : bytesPerRow * (uint64_t) h;
			uint64_t size = image * (uint64_t) d;
			if (size == 0) return 0;
			id<MTLBuffer> b = [[ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared] autorelease];
			if (!b) return 0;
			memcpy(b.contents, bytes, size);
			id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
			if (!cb) return 0;
			id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
			if (!blit) return 0;
			if (texture.textureType == MTLTextureType2DArray) {
				for (int layer = 0; layer < d; layer++)
					[blit copyFromBuffer:b sourceOffset:(uint64_t) layer * image sourceBytesPerRow:bytesPerRow
						sourceBytesPerImage:0 sourceSize:MTLSizeMake(w, h, 1) toTexture:texture
						destinationSlice:slice + layer destinationLevel:mip destinationOrigin:MTLOriginMake(x, y, 0)];
			} else {
				[blit copyFromBuffer:b sourceOffset:0 sourceBytesPerRow:bytesPerRow sourceBytesPerImage:bytesPerImage
					sourceSize:MTLSizeMake(w, h, d) toTexture:texture destinationSlice:slice destinationLevel:mip
					destinationOrigin:MTLOriginMake(x, y, z)];
			}
			[blit endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			return cb.status == MTLCommandBufferStatusCompleted && cb.error == nil;
		} @catch (NSException *ex) {
			return 0;
		}
	}
}

int mcs_texture_read(Ctx *ctx, id<MTLTexture> texture, int slice, int mip, int x, int y, int z, int w, int h, int d,
	void *out, uint64_t bytesPerRow, uint64_t bytesPerImage) {
	if (!ctx || !texture || !out || w < 1 || h < 1 || d < 1 || slice < 0 || mip < 0 || x < 0 || y < 0 || z < 0) return 0;
	@autoreleasepool {
		@try {
			uint64_t image = bytesPerImage > 0 ? bytesPerImage : bytesPerRow * (uint64_t) h;
			uint64_t size = image * (uint64_t) d;
			if (size == 0) return 0;
			id<MTLBuffer> b = [[ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared] autorelease];
			if (!b) return 0;
			id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
			if (!cb) return 0;
			id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
			if (!blit) return 0;
			[blit copyFromTexture:texture sourceSlice:slice sourceLevel:mip sourceOrigin:MTLOriginMake(x, y, z)
				sourceSize:MTLSizeMake(w, h, d) toBuffer:b destinationOffset:0 destinationBytesPerRow:bytesPerRow
				destinationBytesPerImage:bytesPerImage];
			[blit endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			int ok = cb.status == MTLCommandBufferStatusCompleted && cb.error == nil;
			if (ok) memcpy(out, b.contents, size);
			return ok;
		} @catch (NSException *ex) {
			return 0;
		}
	}
}

// A texture buffer (MSL texture_buffer<T>): width texels over a shared MTLBuffer that holds a copy of bytes. Texture buffers
// have one level, no sampler and no slices; a shader addresses them with read(uint) and write(uint). The new texture keeps
// its buffer alive (Metal's texture view shares the buffer's storage), so the buffer is released here. Returns nil for
// invalid arguments or when the device refuses.
// Bytes per texel of the formats the engine's texture buffers use; 0 for any other format (refused).
static NSUInteger mcs_texel_bytes(int format) {
	switch ((MTLPixelFormat) format) {
		case MTLPixelFormatR8Unorm: case MTLPixelFormatR8Snorm: case MTLPixelFormatR8Uint: case MTLPixelFormatR8Sint:
			return 1;
		case MTLPixelFormatR32Uint: case MTLPixelFormatR32Sint: case MTLPixelFormatR32Float:
		case MTLPixelFormatRGBA8Unorm: case MTLPixelFormatRGBA8Uint:
			return 4;
		case MTLPixelFormatRG32Uint: case MTLPixelFormatRG32Float: case MTLPixelFormatRGBA16Float:
			return 8;
		case MTLPixelFormatRGBA32Uint: case MTLPixelFormatRGBA32Float:
			return 16;
		default:
			return 0;
	}
}

id<MTLTexture> mcs_texture_buffer_new(Ctx *ctx, int format, int width, const void *bytes, uint64_t bytesLen, int usage) {
	if (width < 1 || !bytes) return nil;
	NSUInteger texel = mcs_texel_bytes(format);
	if (texel == 0 || bytesLen < (uint64_t) width * texel) return nil;  // the texels must fit in the bytes given
	@autoreleasepool {
		// The texture is one linear row: its stride is the copy rounded up to the device's minimum linear alignment.
		NSUInteger align = [ctx->device minimumLinearTextureAlignmentForPixelFormat:(MTLPixelFormat) format];
		NSUInteger row = (NSUInteger) ((bytesLen + align - 1) / align * align);
		id<MTLBuffer> b = [ctx->device newBufferWithLength:row options:MTLResourceStorageModeShared];
		if (!b) return nil;
		memset(b.contents, 0, row);
		memcpy(b.contents, bytes, (size_t) bytesLen);
		MTLTextureDescriptor *d = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:(MTLPixelFormat) format
			width:(NSUInteger) width resourceOptions:MTLResourceStorageModeShared usage:(MTLTextureUsage) usage];
		id<MTLTexture> t = [b newTextureWithDescriptor:d offset:0 bytesPerRow:row];
		// The texture must hold its buffer: while the texture lives, MTLTexture.buffer is that buffer. Without it the
		// release below would leave the texture reading freed memory, so the call refuses (returns nil) instead.
		if (t && !t.buffer) {
			[t release];
			t = nil;
		}
		[b release];
		return t;
	}
}
