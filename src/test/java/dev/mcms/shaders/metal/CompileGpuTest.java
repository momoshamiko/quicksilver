// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W1-C: the headless device compiles MSL 3.1 and reports Metal's diagnostics and the entry points. */
@Tag("gpu")
final class CompileGpuTest {
	@Test
	void listsTheFunctionsOfAValidSource() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			HeadlessGpu.CompileResult ok = gpu.compile("""
				#include <metal_stdlib>
				using namespace metal;

				float helper(float x) { return x * 0.5f; }

				vertex float4 vs_entry(uint vid [[vertex_id]]) { return float4(0.0f, 0.0f, 0.0f, 1.0f); }

				fragment float4 fs_entry(float4 p [[position]]) { return float4(helper(p.x), 0.0f, 0.0f, 1.0f); }

				kernel void cs_entry(device float *out [[buffer(0)]], uint gid [[thread_position_in_grid]]) { out[gid] = 1.0f; }
				""");
			assertTrue(ok.ok(), () -> "valid source failed: " + ok.diagnostics());
			assertTrue(ok.functions().contains("vs_entry"));
			assertTrue(ok.functions().contains("fs_entry"));
			assertTrue(ok.functions().contains("cs_entry"));
		}
	}

	@Test
	void brokenSourceReturnsMetalsDiagnostics() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			HeadlessGpu.CompileResult bad = gpu.compile("""
				#include <metal_stdlib>
				using namespace metal;
				kernel void broken(device float *out [[buffer(0)]], uint gid [[thread_position_in_grid]]) {
					int2 wrong = float2(1.0f);  // no implicit vector truncation in MSL
					out[gid] = wrong.x;
				}
				""");
			assertFalse(bad.ok());
			assertTrue(bad.diagnostics() != null && bad.diagnostics().contains("error"),
				() -> "Metal's message should name the error: " + bad.diagnostics());
			assertTrue(bad.functions().isEmpty());
		}
	}
}
