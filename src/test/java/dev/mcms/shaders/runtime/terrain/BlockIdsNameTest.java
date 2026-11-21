// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.junit.jupiter.api.Test;

/**
 * The text a {@code block.properties} predicate compares with (E4-B review item 9): the vanilla state name from
 * {@code Property.getName}. Eight vanilla enums have no {@code toString} override, so their constant names would never
 * match ({@code face=floor}, {@code type=left}).
 */
class BlockIdsNameTest {

	@Test
	void aPropertyEnumIsNamedByItsStateNameNotItsConstant() {
		EnumProperty<AttachFace> face = EnumProperty.create("face", AttachFace.class);
		assertEquals("floor", BlockIds.valueName(face, AttachFace.FLOOR));
		EnumProperty<ChestType> type = EnumProperty.create("type", ChestType.class);
		assertEquals("left", BlockIds.valueName(type, ChestType.LEFT));
	}

	@Test
	void aBooleanPropertyIsNamedTrueOrFalse() {
		assertEquals("true", BlockIds.valueName(BooleanProperty.create("waterlogged"), true));
	}
}
