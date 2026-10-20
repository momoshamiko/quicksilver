/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The combined image samplers a SPIR-V module declares, read off its words: binding, whether the image is a depth image, and
 * whether the module ever samples through the sampler or only reads texels.
 * <p>
 * Why: a Metal stage has 16 sampler slots (the runtime compiler refuses {@code [[sampler(16)]]} and up), and SPIRV-Cross gives
 * every combined image sampler a sampler argument. A texture that is only fetched ({@code texelFetch}, {@code textureSize}:
 * {@code OpImage} then {@code OpImageFetch} or a size query) needs no sampler, so {@link MetalPipeline} gives it a constant one
 * that takes no slot, and numbers the samplers that are really used without gaps.
 * <p>
 * "Samples" is decided conservatively: a loaded sampled image used by anything but {@code OpImage}, or the variable itself
 * handed to anything but a load or an access chain (a function call, for instance), counts as sampled. A literal operand that
 * happens to equal one of those ids also counts; that only costs a slot, never a wrong picture.
 */
final class SpirvResources {
	/** One combined image sampler variable (an array of them counts once). */
	record Combined(int set, int binding, boolean depth, boolean sampled, String name) {
	}

	private static final int MAGIC = 0x07230203;
	private static final int OP_NAME = 5, OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLED_IMAGE = 27, OP_TYPE_ARRAY = 28, OP_TYPE_RUNTIME_ARRAY = 29,
		OP_TYPE_POINTER = 32, OP_VARIABLE = 59, OP_LOAD = 61, OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66,
		OP_PTR_ACCESS_CHAIN = 67, OP_DECORATE = 71, OP_COPY_OBJECT = 83, OP_IMAGE = 100;
	private static final int DECORATION_BINDING = 33, DECORATION_DESCRIPTOR_SET = 34;
	private static final int STORAGE_UNIFORM_CONSTANT = 0;

	final List<Combined> combined;

	private SpirvResources(List<Combined> combined) {
		this.combined = combined;
	}

	/** Opcodes whose operands never carry a use of a sampled image: debug names, annotations, types, constants, declarations. */
	private static boolean declarative(int op) {
		return op <= 8 // OpNop .. OpLine
			|| op >= 10 && op <= 39 // OpExtension .. OpTypeForwardPointer (entry points, execution modes, capabilities, types)
			|| op >= 41 && op <= 52 // constants and spec constants
			|| op >= 71 && op <= 75 // decorations and decoration groups
			|| op == 317 || op == 330 || op == 331 || op == 332 // OpNoLine, OpModuleProcessed, OpExecutionModeId, OpDecorateId
			|| op == 5632 || op == 5633; // OpDecorateString, OpMemberDecorateString
	}

	private static boolean derives(int op) {
		return op == OP_LOAD || op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_PTR_ACCESS_CHAIN || op == OP_COPY_OBJECT;
	}

	static SpirvResources read(ByteBuffer spv) {
		IntBuffer words = spv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		int base = words.position();
		int end = words.limit();
		if (end - base < 5 || words.get(base) != MAGIC) throw new IllegalArgumentException("not a SPIR-V module");
		int bound = words.get(base + 3);
		// The variable each loaded value or derived pointer comes from (0: none).
		int[] origin = new int[bound];
		boolean[] combinedVar = new boolean[bound];
		boolean[] sampled = new boolean[bound];
		// Type id -> its OpTypeImage id, through array types; negative for a sampled image type.
		int[] imageOfType = new int[bound];
		boolean[] depthImage = new boolean[bound];
		int[] pointee = new int[bound];
		int[] pointerClass = new int[bound];
		int[] varType = new int[bound];
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, int[]> bindings = new HashMap<>();
		List<Integer> vars = new ArrayList<>();
		// Pass 1: types, variables, decorations, and where each loaded value or derived pointer comes from. SPIR-V puts a
		// definition before its uses, except for OpPhi operands, which pass 2 treats as uses anyway.
		for (int at = base + 5; at < end; ) {
			int head = words.get(at);
			int count = head >>> 16, op = head & 0xFFFF;
			if (count == 0) throw new IllegalArgumentException("SPIR-V instruction of length 0 at word " + (at - base));
			switch (op) {
				case OP_NAME -> names.put(words.get(at + 1), string(words, at + 2, count - 2));
				case OP_TYPE_IMAGE -> {
					int id = words.get(at + 1);
					imageOfType[id] = id;
					depthImage[id] = words.get(at + 4) == 1;
				}
				case OP_TYPE_SAMPLED_IMAGE -> imageOfType[words.get(at + 1)] = -imageOfType[words.get(at + 2)];
				case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> imageOfType[words.get(at + 1)] = imageOfType[words.get(at + 2)];
				case OP_TYPE_POINTER -> {
					int id = words.get(at + 1);
					pointerClass[id] = words.get(at + 2);
					pointee[id] = words.get(at + 3);
				}
				case OP_VARIABLE -> {
					int type = words.get(at + 1), id = words.get(at + 2);
					if (words.get(at + 3) == STORAGE_UNIFORM_CONSTANT && pointerClass[type] == STORAGE_UNIFORM_CONSTANT && imageOfType[pointee[type]] < 0) {
						combinedVar[id] = true;
						varType[id] = type;
						vars.add(id);
					}
				}
				case OP_DECORATE -> {
					int decoration = words.get(at + 2);
					if (decoration == DECORATION_BINDING || decoration == DECORATION_DESCRIPTOR_SET) {
						int[] b = bindings.computeIfAbsent(words.get(at + 1), k -> new int[] {0, -1});
						b[decoration == DECORATION_DESCRIPTOR_SET ? 0 : 1] = words.get(at + 3);
					}
				}
				default -> {
					if (derives(op)) {
						int source = words.get(at + 3);
						int from = combinedVar[source] ? source : origin[source];
						if (from != 0) origin[words.get(at + 2)] = from;
					}
				}
			}
			at += count;
		}
		// Pass 2: any use of a loaded sampled image other than taking its image, or any use of the variable other than a load or an
		// access chain, means the module samples through it.
		for (int at = base + 5; at < end; ) {
			int head = words.get(at);
			int count = head >>> 16, op = head & 0xFFFF;
			if (!declarative(op) && op != OP_VARIABLE) {
				boolean defines = derives(op);
				for (int w = 1; w < count; w++) {
					if (defines && w <= 3) continue;  // result type, result, and the pointer or value it reads
					if (op == OP_IMAGE && w == 3) continue;  // the image of a sampled image: texelFetch, textureSize
					int id = words.get(at + w);
					if (id <= 0 || id >= bound) continue;
					int from = combinedVar[id] ? id : origin[id];
					if (from != 0) sampled[from] = true;
				}
			}
			at += count;
		}
		List<Combined> combined = new ArrayList<>();
		for (int id : vars) {
			int[] b = bindings.getOrDefault(id, new int[] {0, -1});
			int image = -imageOfType[pointee[varType[id]]];
			combined.add(new Combined(b[0], b[1], image > 0 && depthImage[image], sampled[id], names.getOrDefault(id, "%" + id)));
		}
		return new SpirvResources(List.copyOf(combined));
	}

	private static String string(IntBuffer words, int at, int count) {
		byte[] bytes = new byte[count * 4];
		int n = 0;
		for (int i = 0; i < count; i++) {
			int w = words.get(at + i);
			for (int k = 0; k < 4; k++) {
				byte b = (byte) (w >>> (8 * k));
				if (b == 0) return new String(bytes, 0, n, StandardCharsets.UTF_8);
				bytes[n++] = b;
			}
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}
}
