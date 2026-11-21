// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramTranslation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sparse fragment outputs (the E3 review): a {@code DRAWBUFFERS:012} program whose fragment stage writes only
 * attachments 0 and 2 keeps target 2 on attachment 2 and leaves attachment 1 (and its target) untouched — the
 * layout comes from the reflection's attachments, not from truncating the request's first outputs. The reflection
 * reports the attachments a stage writes; the E2 fix (fragment outputs take their listed target from the location)
 * makes that set sparse for named outputs too, and this plan handles either form.
 */
class AttachmentPlanTest {
	@Test
	void theAttachmentPlanKeepsSparseSlotsAndLeavesTheGapAlone() {
		List<ColorTarget> outputs = List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4), new ColorTarget(2, 2, 4));
		// DRAWBUFFERS:012, the fragment stage writes locations 0 and 2: attachments 0 and 2, 1 stays empty.
		List<ProgramTranslation.Reflection.OutputUse> written = List.of(output(0), output(2));
		assertArrayEquals(new int[] {0, 2}, DimensionSet.attachmentPlan(outputs, written));
		// A fragment stage that reports nothing falls back to attachment 0 (gl_FragColor's documented target).
		assertArrayEquals(new int[] {0}, DimensionSet.attachmentPlan(outputs, List.of()));
	}

	private static ProgramTranslation.Reflection.OutputUse output(int attachment) {
		return new ProgramTranslation.Reflection.OutputUse("out" + attachment, attachment, attachment, 4);
	}
}
