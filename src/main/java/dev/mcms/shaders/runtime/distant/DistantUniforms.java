// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.shaders.runtime.uniforms.Std140;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The Distant Horizons uniform block of the dh_ programs (plan.md E7; engine-pack-format.md section 17): the block the
 * translator declares for them ({@code QsDh}, the corpus test's {@code DH_BLOCK}), its std140 layout, and the values the
 * engine writes per frame. Fields are the pack's documented names: {@code dhNearPlane}, {@code dhFarPlane} (DH's projection
 * planes in blocks, not the render distance), {@code dhRenderDistance} (int), {@code dhProjection}, {@code dhProjectionInverse},
 * {@code dhPreviousProjection} (OpenGL depth convention, see {@link DistantProjection#toOpenGl}).
 *
 * <p>Values come from DH's per-frame parameters: {@code DhApiRenderParam.nearClipPlane}/{@code farClipPlane} and
 * {@code dhProjectionMatrix} (javap 2026-11-15). The render distance is DH's {@code lodChunkRenderDistanceRadius} in
 * chunks, written in blocks (16 per chunk) [inference: the Iris page says only "the render distance currently set in DH"].
 */
public final class DistantUniforms {

	/** The block's name as the translator declares it. */
	public static final String BLOCK_NAME = "QsDh";

	/** The members in declaration order (the std140 offsets follow from them: see {@link #block}). */
	static final List<Std140.Member> MEMBERS = List.of(
		new Std140.Member("dhNearPlane", "float"),
		new Std140.Member("dhFarPlane", "float"),
		new Std140.Member("dhRenderDistance", "int"),
		new Std140.Member("dhProjection", "mat4"),
		new Std140.Member("dhProjectionInverse", "mat4"),
		new Std140.Member("dhPreviousProjection", "mat4"));

	/** The per-frame values of the block. */
	public record Values(float nearPlane, float farPlane, int renderDistanceBlocks, Matrix4f projection,
			Matrix4f projectionInverse, Matrix4f previousProjection) {
		public Values {
			projection = new Matrix4f(projection);
			projectionInverse = new Matrix4f(projectionInverse);
			previousProjection = new Matrix4f(previousProjection);
		}
	}

	private DistantUniforms() {
	}

	/** The block as the translator declares it, at {@code slot}. */
	public static UniformBlock block(int slot) {
		return Std140.block(BLOCK_NAME, slot, MEMBERS);
	}

	/** The byte size of the block, padded to std140's rules (what {@link #write} fills). */
	public static int size(UniformBlock block) {
		return Std140.paddedSize(block);
	}

	/**
	 * Writes the values at the block's offsets, little-endian, column-major matrices. The buffer must hold {@link #size}
	 * bytes from its position.
	 *
	 * @throws IllegalArgumentException a field the block does not declare, or a buffer too small
	 */
	public static ByteBuffer write(UniformBlock block, Values values, ByteBuffer out) {
		return write(block, values.nearPlane(), values.farPlane(), values.renderDistanceBlocks(), values.projection(),
			values.projectionInverse(), values.previousProjection(), out);
	}

	/**
	 * {@link #write(UniformBlock, Values, ByteBuffer)} from the values themselves, with no record: the engine writes the
	 * block this way every frame. A little-endian buffer takes the absolute puts itself (no view is made).
	 *
	 * @throws IllegalArgumentException a field the block does not declare, or a buffer too small
	 */
	public static ByteBuffer write(UniformBlock block, float nearPlane, float farPlane, int renderDistance,
			Matrix4fc projection, Matrix4fc projectionInverse, Matrix4fc previousProjection, ByteBuffer out) {
		if (out.remaining() < size(block)) {
			throw new IllegalArgumentException("the QsDh block needs " + size(block) + " bytes, the buffer has "
				+ out.remaining());
		}
		ByteBuffer le = out.order() == ByteOrder.LITTLE_ENDIAN ? out : out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int base = le.position();
		putFloat(le, base, block, "dhNearPlane", nearPlane);
		putFloat(le, base, block, "dhFarPlane", farPlane);
		putInt(le, base, block, "dhRenderDistance", renderDistance);
		putMatrix(le, base, block, "dhProjection", projection);
		putMatrix(le, base, block, "dhProjectionInverse", projectionInverse);
		putMatrix(le, base, block, "dhPreviousProjection", previousProjection);
		out.position(out.position() + size(block));
		return out;
	}

	/** The byte offset of a field in the block (relative to the block's start). */
	static int offsetOf(UniformBlock block, String name) {
		for (UniformField field : block.fields()) {
			if (field.name().equals(name)) {
				return field.offset();
			}
		}
		throw new IllegalArgumentException("the " + block.name() + " block has no field " + name);
	}

	private static void putFloat(ByteBuffer le, int base, UniformBlock block, String name, float value) {
		le.putFloat(base + offsetOf(block, name), value);
	}

	private static void putInt(ByteBuffer le, int base, UniformBlock block, String name, int value) {
		le.putInt(base + offsetOf(block, name), value);
	}

	private static void putMatrix(ByteBuffer le, int base, UniformBlock block, String name, Matrix4fc m) {
		int at = base + offsetOf(block, name);
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				le.putFloat(at + (column * 4 + row) * Float.BYTES, m.get(column, row));
			}
		}
	}
}
