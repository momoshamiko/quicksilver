// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.mcms.glsl.msl.ProgramRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The vertex layouts the engine can feed a pack program's vertex stage (plan.md E4, the registry contract): every game
 * {@link VertexFormat} converts to a {@link ProgramRequest.VertexLayout} (its elements' semantics, Metal formats and
 * offsets, one buffer), and {@link #register} adds layouts that are not the game's own — E4-B registers Sodium's
 * extended terrain format with its {@link PrologueFacts} (the region push constants and Sodium's {@code u_Globals}
 * offsets, ADR 0007). A registered layout always wins over the game-format conversion of the same object.
 *
 * <p>Thread rule: registration happens before the set that uses it loads (E4-B registers at Sodium mixin apply); the
 * lookups run on the render thread and on the background loader. Game formats convert once and cache.
 */
public final class VertexLayoutRegistry {

	/** A registered layout and the prologue facts it was registered with. */
	public record Entry(ProgramRequest.VertexLayout layout, PrologueFacts prologue) {
	}

	private static final Map<VertexFormat, Entry> REGISTERED = new ConcurrentHashMap<>();
	private static final Map<VertexFormat, ProgramRequest.VertexLayout> GAME_FORMATS = new ConcurrentHashMap<>();

	private VertexLayoutRegistry() {
	}

	/**
	 * Registers a vertex format's layout. E4-B's call: Sodium's extended terrain format with the prologue facts the
	 * {@code globals.glsl} parse computed. A second registration of the same format replaces the first (a Sodium
	 * release change re-registers).
	 */
	public static void register(VertexFormat format, ProgramRequest.VertexLayout layout, PrologueFacts prologue) {
		REGISTERED.put(format, new Entry(layout, prologue));
	}

	/** Forgets a registration (a format that is the game's own again, engine off). */
	public static void unregister(VertexFormat format) {
		REGISTERED.remove(format);
	}

	/** Whether the format carries a registration (E4-B's extended terrain format). */
	public static boolean isRegistered(VertexFormat format) {
		return REGISTERED.containsKey(format);
	}

	/**
	 * The formats with a registered layout (E4-B's extended Sodium terrain; the game's own formats are never here):
	 * the engine plans Sodium's own pipelines for each of them ({@code GbufferPlans.sodiumTerrainPipelines}).
	 */
	public static List<VertexFormat> registeredFormats() {
		return List.copyOf(REGISTERED.keySet());
	}

	/**
	 * The layout a pack program's vertex stage sees for this format: the registered one, else the game-format
	 * conversion (null when an element names a semantic the engine does not know — the format cannot serve pack
	 * programs and its pipelines draw through game-pipeline variants).
	 */
	public static ProgramRequest.@Nullable VertexLayout vertexLayoutFor(VertexFormat format) {
		Entry entry = REGISTERED.get(format);
		if (entry != null) {
			return entry.layout();
		}
		return GAME_FORMATS.computeIfAbsent(format, VertexLayoutRegistry::convert);
	}

	/**
	 * The prologue facts of this format's layout ({@link PrologueFacts#NONE} for every game format and an unregistered
	 * one; E4-B's terrain facts for the extended Sodium format).
	 */
	public static PrologueFacts prologueFor(VertexFormat format) {
		Entry entry = REGISTERED.get(format);
		return entry != null ? entry.prologue() : PrologueFacts.NONE;
	}

	/**
	 * The game element no pack program can read: the glint-special format's second texture coordinates. The translator
	 * has no semantic for it, and engine-pack-format.md section 11 lists no third coordinate set, so the layout leaves it
	 * out; its bytes stay in the vertex stride, where the program never fetches them.
	 */
	static final String UNREAD_ELEMENT = "UV3";

	/**
	 * Converts a game vertex format: each element that names a semantic becomes one attribute of buffer 0, except
	 * {@link #UNREAD_ELEMENT}. Null when another element names no semantic the engine knows.
	 */
	static ProgramRequest.@Nullable VertexLayout convert(VertexFormat format) {
		List<ProgramRequest.VertexAttribute> attributes = new ArrayList<>();
		int offset = 0;
		for (VertexFormatElement element : format.getElements()) {
			if (element.name().equals(UNREAD_ELEMENT)) {
				continue;
			}
			ProgramRequest.Semantic semantic = Semantics.semanticOf(element.name());
			String metal = metalFormat(element.format());
			if (semantic == null || metal == null) {
				return null;
			}
			attributes.add(new ProgramRequest.VertexAttribute(semantic, metal, 0, element.offset()));
			offset = Math.max(offset, element.offset());
		}
		if (attributes.isEmpty()) {
			return null;
		}
		return ProgramRequest.VertexLayout.of(attributes.toArray(new ProgramRequest.VertexAttribute[0]));
	}

	/**
	 * The emitter's Metal vertex format name of a {@link GpuFormat}: the base type of its bits and kind, with
	 * {@code Normalized} for the norm kinds ({@code RGBA8_UNORM} → {@code uchar4Normalized}, {@code RG16_SINT} →
	 * {@code short2}, {@code RGB32_FLOAT} → {@code float3}). The channel count and bits come from the name's first part
	 * ({@code RGBA8}: four channels of 8 bits). Null when the format has no such vertex type.
	 */
	static @Nullable String metalFormat(GpuFormat format) {
		String name = format.name();
		int under = name.indexOf('_');
		if (under < 0) {
			return null;
		}
		String channels = name.substring(0, under);
		String kind = name.substring(under + 1);
		int digit = 0;
		while (digit < channels.length() && !Character.isDigit(channels.charAt(digit))) {
			digit++;
		}
		String letters = channels.substring(0, digit);
		int n = switch (letters) {
			case "R" -> 1;
			case "RG" -> 2;
			case "RGB" -> 3;
			case "RGBA" -> 4;
			default -> 0;
		};
		if (n == 0 || digit == channels.length()) {
			return null;
		}
		int bits;
		try {
			bits = Integer.parseInt(channels.substring(digit));
		} catch (NumberFormatException notBits) {
			return null;
		}
		String base;
		boolean normalized = kind.endsWith("NORM");
		if (kind.equals("FLOAT")) {
			base = bits == 32 ? "float" : null;
		} else if (!kind.matches("(U|S)(NORM|INT)")) {
			return null;
		} else {
			boolean unsigned = kind.charAt(0) == 'U';
			base = switch (bits) {
				case 8 -> unsigned ? "uchar" : "char";
				case 16 -> unsigned ? "ushort" : "short";
				case 32 -> unsigned ? "uint" : "int";
				default -> null;
			};
		}
		if (base == null) {
			return null;
		}
		return base + n + (normalized ? "Normalized" : "");
	}

	/** The game element names that name a semantic the pack programs read (engine-entity-draws.md section 2). */
	private enum Semantics {
		// LineWidth names the line-width attribute of the outline formats (engine-vanilla-frame.md section 2).
		POSITION("Position", ProgramRequest.Semantic.POSITION), COLOR("Color", ProgramRequest.Semantic.COLOR),
		UV0("UV0", ProgramRequest.Semantic.TEXCOORD0), UV1("UV1", ProgramRequest.Semantic.OVERLAY),
		UV2("UV2", ProgramRequest.Semantic.LIGHTMAP), NORMAL("Normal", ProgramRequest.Semantic.NORMAL),
		LINE_WIDTH("LineWidth", ProgramRequest.Semantic.LINE_WIDTH);

		final String element;
		final ProgramRequest.Semantic semantic;

		Semantics(String element, ProgramRequest.Semantic semantic) {
			this.element = element;
			this.semantic = semantic;
		}

		static ProgramRequest.@Nullable Semantic semanticOf(String element) {
			for (Semantics s : values()) {
				if (s.element.equals(element)) {
					return s.semantic;
				}
			}
			return null;
		}
	}
}
