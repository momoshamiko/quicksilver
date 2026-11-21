// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

/**
 * The unbound-sampler warning (E6-fix-F, review item 6): a sampler name that one program's custom texture serves must not hide
 * a true unbound case of the same name in another program. Each name logs once per set, for the program that first lacks it.
 */
class GbufferUnboundWarningTest {

	/** Bindings that serve sampler {@code foo} of gbuffers_basic with custom texture 3, and nothing else. */
	private static GbufferDelegate.Bindings bindings() {
		return (GbufferDelegate.Bindings) Proxy.newProxyInstance(GbufferDelegate.Bindings.class.getClassLoader(),
			new Class<?>[] {GbufferDelegate.Bindings.class}, (proxy, method, args) -> {
				if (method.getName().equals("customId")) {
					return "gbuffers_basic".equals(args[0]) && "foo".equals(args[1]) ? 3 : -1;
				}
				Class<?> r = method.getReturnType();
				if (r == long.class) {
					return 0L;
				}
				if (r == int.class) {
					return 0;
				}
				if (r == boolean.class) {
					return false;
				}
				return null;
			});
	}

	/** A pack variant of one program that declares the named samplers. */
	private static GbufferPlans.PackVariant variant(String program, String... samplers) {
		List<ProgramRequest.TextureBinding> textures = new ArrayList<>();
		for (int i = 0; i < samplers.length; i++) {
			textures.add(new ProgramRequest.TextureBinding(samplers[i], i, ProgramRequest.TextureKind.COLOUR, -1, null, -1, null));
		}
		return (GbufferPlans.PackVariant) Proxy.newProxyInstance(GbufferPlans.PackVariant.class.getClassLoader(),
			new Class<?>[] {GbufferPlans.PackVariant.class}, (proxy, method, args) -> switch (method.getName()) {
				case "program" -> program;
				case "textures" -> textures;
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	@Test
	void aNameServedInOneProgramStillLogsOnceInTheProgramThatLacksIt() {
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTestLayouts(Map.of()), bindings());
		List<String> messages = captureWarnings(() -> {
			delegate.noteUnbound(variant("gbuffers_basic", "foo"));  // served by the custom texture: nothing logged
			delegate.noteUnbound(variant("shadow", "foo"));           // unbound here: logged
			delegate.noteUnbound(variant("shadow", "foo"));           // logged once per set: nothing more
		});
		assertEquals(1, messages.size(), "one warning: " + messages);
		assertTrue(messages.get(0).startsWith("Quicksilver: shadow declares sampler foo:"), messages.get(0));
	}

	/** The warnings the Quicksilver logger writes while the action runs (log4j, as QuicksilverSettingsShadersTest captures them). */
	private static List<String> captureWarnings(Runnable action) {
		Logger logger = (Logger) LogManager.getLogger("Quicksilver");
		List<String> messages = new ArrayList<>();
		AbstractAppender appender = new AbstractAppender("mcms-unbound-test", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(org.apache.logging.log4j.core.LogEvent event) {
				messages.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
		return messages;
	}
}
