// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.util.HashMap;
import java.util.Map;

/** A scope for tests: names bound to frame slots or constants; load-time only, so a map is fine here. */
final class TestScope implements ExprScope {
	final ExprFrame frame = new ExprFrame();
	private final Map<String, ExprBinding> names = new HashMap<>();
	private int slots;

	static TestScope standard() {
		TestScope s = new TestScope();
		s.bind("frameTimeCounter", ExprType.FLOAT);
		s.bind("sunAngle", ExprType.FLOAT);
		s.bind("temperature", ExprType.FLOAT);
		s.bind("rainfall", ExprType.FLOAT);
		s.bind("biome", ExprType.INT);
		s.bind("heldItemId", ExprType.INT);
		s.bind("worldTime", ExprType.INT);
		s.bind("is_sprinting", ExprType.BOOL);
		s.bind("is_hurt", ExprType.BOOL);
		s.bind("cameraPosition", ExprType.VEC3);
		s.bind("skyColor", ExprType.VEC3);
		s.bind("eyeBrightnessSmooth", ExprType.VEC2);
		s.bind("gbufferModelView", ExprType.MAT4);
		s.bind("normalMatrix", ExprType.MAT3);
		s.constantInt("BIOME_RIVER", 7);
		s.constantInt("BIOME_FOREST", 45);
		s.constantInt("PPT_NONE", 0);
		s.constantInt("PPT_RAIN", 1);
		s.constantInt("PPT_SNOW", 2);
		s.done();
		return s;
	}

	TestScope bind(String name, ExprType type) {
		names.put(name, new ExprBinding.Frame(slots, type));
		slots++;
		return this;
	}

	TestScope constantInt(String name, int value) {
		names.put(name, new ExprBinding.ConstInt(value));
		return this;
	}

	TestScope constantFloat(String name, float value) {
		names.put(name, new ExprBinding.ConstFloat(value));
		return this;
	}

	TestScope constantBool(String name, boolean value) {
		names.put(name, new ExprBinding.ConstBool(value));
		return this;
	}

	int slotOf(String name) {
		return ((ExprBinding.Frame) names.get(name)).slot();
	}

	void done() {
		frame.ensureSlots(slots);
	}

	@Override
	public ExprBinding resolve(String name) {
		return names.get(name);
	}
}
