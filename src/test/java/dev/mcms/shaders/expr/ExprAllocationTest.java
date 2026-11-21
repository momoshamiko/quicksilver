// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.lang.management.ManagementFactory;



import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A uniform set the size of a real pack's evaluates without allocating: per-thread allocated bytes do not move across
 * 10,000 frames (the brief's allocation-free rule for the hot path). Up to five windows are measured and one clean window
 * passes: a JIT recompilation or deoptimization inside a window can allocate on this thread for a moment (seen in about
 * one full-suite run in five, 2026-11-14/15), while an allocation in the evaluator itself repeats every frame, so it shows
 * in every window and still fails.
 */
class ExprAllocationTest {
	@Test
	void uniformSetEvaluatesWithoutAllocating() {
		TestScope scope = TestScope.standard();
		String[] texts = {
			"frameTimeCounter * 2.0 + 1.0",
			"sin(frameTimeCounter) * cos(frameTimeCounter * 0.5)",
			"max(frameTimeCounter, sunAngle, temperature)",
			"min(frameTimeCounter, 1.0)",
			"clamp(sunAngle, 0.0, 1.0)",
			"abs(sunAngle - 0.5)",
			"floor(sunAngle * 8.0)",
			"ceil(sunAngle * 8.0)",
			"exp(sunAngle * 0.5)",
			"frac(sunAngle * 3.0)",
			"log(sunAngle + 1.0)",
			"pow(1.0 + sunAngle, 2.0)",
			"sqrt(sunAngle + 0.25)",
			"fmod(sunAngle * 10.0, 3.0)",
			"lerp(0.5, sunAngle, temperature)",
			"if(sunAngle < 0.5, 1.0, 2.0)",
			"if(sunAngle < 0.25, 1, sunAngle < 0.75, 2, 3)",
			"between(sunAngle, 0.0, 1.0)",
			"equals(sunAngle, 0.5, 0.1)",
			"in(biome, BIOME_RIVER, BIOME_FOREST)",
			"biome + heldItemId * 2",
			"biome % 7",
			"heldItemId == 276",
			"is_sprinting && !is_hurt",
			"smooth(1, if(is_sprinting, 1.0, 0.0), 5)",
			"smooth(2, if(is_hurt, 1.3, 0), 0, 10)",
			"random()",
			"random(2.0, 5.0)",
			"cameraPosition.y + skyColor.r",
			"gbufferModelView.0.1 * 2.0",
			"vec3(frameTimeCounter, sunAngle, temperature) * 2.0",
			"min(vec2(sunAngle, temperature), 0.5)",
			"clamp(vec3(sunAngle, temperature, rainfall), 0.0, 1.0)",
			"abs(vec2(0.0 - sunAngle, rainfall))",
			"smooth(3, vec2(sunAngle, temperature), 1, 2)",
			"vec4(biome, heldItemId, worldTime, 0)",
			"print(1, 60, sunAngle)",
		};
		assertEquals(37, texts.length);
		CompiledExpr[] set = new CompiledExpr[texts.length];
		int scratch = 1;
		for (int k = 0; k < texts.length; k++) {
			CompiledExpr c = ExprCompiler.compile(texts[k], scope);
			set[k] = c;
			scratch = Math.max(scratch, c.vectorScratch());
		}
		ExprFrame frame = scope.frame;
		frame.setFloat(0, 12.5f);
		frame.setFloat(1, 0.25f);
		frame.setFloat(2, 0.7f);
		frame.setFloat(3, 0.4f);
		frame.setInt(4, 45);
		frame.setInt(5, 276);
		frame.setInt(6, 6000);
		frame.setBool(7, true);
		frame.setBool(8, false);
		frame.setVec3(9, 10f, 20f, 30f);
		frame.setVec3(10, 0.1f, 0.2f, 0.3f);
		frame.setVec2(11, 120f, 80f);
		float[] mv = new float[16];
		frame.setMat4(12, mv);
		ExprEval eval = new ExprEval(frame, scratch);
		eval.print = null;
		float[] vecOut = new float[4];
		for (int warm = 0; warm < 3000; warm++) {
			eval.deltaTime = 1f / 60f;
			eval.timeSeconds = warm * (1f / 60f);
			runSet(set, eval, vecOut);
		}
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long tid = Thread.currentThread().threadId();
		long[] windows = new long[5];
		int measured = 0;
		while (measured < windows.length) {
			long before = mx.getThreadAllocatedBytes(tid);
			for (int frameNo = 0; frameNo < 10000; frameNo++) {
				eval.deltaTime = 1f / 60f;
				eval.timeSeconds = frameNo * (1f / 60f);
				runSet(set, eval, vecOut);
			}
			long after = mx.getThreadAllocatedBytes(tid);
			assertTrue(after >= before);
			windows[measured++] = after - before;
			if (after == before) {
				return;
			}
		}
		assertEquals(0L, java.util.Arrays.stream(windows).min().orElseThrow(),
			"the uniform set allocated in every window of 10,000 frames (bytes per window): " + java.util.Arrays.toString(windows));
	}

	private static void runSet(CompiledExpr[] set, ExprEval eval, float[] vecOut) {
		for (CompiledExpr c : set) {
			switch (c.type()) {
				case FLOAT -> c.evalF(eval);
				case INT -> c.evalI(eval);
				case BOOL -> c.evalB(eval);
				case VEC2, VEC3, VEC4 -> c.evalV(eval, vecOut, 0);
				case MAT3, MAT4 -> throw new AssertionError("unexpected matrix result");
			}
		}
	}
}
