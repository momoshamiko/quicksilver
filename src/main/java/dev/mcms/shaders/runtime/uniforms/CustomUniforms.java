// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.shaders.expr.CompiledExpr;
import dev.mcms.shaders.expr.ExprEval;
import dev.mcms.shaders.expr.ExprFrame;
import dev.mcms.shaders.expr.ExprType;
import dev.mcms.shaders.pack.FrameLayout;
import dev.mcms.shaders.pack.PackModel;

/**
 * The pack's custom uniforms (engine-pack-format.md section 13; OptiFine doc shaders.properties "Custom uniforms"): the
 * block {@code QsCustom} with every {@code uniform.*} value the pack defines, evaluated once per frame on the render
 * thread in declaration order — each result is written back into its frame slot, so a later expression reads the
 * values of the earlier ones, and a pack that declares its {@code variable.*} lines first (the documented example
 * pattern) has them evaluated first. {@code variable.*} lines are evaluated too but are not in the block; a value
 * whose expression failed to compile at load is absent from the block and its error is logged once here.
 *
 * <p>One instance per loaded pack; nothing on the frame path allocates. Evaluation never throws (the expression
 * language's guarantee). The expression parameters are the ones {@link FrameUniforms#fill} writes from the same
 * frame's uniform block, and the expression clock ({@code smooth}'s delta, {@code print}'s seconds) is the frame
 * uniform's clock: a {@code mcms.shaders.holdClock} hold holds {@code print}'s seconds while {@code smooth}'s delta
 * keeps the real frame time.
 */
public final class CustomUniforms {
	/** The custom block's name. */
	public static final String BLOCK_NAME = "QsCustom";

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	private final List<Entry> entries;
	private final UniformBlock blockSchema;
	private final ByteBuffer block;
	private final FrameLayout layout;
	private final ExprEval eval;
	private final float[] vec = new float[4];
	private final Map<String, Integer> offsets = new HashMap<>();

	/**
	 * Builds the evaluator from the pack model (which holds the compiled {@code uniform.*}/{@code variable.*}
	 * expressions and their {@link FrameLayout}); compiles nothing new.
	 *
	 * @param model the loaded pack
	 * @return the pack's custom uniforms, or {@code null} when the pack defines no evaluable value
	 */
	public static CustomUniforms of(PackModel model) {
		List<Entry> entries = new ArrayList<>();
		List<Std140.Member> members = new ArrayList<>();
		List<PackModel.CustomValue> values = model.customValues();
		Map<String, Integer> lastUniform = new HashMap<>();
		for (int i = 0; i < values.size(); i++) {
			PackModel.CustomValue cv = values.get(i);
			if (cv.error().isPresent() || cv.compiled().isEmpty() || cv.outputSlot().isEmpty()) {
				continue;
			}
			if (!cv.variable()) {
				lastUniform.put(cv.name(), i);
			}
		}
		int scratch = 0;
		boolean any = false;
		for (int i = 0; i < values.size(); i++) {
			PackModel.CustomValue cv = values.get(i);
			if (cv.error().isPresent() || cv.compiled().isEmpty() || cv.outputSlot().isEmpty()) {
				if (cv.error().isPresent()) {
					// Logged once per load, as the frame block logs unknown uniforms once per program.
					LOG.warn("[mcms-shaders] pack {} custom {}.{} not served: {}", model.name(),
						cv.variable() ? "variable" : "uniform", cv.name(), cv.error().orElse("not compiled"));
				}
				continue;
			}
			any = true;
			CompiledExpr compiled = cv.compiled().get();
			boolean writesBlock = !cv.variable() && Integer.valueOf(i).equals(lastUniform.get(cv.name()));
			entries.add(new Entry(cv.name(), typeWord(cv.type().orElseThrow()), cv.type().orElseThrow(), compiled,
				cv.outputSlot().getAsInt(), writesBlock));
			scratch = Math.max(scratch, compiled.vectorScratch());
			if (writesBlock) {
				members.add(new Std140.Member(cv.name(), typeWord(cv.type().orElseThrow())));
			}
		}
		if (!any) {
			return null;
		}
		return new CustomUniforms(entries, members, model.frameLayout(), scratch);
	}

