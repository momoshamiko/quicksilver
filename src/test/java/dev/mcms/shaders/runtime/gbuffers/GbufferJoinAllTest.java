// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A plan-build round joins every worker before it throws, so the caller may release what the workers used at once
 * (E4 review item 2: a failed worker let the build free a library another worker was still compiling into).
 */
class GbufferJoinAllTest {

	@Test
	void aRoundThrowsTheFirstFailureOnlyAfterEveryWorkerHasFinished() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			AtomicInteger finished = new AtomicInteger();
			List<Future<Integer>> wave = new ArrayList<>();
			wave.add(pool.submit(failing("first", finished)));
			wave.add(pool.submit(failing("second", finished)));
			for (int i = 0; i < 3; i++) {
				wave.add(pool.submit(slow(finished)));
			}
			IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> GbufferPlans.joinAll(wave));
			assertEquals("first", thrown.getMessage(), "the first failure in wave order is the one thrown");
			assertEquals(1, thrown.getSuppressed().length, "the later failure is kept as suppressed");
			assertEquals("second", thrown.getSuppressed()[0].getMessage());
			assertEquals(5, finished.get(), "every worker of the round finished before the throw");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aRoundWithoutFailuresReturnsOnceAllItsWorkersFinished() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			AtomicInteger finished = new AtomicInteger();
			List<Future<Integer>> wave = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				wave.add(pool.submit(slow(finished)));
			}
			assertDoesNotThrow(() -> GbufferPlans.joinAll(wave));
			assertEquals(3, finished.get());
		} finally {
			pool.shutdownNow();
		}
	}

	private static Callable<Integer> failing(String message, AtomicInteger finished) {
		return () -> {
			finished.incrementAndGet();
			throw new IllegalStateException(message);
		};
	}

	/** A worker that takes a while, so a round that threw early would leave it running. */
	private static Callable<Integer> slow(AtomicInteger finished) {
		return () -> {
			Thread.sleep(300);
			return finished.incrementAndGet();
		};
	}
}
