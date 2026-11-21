// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shadowcomp fragment program on the GPU (E5 review item 15): it writes {@code shadowcolor0} as a composite-style pass
 * after the shadow pass, and the buffer that later passes sample then holds its write. With the pack's flip on (the
 * default) the write is the read texture; with {@code flip.shadowcomp.shadowcolor0=false} no later pass sees it. The pass
 * block is uploaded for the frame before the pass binds it (E5 review item 2): a vertex stage that reads
 * {@code gl_TextureMatrix[0]} sees the identity only then.
 */
@Tag("gpu")
class ShadowCompGpuTest {

	private static final int SIZE = 64;
	private static final int RGBA8 = 70;

	/** A fragment write of a constant, and a vertex stage that reads no pass-block value. */
	private static final String CONSTANT_VERTEX = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";
	private static final String CONSTANT_FRAGMENT = """
		#version 130
		const int shadowMapResolution = 64;
		/* DRAWBUFFERS:0 */
		varying vec2 texcoord;
		void main() {
			gl_FragData[0] = vec4(0.25, 0.5, 0.75, 1.0);
		}
		""";

	/** The vertex stage takes its texture coordinate through {@code gl_TextureMatrix[0]}, the pass block's identity. */
	private static final String MATRIX_VERTEX = """
		#version 130
		varying vec2 uv;
		void main() {
			uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
			gl_Position = ftransform();
		}
		""";
	private static final String MATRIX_FRAGMENT = """
		#version 130
		const int shadowMapResolution = 64;
		/* DRAWBUFFERS:0 */
		varying vec2 uv;
		void main() {
			gl_FragData[0] = vec4(uv, 0.0, 1.0);
		}
		""";

	@Test
	void aShadowcompWriteIsWhatTheLaterPassesSample(@TempDir Path dir) throws Exception {
		int[] written = readAfterShadowcomp(dir.resolve("flip"), Map.of(), CONSTANT_VERTEX, CONSTANT_FRAGMENT, 0, 0);
		assertEquals(64, written[0], 2, "red: the pass wrote 0.25");
		assertEquals(128, written[1], 2, "green: the pass wrote 0.5");
		assertEquals(191, written[2], 2, "blue: the pass wrote 0.75");
	}

	@Test
	void aWriteWithTheFlipOffStaysOutOfTheBuffersLaterPassesRead(@TempDir Path dir) throws Exception {
		int[] kept = readAfterShadowcomp(dir.resolve("noflip"), Map.of("shadowcomp.shadowcolor0", false),
			CONSTANT_VERTEX, CONSTANT_FRAGMENT, 0, 0);
		assertEquals(255, kept[0], "the clear colour stays: the write went to the alt texture");
		assertEquals(255, kept[1]);
		assertEquals(255, kept[2]);
	}

	@Test
	void theShadowcompPassReadsTheFrameUploadedPassBlock(@TempDir Path dir) throws Exception {
		// The quad's texture coordinate at the last texel is 63.5/64 (about 253 of 255) through the identity. Without this
		// frame's upload the block is unbound and the coordinate is zero.
		int[] far = readAfterShadowcomp(dir.resolve("matrix"), Map.of(), MATRIX_VERTEX, MATRIX_FRAGMENT, SIZE - 1, SIZE - 1);
		assertEquals(253, far[0], 3, "red: u at the last texel, read through gl_TextureMatrix[0]");
		assertEquals(253, far[1], 3, "green: v at the last texel, read through gl_TextureMatrix[0]");
	}

	/**
	 * The red, green and blue of texel ({@code x}, {@code y}) of {@code shadowcolor0} after the set's shadowcomp pass, on the
	 * GPU. The pass block and the frame block are uploaded to buffers the test owns, as the engine's transient upload does.
	 */
	private static int[] readAfterShadowcomp(Path dir, Map<String, Boolean> flips, String vertex, String fragment, int x, int y)
			throws Exception {
		PackModel model = writePack(dir, vertex, fragment);
		CustomUniforms custom = CustomUniforms.of(model);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			ShadowTargets shadow = null;
			Targets targets = null;
			try {
				DimensionSet set = DimensionSet.load(ctx, "shadow-comp", model, model.rootFolder(), custom, RGBA8,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertEquals(0, set.absent(), "the shadowcomp fragment stage runs from the pack's files: " + set.notes());
				shadow = ShadowTargets.of(set.dimension());
				assertTrue(shadow.flipping(), "a shadowcomp fragment program makes the colour buffers flip");
				assertTrue(shadow.create(Targets.engineTextures(ctx)), "the device makes the shadow maps");
				targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
					false, false, SIZE, SIZE, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				toRelease.add(noise);
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				toRelease.add(copy);
				long block = MetalBridge.newBuffer(ctx, Math.max(16, FrameUniforms.size()));
				toRelease.add(block);
				long[] frameBlock = {block, 0};
				EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
					@Override
					public long[] frameBlock(String program) {
						return frameBlock;
					}

					@Override
					public long[] customBlock() {
						return null;
					}
				};
				EngineFrame frame = new EngineFrame(set, targets, shadow, noise, copy, 0, upload(ctx, toRelease), flips,
					Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> {}, null));
				// The shadow pass's clear makes the buffer white; then the shadowcomp passes run on the frame's encoder.
				shadow.clear(enc);
				MetalBridge.commitAndWait(enc);
				frame.shadowComposite(enc, targets, blocks);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				frame.close();
				byte[] texels = gpu.readTexture(shadow.color(0), 0, 0, SIZE, SIZE, 1, 4);
				int at = (y * SIZE + x) * 4;
				return new int[] {texels[at] & 0xff, texels[at + 1] & 0xff, texels[at + 2] & 0xff};
			} finally {
				if (shadow != null) {
					shadow.release();
				}
				if (targets != null) {
					targets.release();
				}
				for (long handle : toRelease) {
					MetalBridge.release(handle);
				}
			}
		}
	}

	/** A block upload that copies the bytes into a buffer the test owns and tracks for release. */
	private static EngineFrame.BlockUpload upload(long ctx, List<Long> tracker) {
		return (bytes, out) -> {
			bytes.rewind();
			long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
			MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
				.copyFrom(MemorySegment.ofBuffer(bytes));
			tracker.add(buffer);
			out[0] = buffer;
			out[1] = 0;
		};
	}

	private static PackModel writePack(Path dir, String vertex, String fragment) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("shadowcomp.vsh"), vertex);
		Files.writeString(shaders.resolve("shadowcomp.fsh"), fragment);
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}
}
