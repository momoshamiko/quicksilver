// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.renderpearl.api.commands.RenderPass;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shadow pass's close pops the game's open debug groups first (E5 review item 13): the frontend refuses a close with
 * groups open, and the count is read without an exception on a steady frame. When the count is unknown (E5 review item 3),
 * the groups are popped until the frontend refuses, and the pass is closed on every path.
 */
class ShadowPassCloseTest {

	/**
	 * A render pass with the frontend's debug-group rule: a pop with none open throws, and a close with groups open throws
	 * (FrontendRenderPass, javap 26.3). Every call is recorded in order.
	 */
	private static final class StandInPass {
		final List<String> calls = new ArrayList<>();
		int open;
		final RenderPass pass;

		StandInPass() {
			this.pass = (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[] {RenderPass.class},
				(proxy, method, args) -> handle(proxy, method, args));
		}

		private Object handle(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "pushDebugGroup" -> this.open++;
				case "popDebugGroup" -> {
					if (this.open == 0) {
						throw new IllegalStateException("Can't pop more debug groups than was pushed!");
					}
					this.open--;
					this.calls.add("pop");
				}
				case "close" -> {
					if (this.open > 0) {
						throw new IllegalStateException("Render pass had debug groups left open!");
					}
					this.calls.add("close");
				}
				case "toString" -> {
					return "stand-in pass";
				}
				case "hashCode" -> {
					return System.identityHashCode(proxy);
				}
				case "equals" -> {
					return proxy == args[0];
				}
				default -> throw new UnsupportedOperationException(method.getName());
			}
			return null;
		}
	}

	/** A render pass whose pops throw {@code popFailure}; its close is recorded in {@code calls}. */
	private static RenderPass recordingPass(List<String> calls, RuntimeException popFailure) {
		return (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[] {RenderPass.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "popDebugGroup" -> throw popFailure;
					case "close" -> calls.add("close");
					case "toString" -> {
						return "recording pass";
					}
					case "hashCode" -> {
						return System.identityHashCode(proxy);
					}
					case "equals" -> {
						return proxy == args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				}
				return null;
			});
	}

	@Test
	void aPassWithNoGroupsOpenIsClosedWithoutAnyPop() {
		StandInPass stand = new StandInPass();

		ShadowPassOps.closeWithOpenGroups(stand.pass, pass -> stand.open);

		assertEquals(List.of("close"), stand.calls, "no pop and no refused close on a steady frame");
	}

	@Test
	void theOpenGroupsArePoppedBeforeTheClose() {
		StandInPass stand = new StandInPass();
		stand.pass.pushDebugGroup(() -> "game group a");
		stand.pass.pushDebugGroup(() -> "game group b");

		ShadowPassOps.closeWithOpenGroups(stand.pass, pass -> stand.open);

		assertEquals(List.of("pop", "pop", "close"), stand.calls);
		assertEquals(0, stand.open);
	}

	@Test
	void anUncountedPassPopsUntilTheFrontendRefusesThenCloses() {
		StandInPass stand = new StandInPass();
		stand.pass.pushDebugGroup(() -> "game group a");
		stand.pass.pushDebugGroup(() -> "game group b");

		ShadowPassOps.closeWithOpenGroups(stand.pass, pass -> ShadowPassOps.UNKNOWN_GROUPS);

		assertEquals(List.of("pop", "pop", "close"), stand.calls, "the third pop is refused, then the pass closes");
		assertEquals(0, stand.open, "no group is left open when the pass closes");
	}

	@Test
	void anUncountedPassWithNoGroupsOpenStillClosesOnce() {
		StandInPass stand = new StandInPass();

		ShadowPassOps.closeWithOpenGroups(stand.pass, pass -> ShadowPassOps.UNKNOWN_GROUPS);

		assertEquals(List.of("close"), stand.calls, "the first pop is refused at once, and the pass closes once");
	}

	@Test
	void aPassThatIsNotAFrontendPassHasAnUnknownCount() {
		StandInPass stand = new StandInPass();

		assertEquals(ShadowPassOps.UNKNOWN_GROUPS, ShadowPassOps.openDebugGroups(stand.pass),
			"a pass of another type is not read through the frontend's counter");
	}

	@Test
	void aPassWhosePopFailsStillCloses() {
		List<String> calls = new ArrayList<>();
		RenderPass pass = recordingPass(calls, new IllegalArgumentException("pop failed"));

		assertThrows(IllegalArgumentException.class, () -> ShadowPassOps.closeWithOpenGroups(pass, p -> 1));

		assertEquals(List.of("close"), calls, "the pass is closed even when a pop throws, so it is never left open");
	}
}
