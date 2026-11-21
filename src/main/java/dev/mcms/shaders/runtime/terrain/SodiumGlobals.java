// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.glsl.GlslSyntaxException;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Declarator;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.NameTypeSpec;
import dev.mcms.glsl.ast.Qualifier;
import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.ast.StorageQual;
import dev.mcms.glsl.ast.Type;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.shaders.runtime.uniforms.Std140;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code std140} layout of Sodium's {@code u_Globals} block, computed from Sodium's own
 * {@code assets/sodium/shaders/include/globals.glsl} (ADR 0007 decision 3: the engine reads Sodium's shader interface
 * instead of hard-coding it, so a Sodium release that moves a field cannot silently draw wrong). The file has no
 * {@code #version} line (it is included into shaders that declare {@code #version 330 core}), so the parser sees it
 * with that line in front. Sodium's text is only read to learn the layout; no copy of it exists in Quicksilver.
 *
 * <p>{@link #parse} is pure and tested against both jars ADR 0007 allows. {@link #read} is the game-side arm:
 * {@code FabricLoader.getModContainer("sodium").findPath} (so a resource pack cannot change the answer).
 */
public record SodiumGlobals(ProgramRequest.UniformBlock block, List<String> problems) {

	/** The fields the terrain prologue needs; a block that lacks one fails the set's load (ADR 0007 decision 3). */
	public static final List<String> REQUIRED = List.of("u_ProjectionMatrix", "u_ModelViewMatrix", "u_TexCoordShrink");

	public SodiumGlobals {
		problems = List.copyOf(problems);
	}

	/** Parses the block out of a {@code globals.glsl} text. */
	public static SodiumGlobals parse(String text) {
		List<String> problems = new ArrayList<>();
		Unit unit;
		try {
			unit = Parser.parse("#version 330\n" + text);
		} catch (GlslSyntaxException e) {
			problems.add("does not parse as GLSL 330: " + e.getMessage());
			return new SodiumGlobals(emptyBlock(), problems);
		}
		for (Decl d : unit.decls()) {
			if (d instanceof InterfaceBlock b && "u_Globals".equals(b.name()) && isUniform(b)) {
				List<Std140.Member> members = new ArrayList<>();
				for (var m : b.members()) {
					for (Declarator name : m.declarators()) {
						String type = typeWord(m.type());
						if (type == null) {
							problems.add("field " + name.name() + " has unsupported type");
							continue;
						}
						members.add(new Std140.Member(name.name(), type));
					}
				}
				if (members.isEmpty()) {
					problems.add("the block declares no usable field");
				}
				ProgramRequest.UniformBlock block = Std140.block("u_Globals", 0, members);
				for (String need : REQUIRED) {
					if (block.fields().stream().noneMatch(f -> f.name().equals(need))) {
					problems.add("the block lacks " + need + ", which the terrain prologue needs");
					}
				}
				return new SodiumGlobals(block, problems);
			}
		}
		problems.add("no uniform block named u_Globals");
		return new SodiumGlobals(emptyBlock(), problems);
	}

	/**
	 * Reads and parses Sodium's own {@code globals.glsl} from the installed Sodium jar (ADR 0007 decision 3:
	 * {@code getModContainer("sodium").findPath}, so a resource pack cannot change the answer). Null when Sodium's
	 * container or the file is absent.
	 */
	public static @Nullable SodiumGlobals read() {
		return FabricLoader.getInstance().getModContainer("sodium").flatMap(container -> {
			try {
				return Optional.of(parse(java.nio.file.Files.readString(
					container.findPath("assets/sodium/shaders/include/globals.glsl").orElseThrow(),
					java.nio.charset.StandardCharsets.UTF_8)));
			} catch (IOException | java.util.NoSuchElementException e) {
				return Optional.<SodiumGlobals>empty();
			}
		}).orElse(null);
	}

	/** The byte offset of a field, or -1 when the block does not declare it. */
	public long offsetOf(String field) {
		return block.fields().stream().filter(f -> f.name().equals(field)).findAny()
			.map(f -> (long) f.offset()).orElse(-1L);
	}

	/** The byte offset of {@code u_CameraOffset} (beta.5), or -1 on a release that does not declare it (beta.1). */
	public long cameraOffsetAt() {
		return offsetOf("u_CameraOffset");
	}

	/**
	 * The block's exact end in bytes, what Sodium's writer fills: the last member's offset plus its payload, rounded
	 * up to four. Sodium's block does not tail-pad to the vec4 alignment (the beta.1 block ends at 184, the beta.5
	 * block at 200 — the values of ADR 0007's Context), which is why this is not the engine's own padded size.
	 */
	public int size() {
		if (block.fields().isEmpty()) {
			return 0;
		}
		dev.mcms.glsl.msl.ProgramRequest.UniformField last = block.fields().get(block.fields().size() - 1);
		return last.offset() + Std140.size(last.type());
	}

	private static boolean isUniform(InterfaceBlock b) {
		for (Qualifier q : b.qualifiers()) {
			if (q instanceof StorageQual s && s.kind() == StorageKind.UNIFORM) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable String typeWord(Type type) {
		if (type.spec() instanceof NameTypeSpec n) {
			return n.name();
		}
		if (type.spec() instanceof BuiltinTypeSpec b) {
			return b.name();
		}
		return null;
	}

	private static ProgramRequest.UniformBlock emptyBlock() {
		return Std140.block("u_Globals", 0, List.of());
	}
}