	private CustomUniforms(List<Entry> entries, List<Std140.Member> members, FrameLayout layout, int scratch) {
		this.entries = entries;
		this.blockSchema = Std140.block(BLOCK_NAME, 0, members);
		this.block = ByteBuffer.allocate(size()).order(ByteOrder.LITTLE_ENDIAN);
		this.layout = layout;
		this.eval = new ExprEval(new ExprFrame(), scratch);
		for (Entry e : entries) {
			e.compiled().requireScratch(eval);
		}
		for (UniformField f : blockSchema.fields()) {
			offsets.put(f.name(), f.offset());
		}
	}

	/**
	 * The custom block: the pack's {@code uniform.*} values in declaration order at std140 offsets, bound at
	 * {@code slot}.
	 *
	 * @param slot the buffer slot the engine binds the block at
	 * @return the block for a {@code ProgramRequest.UniformSchema}
	 */
	public UniformBlock schema(int slot) {
		return new UniformBlock(BLOCK_NAME, slot, blockSchema.fields());
	}

	/** The block's padded size in bytes (what {@link #write} fills). */
	public int size() {
		return Std140.paddedSize(blockSchema);
	}

	/**
	 * Evaluates every expression once per frame, in declaration order: the frame's parameters first (from the same
	 * frame uniform block, through {@link FrameUniforms#fill}), then each value, its result written back to its slot
	 * for the expressions after it.
	 *
	 * @param frame the frame uniforms of the same frame
	 */
	public void update(FrameUniforms frame) {
		ExprFrame exprFrame = eval.frame;
		exprFrame.ensureSlots(layout.slots());
		frame.fill(exprFrame, layout);
		eval.deltaTime = frame.frameTime();
		eval.timeSeconds = frame.clockSeconds();
		for (Entry e : entries) {
			switch (e.exprType()) {
				case FLOAT -> exprFrame.setFloat(e.slot(), e.compiled().evalF(eval));
				case INT -> exprFrame.setInt(e.slot(), e.compiled().evalI(eval));
				case BOOL -> exprFrame.setBool(e.slot(), e.compiled().evalB(eval));
				case VEC2, VEC3, VEC4 -> {
					e.compiled().evalV(eval, vec, 0);
					switch (e.exprType()) {
						case VEC2 -> exprFrame.setVec2(e.slot(), vec[0], vec[1]);
						case VEC3 -> exprFrame.setVec3(e.slot(), vec[0], vec[1], vec[2]);
						default -> exprFrame.setVec4(e.slot(), vec[0], vec[1], vec[2], vec[3]);
					}
				}
				default -> {
				}
			}
			if (e.writesBlock()) {
				writeField(e);
			}
		}
	}

	private void writeField(Entry e) {
		int o = offsets.get(e.name());
		ExprFrame f = eval.frame;
		switch (e.exprType()) {
			case FLOAT -> block.putFloat(o, f.getFloat(e.slot()));
			case INT -> block.putInt(o, f.getInt(e.slot()));
			case BOOL -> block.putInt(o, f.getBool(e.slot()) ? 1 : 0);
			case VEC2 -> {
				f.getVec(e.slot(), vec);
				block.putFloat(o, vec[0]).putFloat(o + 4, vec[1]);
			}
			case VEC3 -> {
				f.getVec(e.slot(), vec);
				block.putFloat(o, vec[0]).putFloat(o + 4, vec[1]).putFloat(o + 8, vec[2]);
			}
			case VEC4 -> {
				f.getVec(e.slot(), vec);
				block.putFloat(o, vec[0]).putFloat(o + 4, vec[1]).putFloat(o + 8, vec[2]).putFloat(o + 12, vec[3]);
			}
			default -> {
			}
		}
	}

	/**
	 * Writes the block's std140 bytes, {@link #size()} of them, little-endian.
	 *
	 * @param dst the destination buffer, positioned at the block's start
	 */
	public void write(ByteBuffer dst) {
		dst.put(block.array(), 0, size());
	}

	private static String typeWord(ExprType type) {
		return switch (type) {
			case FLOAT -> "float";
			case INT -> "int";
			case BOOL -> "bool";
			case VEC2 -> "vec2";
			case VEC3 -> "vec3";
			case VEC4 -> "vec4";
			case MAT3 -> "mat3";
			case MAT4 -> "mat4";
		};
	}

	/** One custom value: its name, GLSL type word, expression type, compiled expression, frame slot and block write flag. */
	private record Entry(String name, String type, ExprType exprType, CompiledExpr compiled, int slot,
			boolean writesBlock) {
	}
}
