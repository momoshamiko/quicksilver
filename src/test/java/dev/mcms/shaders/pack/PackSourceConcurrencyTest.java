// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two threads read one folder pack through case-only fallbacks; warnings stay one per path and nothing throws. */
class PackSourceConcurrencyTest {
	@TempDir
	Path temp;

	@Test
	void concurrentCaseFallbackReadsKeepOneWarningPerPath() throws Exception {
		Path pack = temp.resolve("Smoke");
		Files.createDirectories(pack.resolve("shaders/Lib"));
		Files.writeString(pack.resolve("shaders/Lib/Common.glsl"), "uniform float foo;\n");
		Files.writeString(pack.resolve("shaders/Other.fsh"), "void main() {}\n");
		try (PackSource s = PackSource.open(pack)) {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			CountDownLatch start = new CountDownLatch(1);
			List<Thread> threads = new ArrayList<>();
			for (int t = 0; t < 2; t++) {
				Thread th = new Thread(() -> {
					try {
						start.await();
						for (int i = 0; i < 2000; i++) {
							s.readText("lib/common.glsl");
							s.list("lib");
							s.exists("other.fsh");
							s.warnings();
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				});
				threads.add(th);
				th.start();
			}
			start.countDown();
			for (Thread th : threads) th.join();
			assertNull(failure.get(), () -> "reader thread failed: " + failure.get());
			// Two distinct paths: the file "lib/common.glsl" and the folder "lib" (both matched by case only), one each.
			assertEquals(2, s.warnings().size());
			assertEquals(List.of("Common.glsl"), s.list("lib"));
		}
	}
}
