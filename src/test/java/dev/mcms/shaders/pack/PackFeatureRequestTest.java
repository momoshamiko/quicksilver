// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack defines an IRIS_FEATURE_ flag only when it requests the flag, and a required flag the engine lacks refuses it. */
class PackFeatureRequestTest {
	@TempDir
	Path dir;

	private PackModel load(String propertiesText) throws IOException {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), propertiesText);
		Files.writeString(shaders.resolve("final.fsh"), "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		try (PackSource pack = PackSource.open(dir)) {
			return PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	@Test
	void aPackDefinesOnlyTheFlagsItRequestsAndTheEngineProvides() throws IOException {
		PackModel model = load("iris.features.optional=SSBO CAN_DISABLE_WEATHER\n");
		assertTrue(model.macros().isDefined("IRIS_FEATURE_SSBO"), "requested and provided");
		assertFalse(model.macros().isDefined("IRIS_FEATURE_CAN_DISABLE_WEATHER"), "requested but not provided");
		assertFalse(model.macros().isDefined("IRIS_FEATURE_COMPUTE_SHADERS"), "provided but not requested");
		assertTrue(model.unsupportedRequiredFeatures().isEmpty(), "optional flags never refuse a pack");
	}

	@Test
	void aPackThatRequestsNoFlagDefinesNone() throws IOException {
		PackModel model = load("blend.final=off\n");
		for (String f : StandardMacros.ENGINE_FEATURES) {
			assertFalse(model.macros().isDefined("IRIS_FEATURE_" + f), f + " is defined without a request");
		}
	}

	@Test
	void aRequiredFlagTheEngineLacksIsReportedAsRefusal() throws IOException {
		PackModel model = load("iris.features.required=CAN_DISABLE_WEATHER SSBO\n");
		assertEquals(List.of("CAN_DISABLE_WEATHER"), model.unsupportedRequiredFeatures());
		assertTrue(model.macros().isDefined("IRIS_FEATURE_SSBO"), "a required flag the engine provides is defined");
	}

	@Test
	void shadersPropertiesSeesTheFlagsItsOwnRequestsDefine() throws IOException {
		String text = "iris.features.optional=SSBO\n#if defined IRIS_FEATURE_SSBO\nclouds=off\n#endif\n";
		assertEquals(Optional.of(ShaderProperties.Clouds.OFF), load(text).properties().clouds(),
			"the directive sits under a flag the file requests");
		assertEquals(Optional.empty(), load("#if defined IRIS_FEATURE_SSBO\nclouds=off\n#endif\n").properties().clouds(),
			"without the request the flag is not defined");
	}
}
