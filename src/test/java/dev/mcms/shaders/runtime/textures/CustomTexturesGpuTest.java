// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import dev.mcms.shaders.pack.textures.CustomTextureTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Dim;
import dev.mcms.shaders.pack.textures.CustomTextureTable.Entry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E6-B on the Metal device: a pack's PNG and raw texture are created by {@link CustomTextureSet} and sampled by a translated
 * program with the state the table decided. The PNG is nearest and repeat (1.25 reads texel 0); the raw texture is linear
 * and clamp (the midpoint interpolates, 1.5 reads the last texel). The default normal atlas reads the flat normal.
 */
@Tag("gpu")
final class CustomTexturesGpuTest {

	@TempDir
	Path dir;

	private static final String VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static final String FS_SAMPLE = """
		#version 120
		uniform sampler2D png;
		uniform sampler2D raw;
		void main() {
			float r = texture2D(png, vec2(1.25, 0.25)).r;
			float g = texture2D(raw, vec2(0.5, 0.5)).r;
			float b = texture2D(raw, vec2(1.5, 0.5)).r;
			gl_FragData[0] = vec4(r, g, b, 1.0);
		}
		""";

	private static final String FS_NORMAL = """
		#version 120
		uniform sampler2D colortex7;
		void main() {
			gl_FragData[0] = texture2D(colortex7, vec2(0.3, 0.7));
		}
		""";

	/** A 2x1 PNG: texel 0 red 40, texel 1 red 200. */
	private void writePng(Path file) throws Exception {
		BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xff000000 | 40 << 16);
		image.setRGB(1, 0, 0xff000000 | 200 << 16);
		Files.createDirectories(file.getParent());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		Files.write(file, out.toByteArray());
	}

	/** A 2x1 RGBA32F raw file: texel 0 red 0.2, texel 1 red 0.6. */
	private void writeRaw(Path file) throws Exception {
		ByteBuffer b = ByteBuffer.allocate(2 * 16).order(ByteOrder.LITTLE_ENDIAN);
		b.putFloat(0.2f).putFloat(0f).putFloat(0f).putFloat(1f);
		b.putFloat(0.6f).putFloat(0f).putFloat(0f).putFloat(1f);
		Files.createDirectories(file.getParent());
		Files.write(file, b.array());
	}

	private CustomTextureTable table(String properties) throws Exception {
		Path root = dir.resolve("pack");
		writePng(root.resolve("shaders/textures/ps.png"));
		writeRaw(root.resolve("shaders/textures/raw.dat"));
		Files.createDirectories(root.resolve("shaders"));
		try (PackSource pack = PackSource.open(root)) {
			return CustomTextureTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
		}
	}

	private static SamplerState state(Entry e) {
		return new SamplerState(e.filter(), null, e.wrap(), false, false);
	}

	@Test
	void pngIsNearestAndRepeatAndRawIsLinearAndClampOnTheDevice() throws Exception {
		CustomTextureTable t = table("customTexture.png=textures/ps.png\n"
			+ "customTexture.raw=textures/raw.dat TEXTURE_2D RGBA32F 2 1 RGBA FLOAT\n");
		Entry png = t.resolve("composite", "png", Dim.D2);
		Entry raw = t.resolve("composite", "raw", Dim.D2);
		assertEquals(dev.mcms.glsl.msl.ProgramRequest.Filter.NEAREST, png.filter());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomTextureSet set = CustomTextureSet.create(gpu.context(), t, path -> null, FakeGameViews.none());
			assertTrue(set != null);
			int pngId = set.id("composite", "png"), rawId = set.id("composite", "raw");
			assertTrue(pngId >= 0 && rawId >= 0, "every recorded sampler has an id");
			assertEquals(-1, set.id("gbuffers_basic", "png"), "a program that did not resolve it has none");
			ProgramRequest request = new ProgramRequest("custom", ProgramRequest.Family.FULL_SCREEN,
				List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS_SAMPLE)),
				ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)),
				List.of(TextureBinding.constexpr("png", 0, TextureKind.COLOUR, state(png)),
					TextureBinding.constexpr("raw", 1, TextureKind.COLOUR, state(raw))),
				List.of(), List.of(), UniformSchema.none(), null, false);
			ProgramTranslation tr = translated(request);
			byte[][] out = gpu.renderFullscreenOn(tr.msl(), tr.vertexEntry(), tr.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, new long[] {set.handle(pngId), set.handle(rawId)}, new byte[0],
				new byte[0][]);
			assertEquals(40, out[0][0] & 0xff, "nearest, repeat: 1.25 reads texel 0 (clamp would read 200)");
			assertEquals(102, out[0][1] & 0xff, "linear: the midpoint of 0.2 and 0.6 is 0.4");
			assertEquals(153, out[0][2] & 0xff, "clamp: 1.5 reads the last texel 0.6 (repeat would read 0.4)");
			set.close();
		}
	}

	@Test
	void theDefaultNormalAtlasReadsTheFlatNormalAtEveryCoordinate() throws Exception {
		CustomTextureTable t = table("texture.composite.colortex7=minecraft:textures/atlas/blocks_n.png\n");
		Entry nrm = t.resolve("composite", "colortex7", Dim.D2);
		assertEquals(CustomTextureTable.Source.DEFAULT_NORMAL, nrm.source());
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomTextureSet set = CustomTextureSet.create(gpu.context(), t, path -> null, FakeGameViews.none());
			assertTrue(set != null);
			int id = set.id("composite", "colortex7");
			long handle = set.handle(id);
			assertNotEquals(0L, handle);
			ProgramRequest request = new ProgramRequest("normal", ProgramRequest.Family.FULL_SCREEN,
				List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS_NORMAL)),
				ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)),
				List.of(TextureBinding.constexpr("colortex7", 0, TextureKind.COLOUR, state(nrm))), List.of(), List.of(),
				UniformSchema.none(), null, false);
			ProgramTranslation tr = translated(request);
			byte[][] out = gpu.renderFullscreenOn(tr.msl(), tr.vertexEntry(), tr.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, new long[] {handle}, new byte[0], new byte[0][]);
			assertEquals(127, out[0][0] & 0xff, "the documented normal: 127, 127, 255, 255");
			assertEquals(127, out[0][1] & 0xff);
			assertEquals(255, out[0][2] & 0xff);
			assertEquals(255, out[0][3] & 0xff);
			set.close();
		}
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new ProgramRequest.StageSource(stage, text,
			java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("custom", i)).toList());
	}

	/** A translation that must succeed; its diagnostics go into the failure message. */
	private static ProgramTranslation translated(ProgramRequest request) {
		ProgramTranslation t = Translator.translate(request);
		if (!t.ok()) {
			throw new AssertionError("translation failed: " + t.diagnostics());
		}
		return t;
	}
}
