// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.glsl.msl.ProgramRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Metal buffer index of each pack block of one program (EngineSlots: pack blocks take 0 to 19; decided 2026-11-14 from
 * the corpus measurement), and the bufferObject index a shader storage block binds.
 *
 * <ul>
 * <li>A buffer block (an SSBO) with {@code layout(binding = N)} takes index N. This is the buffer the pack's
 *     {@code bufferObject.N} names (OptiFine: a buffer object is the SSBO at its binding), so the engine binds both at N.
 * <li>A buffer block with no binding takes the lowest index that no buffer binding takes, and binds {@code bufferObject.0}
 *     (GL's default binding point for a shader storage block is 0). Its Metal slot and its bufferObject index are separate.
 * <li>Every other block (a uniform block) takes the lowest index that no buffer binding takes, in declaration order, and
 *     binds no bufferObject. A uniform block's own binding number is not a buffer index (OpenGL keeps uniform and
 *     shader-storage binding points apart).
 * <li>A program that cannot be given slots (a binding at or above the limit, two buffer blocks with one binding, or more
 *     blocks than the range holds) gets an error and no slots; the caller refuses the program.
 * </ul>
 */
public final class BufferSlots {

	/**
	 * A program's slots: the blocks in declaration order with their indices, or {@code error} naming why the program has
	 * none (then the list is empty).
	 */
	public record Assignment(List<ProgramRequest.BufferBinding> buffers, @Nullable String error) {

		public Assignment {
			buffers = List.copyOf(buffers);
		}
	}

	private static final Pattern BLOCK = Pattern.compile("\\b(buffer|uniform)\\s+(\\w+)\\s*\\{");
	/** A {@code binding = N} inside a layout qualifier in the text between the previous declaration and the keyword. */
	private static final Pattern BINDING = Pattern.compile("\\blayout\\s*\\([^)]*\\bbinding\\s*=\\s*(\\d{1,6})");
	/** The bufferObject index a buffer block with no binding binds: GL's default shader storage binding point. */
	private static final int DEFAULT_BUFFER_OBJECT = 0;

	private BufferSlots() {
	}

	/**
	 * The slots of the blocks declared in {@code source} (the program's stage text, macros expanded or not), for a range of
	 * {@code limit} indices (0 to limit - 1). A block declared twice (the same name) takes one slot, as before E6.
	 */
	public static Assignment assign(CharSequence source, int limit) {
		String text = source.toString();
		List<Decl> decls = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Matcher m = BLOCK.matcher(text);
		while (m.find()) {
			String name = m.group(2);
			if (!names.add(name)) {
				continue;
			}
			boolean buffer = m.group(1).equals("buffer");
			decls.add(new Decl(name, buffer, buffer ? explicitBinding(text, m.start()) : -1));
		}
		int[] slots = new int[decls.size()];
		Arrays.fill(slots, -1);
		Map<Integer, String> bound = new HashMap<>();
		for (int i = 0; i < decls.size(); i++) {
			Decl d = decls.get(i);
			if (d.binding() < 0) {
				continue;
			}
			if (d.binding() >= limit) {
				return failed("buffer block " + d.name() + " declares binding " + d.binding()
					+ "; the engine binds pack blocks at 0 to " + (limit - 1));
			}
			String other = bound.putIfAbsent(d.binding(), d.name());
			if (other != null) {
				return failed("buffer blocks " + other + " and " + d.name() + " both declare binding " + d.binding());
			}
			slots[i] = d.binding();
		}
		int next = 0;
		for (int i = 0; i < decls.size(); i++) {
			if (slots[i] >= 0) {
				continue;
			}
			while (next < limit && bound.containsKey(next)) {
				next++;
			}
			if (next >= limit) {
				return failed("the program declares more than " + limit + " pack blocks");
			}
			slots[i] = next++;
		}
		List<ProgramRequest.BufferBinding> out = new ArrayList<>(decls.size());
		for (int i = 0; i < decls.size(); i++) {
			Decl d = decls.get(i);
			int bufferObject = !d.buffer() ? -1 : d.binding() >= 0 ? d.binding() : DEFAULT_BUFFER_OBJECT;
			out.add(new ProgramRequest.BufferBinding(d.name(), slots[i], bufferObject));
		}
		return new Assignment(out, null);
	}

	private static Assignment failed(String reason) {
		return new Assignment(List.of(), reason);
	}

	/** The {@code binding} of the layout qualifier that leads the declaration at {@code keyword}, or -1 when it has none. */
	private static int explicitBinding(String text, int keyword) {
		int from = Math.max(text.lastIndexOf(';', keyword),
			Math.max(text.lastIndexOf('{', keyword), text.lastIndexOf('}', keyword))) + 1;
		Matcher b = BINDING.matcher(text.substring(from, keyword));
		return b.find() ? Integer.parseInt(b.group(1)) : -1;
	}

	/** One block as declared: its name, whether it is a buffer (SSBO) block, and the binding its layout gives (-1 for none). */
	private record Decl(String name, boolean buffer, int binding) {
	}
}
