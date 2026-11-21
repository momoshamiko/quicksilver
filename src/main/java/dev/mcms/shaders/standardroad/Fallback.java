// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import dev.mcms.glsl.msl.ProgramRequest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime's fallback (ADR 0006 section 4): when Metal rejects the translator's MSL for a program, the runtime asks
 * for the standard road's stages once. The answer is the stages' MSL or the reason there is none. One WARN line per
 * pack and program name, whatever the answer, so a pack that trips the fallback every frame logs once; the runtime
 * calls {@link #packChanged()} when the pack changes, and the new pack's programs warn again. Nothing here throws:
 * any failure of the road itself is the refused answer.
 */
public final class Fallback {

	/** The standard road's stages, or the reason there are none (the program is then absent, as ADR 0006 section 4 says). */
	public record Result(boolean ok, String reason, StandardRoad.Program program) {
	}

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private Fallback() {
	}

	/**
	 * Compiles the program through the standard road. {@code rejection} is Metal's message for the translator's MSL; it
	 * goes into the WARN line with the answer.
	 */
	public static Result standardRoad(String pack, ProgramRequest request, String rejection) {
		String key = pack + "/" + nameOf(request);
		Result result;
		try {
			StandardRoad.Program program = StandardRoad.compile(request);
			result = program.ok() ? new Result(true, null, program)
				: new Result(false, String.join("; ", program.errors()), program);
		} catch (Throwable t) {
			result = new Result(false, "standard road failed: " + StandardRoad.describe(t), null);
		}
		if (WARNED.add(key)) {
			LOG.warn("[mcms-shaders] pack {} program {} rejected by Metal ({}); standard road: {}", pack,
				nameOf(request), rejection, result.ok() ? "used" : "refused: " + result.reason());
		}
		return result;
	}

	/** The runtime calls this when the pack changes: the new pack's programs warn again. */
	public static void packChanged() {
		WARNED.clear();
	}

	/** Whether the WARN line for one program of one pack was emitted (for tests). */
	static boolean warned(String pack, String program) {
		return WARNED.contains(pack + "/" + program);
	}

	private static String nameOf(ProgramRequest request) {
		try {
			return request == null ? "unknown" : request.name();
		} catch (Throwable t) {
			return "unknown";
		}
	}
}
