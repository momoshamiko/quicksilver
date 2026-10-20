// Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/native/mcmetal.h.
// Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: provenance comment and Enc.committedEarly
// (mc_enc_commit_early) added. 2026-10-12 (M4): Ctx.clearKernels (storage image clears) and Enc.compute (the frame's compute encoder
// for a pack's compute passes and those clears) added. After M5: Enc.blitGroup (the frame blit encoder's profiling group).
// Shared between mcmetal.m (the backend), mcterrain.m (terrain culling) and mcprobe.m (the debug visibility probe).
#import <Metal/Metal.h>

typedef struct {
	id<MTLDevice> device;
	id<MTLCommandQueue> queue;
	id<MTLLibrary> builtins;
	id<MTLRenderPipelineState> present;  // flips the frame into the drawable (same row order as GL/Vulkan)
	id<MTLSamplerState> presentSampler;
	NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *clearPipelines;  // keyed by attachment formats
	id<MTLDepthStencilState> depthWrite, depthKeep;
	id<MTLBuffer> fanIndices;  // {0, k+1, k+2} per triangle: Metal has no fans, so they're drawn as indexed triangle lists
	// M4: zero-fill kernels for storage images, made at first use: [kind * 3 + dims - 1], kind 0 float, 1 uint, 2 sint
	id<MTLComputePipelineState> clearKernels[9];
} Ctx;

#define MAX_COLORS 8

typedef struct {
	Ctx *ctx;
	id<MTLCommandBuffer> cmd;
	id<MTLBlitCommandEncoder> blit;
	// Stays open after mc_render_end: if the next pass draws to the same attachments without clearing, it continues in this
	// encoder, which on a tile GPU saves a full store + load of every attachment. Anything else encoded ends it first.
	id<MTLRenderCommandEncoder> render;
	// M4: the open compute encoder of the frame's command buffer (a pack's compute passes, storage clears). At most one of
	// render, blit and compute is open; opening any other one ends it.
	id<MTLComputeCommandEncoder> compute;
	id<MTLTexture> colors[MAX_COLORS];  // attachments of `render`, compared by identity only
	id<MTLTexture> depth;
	int colorCount;
	int width, height;  // of `render`'s viewport
	// Attachments whose contents died while `render` was open (the game cleared them): bit i = color i, bit 8 = depth.
	// Their store actions are decided at endEncoding, so dead ones are never written back to memory.
	unsigned dead;
	id<MTLBuffer> indexBuffer;
	MTLIndexType indexType;
	int indexSize;
	MTLPrimitiveType primitive;
	id<MTLCounterSampleBuffer> samples;  // set only while profiling: 4 timestamps per render encoder (vertex start/end, fragment start/end)
	int sampleCount;
	id sampleEnc[512];  // the encoder log (-Dmcms.own.int.encLog): each profiled group's encoder (retained until mc_profile_labels)
	char sampleKind[512];
	char sampleInfo[512][48];  // render groups: target size, load and store actions; blit groups: the mip chains they fill
	int renderGroup;
	int blitGroup;  // the open frame blit encoder's profiling group (-1: not profiled); set by blit() each time it opens one
	// Terrain culling only: a command buffer committed right before the frame's `cmd` (neither is enqueued earlier, so the
	// queue runs pre first), so work recorded into it mid-frame (geometry uploads, culling) still runs on the GPU before
	// anything in `cmd`. Opened by the frame's first use (mc_pre), so a frame without any has no second command buffer.
	// Nothing else orders them, on purpose: every buffer of pre's that `cmd` uses is bound or declared with useResource, so
	// hazard tracking orders those accesses, and a queue reports completions in order, so cmd's completion (what fences and
	// frame pacing wait on) implies pre's. An event between them would also wait for all older work on the queue, i.e. the
	// previous frame, and frames would stop overlapping.
	id<MTLCommandBuffer> pre;
	id<MTLBlitCommandEncoder> preBlit;
	id<MTLComputeCommandEncoder> preCompute;
	unsigned renderSerial;  // bumped for every render encoder opened (the native shading pipeline tells encoders apart by it)
	// opt-in (-Dmcms.cpu.pass): mc_render_begin's pass descriptor, kept and reset instead of made per pass; the color
	// attachments it may still name (cpuPassColors), and whether profiling put a sample buffer on it.
	MTLRenderPassDescriptor *cpuPassDesc;
	int cpuPassColors, cpuPassSampled;
	// opt-in (-Dmcms.cpu.cmdAhead): the next frame's command buffer, made on a background queue right after a commit;
	// cmd() takes it (waiting for it if it isn't made yet) instead of making one on the render thread.
	id<MTLCommandBuffer> cpuNext;
	dispatch_semaphore_t cpuNextReady;
	int cpuNextPending;
	// (mc_tl_restart) the open render encoder's depth was cleared at open (to tlDepthClearValue), and
	// whether anything in it wrote depth since (a pipeline whose depth state writes, an in-place depth clear)
	int tlDepthCleared, tlDepthWritten;
	float tlDepthClearValue;
	// mc_enc_commit_early committed this submit's work so far (VSync on); cleared by mc_enc_commit
	int committedEarly;
} Enc;

id<MTLCommandBuffer> mc_pre(Enc *enc);
id<MTLComputeCommandEncoder> mc_pre_compute(Enc *enc);
// A compute encoder on command buffer c; while a submit is profiled (-Dmcms.metal.trace) its start and end are sampled into the
// "vertex" slots of a group of 4, so it shows in the per-encoder GPU profile as an encoder with no fragment stage.
id<MTLComputeCommandEncoder> mc_profiled_compute(Enc *enc, id<MTLCommandBuffer> c, MTLDispatchType type);
id<MTLBlitCommandEncoder> mc_profiled_blit(Enc *enc, id<MTLCommandBuffer> c);
// The profile group of the last compute encoder mc_profiled_compute made (-1: not profiled).
int mc_last_compute_group(void);
void mc_pre_end_encoders(Enc *enc);
id<MTLComputeCommandEncoder> mc_render_suspend(Enc *enc);
id<MTLBlitCommandEncoder> mc_blit(Enc *enc);  // the frame's blit encoder, ending any open render encoder (shaderpack runtime)
void mc_set_rp_log(int on);
id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);  // the frame's command buffer with no encoder open (native shading pipeline)
void mc_render_resume(Enc *enc, id<MTLComputeCommandEncoder> compute);
