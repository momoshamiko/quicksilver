/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/Latency.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; -Dmcopt.metal.latency renamed to -Dmcms.metal.latency.
 */
package dev.mcms.metal;

/**
 * -Dmcms.metal.latency=true (with -Dmcms.metal.gpuTimes=true): the time of the input poll that starts each frame
 * (RenderSystem.pollEvents: keyboard, mouse buttons and the mouse movement the frame's camera turn uses), stored with that frame's
 * presenting command buffer, so a report can give input-sample -> commit -> GPU done -> presented per shown frame.
 */
public final class Latency {
	public static final boolean ON = Boolean.getBoolean("mcms.metal.latency");
	public static volatile long lastPollNs;

	private Latency() {
	}
}
