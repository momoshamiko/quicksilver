// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code DH_BLOCK_*} defines a pack compares {@code dhMaterialId} with are the material indices Distant Horizons writes into
 * each vertex (plan.md E7, DH decision 12): Complementary's {@code dh_water} tests {@code mat == DH_BLOCK_WATER}, and a define off
 * by one skips the water material for every DH water vertex.
 */
class DistantBlockIdsTest {

	@Test
	void everyDistantBlockDefineIsTheMaterialIndexDistantHorizonsWrites() {
		Map<String, String> defines = new HashMap<>();
		for (StandardMacros.Macro macro : StandardMacros.engine(true).macros()) {
			if (macro.name().startsWith("DH_BLOCK_")) {
				defines.put(macro.name(), macro.value());
			}
		}
		assertEquals(EDhApiBlockMaterial.values().length, defines.size(), defines::toString);
		for (EDhApiBlockMaterial material : EDhApiBlockMaterial.values()) {
			assertEquals(Integer.toString(material.index), defines.get("DH_BLOCK_" + material.name()), material.name());
		}
	}
}
