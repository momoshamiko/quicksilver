/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-13, M5).
 */
package dev.mcms.metal;

/**
 * Spec S4 (stability): -Dmcms.metal.memLog=SECONDS logs, every SECONDS from the device's creation, the process's resident memory
 * (RSS), its physical footprint and Metal's currentAllocatedSize, with the count of command buffers the GPU failed so far
 * (mcmetal.m cbWatch) and the Metal objects of each kind that still exist (mcmetal.m mc_track_*, count/bytes; views, texel
 * views and pipelines count 0 bytes):
 * {@code mcms-metal mem: t=<epoch ms> rss=<bytes> footprint=<bytes> metal=<bytes> gpuErrors=<n> objects=shared:<n>/<bytes>,...}.
 * bench/soak_check.py reads these lines from the game log. A daemon thread at minimum priority that reads counters only; the
 * native context it reads lives until the process exits (MetalDevice.close does not free it).
 */
final class MetalHealth {
	/** mcmetal.m's TK_* order. */
	private static final String[] KINDS = {"shared", "private", "texture", "view", "texel", "arena", "pipeline"};

	private MetalHealth() {
	}

	static void start(long ctx) {
		double seconds = Double.parseDouble(System.getProperty("mcms.metal.memLog", "0"));
		if (seconds <= 0) return;
		long periodMs = Math.max(1, Math.round(seconds * 1000));
		Native.trackOn();
		Thread thread = new Thread(() -> {
			while (true) {
				StringBuilder line = new StringBuilder("mcms-metal mem: t=").append(System.currentTimeMillis()).append(" rss=").append(Native.memResident())
					.append(" footprint=").append(Native.memFootprint()).append(" metal=").append(Native.memMetal(ctx)).append(" gpuErrors=")
					.append(Native.cbErrors()).append(" objects=");
				for (int k = 0; k < KINDS.length; k++) {
					line.append(k == 0 ? "" : ",").append(KINDS[k]).append(':').append(Native.trackCount(k)).append('/').append(Native.trackBytes(k));
				}
				// println, not printf: Minecraft's stdout logger keeps println lines only
				System.out.println(line);
				try {
					Thread.sleep(periodMs);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "mcms-metal-memlog");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
		System.out.println("mcms-metal memLog: on, every " + seconds + " s");
	}
}
