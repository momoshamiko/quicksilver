// Quicksilver: original code (ADR 0006), Apache-2.0.
// The shader engine's native entry points (mcshaders.m), bound from Java by dev.mcms.shaders.metal.ShaderNative through
// MetalBridge.nativeLookup(). Everything here is manual memory management like the rest of the image (-fno-objc-arc): handles
// returned as id cross to Java as long and are freed with mc_release.
// 2026-11-13 (E3): mcs_render_label names the encoder's open render encoder for the encoder log (see mcshaders.m).
#import "mcmetal.h"

// The version of these entry points; ShaderNative checks it when it loads. Bump with any signature change.
int mcs_version(void);

/*
 * Compiles MSL source into an MTLLibrary with the engine's options (see the comment block above the definition in
 * mcshaders.m for the decisions). languageVersion/mathMode are the raw MTLLanguageVersion/MTLMathMode enum values;
 * preserveInvariance is Metal's MTLCompileOptions option of the same name. Returns nil on failure with Metal's NSError
 * text in err (the backend's mc_library_new stays on MSL 3.0 with fast math for the game's own pipelines).
 */
id<MTLLibrary> mcs_library_new(Ctx *ctx, const char *source, char *err, int errCap, int languageVersion, int mathMode,
	int preserveInvariance);

// The library's function names (newLibraryWithSource makes one per top-level entry point), newline-separated into out.
// Returns the number of names actually written (a too-small cap truncates); out is always null-terminated.
// A compute pipeline from an already compiled library (the backend's mc_compute_pipeline_new recompiles from source with
// its own options). *maxThreads gets maxTotalThreadsPerThreadgroup, or 0 on failure.
id<MTLComputePipelineState> mcs_compute_pipeline_new(Ctx *ctx, id<MTLLibrary> library, const char *entry, char *err,
	int errCap, int *maxThreads);

/*
 * A texture of any shape the engine needs: type is the raw MTLTextureType (2D, 2DArray, 3D, ...; for the array types the
 * depth argument is the layer count, for 3D it is the depth), storage the raw MTLStorageMode. Width, height, depth and
 * mips must each be at least 1: invalid arguments return nil without calling Metal (newTextureWithDescriptor aborts the
 * process on some of them). The backend's own mc_texture_new has no arrays and fixed private storage.
 */
id<MTLTexture> mcs_texture_new(Ctx *ctx, int format, int type, int width, int height, int depth, int mips, int usage,
	int storage);

// Copies a box of texels from host memory into a texture level/slice, synchronously (own command buffer, waits for the
// GPU). The frame-time path uploads through the frame's blit encoder (mc_blit_buffer_to_texture); this is the load-time
// and test path. Returns 0 on failure.
int mcs_texture_upload(Ctx *ctx, id<MTLTexture> texture, const void *bytes, uint64_t bytesPerRow, uint64_t bytesPerImage,
	int slice, int mip, int x, int y, int z, int w, int h, int d);

// Copies a box of texels out of a texture (any dimension, any slice; mc_blit_texture_to_buffer reads 2D regions only),
// synchronously. Returns 0 on failure.
int mcs_texture_read(Ctx *ctx, id<MTLTexture> texture, int slice, int mip, int x, int y, int z, int w, int h, int d,
	void *out, uint64_t bytesPerRow, uint64_t bytesPerImage);

// A texture buffer over a copy of bytes (width texels of format; usage is the raw MTLTextureUsage). Returns nil on failure.
id<MTLTexture> mcs_texture_buffer_new(Ctx *ctx, int format, int width, const void *bytes, uint64_t bytesLen, int usage);

// Names the open render encoder (the engine's passes) for the encoder log, traces and pass reports. No-op when no render
// encoder is open.
void mcs_render_label(Enc *enc, const char *label);

// 2026-11-13 (E3 review): sets the open render encoder's viewport (scale.<program> sub-rectangles, the final pass's
// window size); no-op when no render encoder is open. Version 6 (mcshaders.m).
void mcs_render_viewport(Enc *enc, double x, double y, double w, double h);

// 2026-11-17 (E9, ADR 0008): MetalFX's spatial scaler from in (render size) into out (window size) on the frame's command
// buffer, after ending the open encoders. Returns 1 (built a scaler), 0 (reused it) or a refusal code (see mcshaders.m).
// refuse != 0 answers as a device without MetalFX. Version 7.
int mcs_spatial_scale(Enc *enc, id<MTLTexture> in, id<MTLTexture> out, int refuse);

// Releases the spatial scaler (a set's release, the device's close); the next mcs_spatial_scale builds a new one.
void mcs_spatial_release(void);
