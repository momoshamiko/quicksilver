// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.PassDelegate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The draw log's switch (2026-11-14, E4 diagnostics): off means nothing is logged, written, counted or recorded, and the
 * backend's encoder hook stays null; a changed value re-arms the log per scene, appending after the first block of a run.
 */
class DrawLogTest {

	@TempDir
	Path dir;

	private Path file;

	@BeforeEach
	void setUp() {
		System.clearProperty(DrawLog.PROPERTY);
		DrawLog.reset();
		this.file = this.dir.resolve("mcms-drawlog.txt");
		DrawLog.file(this.file);
	}

	@AfterEach
	void tearDown() {
		System.clearProperty(DrawLog.PROPERTY);
		DrawLog.reset();
	}

	@Test
	void switchOffDoesNothing() {
		for (int frame = 0; frame < 100; frame++) {
			DrawLog.frame(true);
		}
		assertFalse(DrawLog.on());
		DrawLog.noteDraw(PassDelegate.DRAW_INDEXED, 36, 1, 0, 0, 0);
		DrawLog.noteMulti(PassDelegate.MULTI, null, null, null, 4, 1, 0);
		DrawLog.notePush(ByteBuffer.allocate(20));
		DrawLog.flip(0, 1L, 2L);
		DrawLog.copy("load", 0, 1L, 2L);
		DrawLog.emit("never written");
		assertEquals(0, DrawLog.drawNumber());
		StringBuilder push = new StringBuilder();
		DrawLog.appendPush(push);
		assertEquals("none", push.toString());
		assertFalse(Files.exists(this.file));
	}

	@Test
	void switchOffInstallsNoEncoderHook() {
		for (int frame = 0; frame < 10; frame++) {
			DrawLog.frame(true);
		}
		assertNull(MetalHooks.encoderTrace);
		assertFalse(Files.exists(this.file));
	}

	@Test
	void aBlockInstallsTheEncoderHookAndTheEndRemovesIt() throws IOException {
		System.setProperty(DrawLog.PROPERTY, "1:open");
		DrawLog.frame(true);
		MetalHooks.EncoderTrace trace = MetalHooks.encoderTrace;
		assertNotNull(trace);
		trace.split("flushClearInPass", 0x10L);
		trace.restore("minecraft:pipeline/solid");
		DrawLog.flip(0, 0x1L, 0x2L);
		DrawLog.frame(true);  // the requested frame was written: the block ends
		assertNull(MetalHooks.encoderTrace);
		String text = Files.readString(this.file);
		assertTrue(text.contains("SPLIT f=1 p=0 why=flushClearInPass tex=0x10"), text);
		assertTrue(text.contains("RESTORE f=1 p=0 pipe=minecraft:pipeline/solid"), text);
		assertTrue(text.contains("FLIP f=1 p=0 colortex0 read=0x1 write=0x2"), text);
		assertTrue(text.contains("END scene=open"), text);
	}

	@Test
	void engineOffEndsAnOpenBlockAndRemovesTheEncoderHook() throws IOException {
		System.setProperty(DrawLog.PROPERTY, "5:open");
		DrawLog.frame(true);
		assertNotNull(MetalHooks.encoderTrace);
		DrawLog.stop();  // the engine turned off (Engine.turnOff): the frame hook is not called any more
		assertFalse(DrawLog.on());
		assertNull(MetalHooks.encoderTrace);
		assertTrue(Files.readString(this.file).contains("END scene=open"));
	}

	@Test
	void zeroFramesIsOff() {
		System.setProperty(DrawLog.PROPERTY, "0");
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		assertNull(MetalHooks.encoderTrace);
		assertFalse(Files.exists(this.file));
	}

	@Test
	void anInvalidValueIsOff() {
		System.setProperty(DrawLog.PROPERTY, "two:open");
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		assertFalse(Files.exists(this.file));
	}

	@Test
	void waitsForTheWorldBeforeLogging() {
		System.setProperty(DrawLog.PROPERTY, "2");
		for (int i = 0; i < 3; i++) {
			DrawLog.frame(false);
		}
		assertFalse(DrawLog.on());
		assertFalse(Files.exists(this.file));
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		assertTrue(Files.exists(this.file));
	}

	@Test
	void logsTheRequestedWorldFramesThenStops() throws IOException {
		System.setProperty(DrawLog.PROPERTY, "2");
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		DrawLog.noteDraw(PassDelegate.DRAW_INDEXED, 36, 1, 0, 0, 0);
		DrawLog.emit("draw one");
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		DrawLog.frame(true);  // the requested frames are written: the block ends here
		assertFalse(DrawLog.on());
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		assertEquals(1, DrawLog.drawNumber());
		String text = Files.readString(this.file);
		assertTrue(text.contains("FRAME 1 "), text);
		assertTrue(text.contains("FRAME 2 "), text);
		assertFalse(text.contains("FRAME 3"), text);
		assertTrue(text.contains("draw one"), text);
	}

	@Test
	void aChangedValueRearmsWithANewSceneBlockThatAppends() throws IOException {
		System.setProperty(DrawLog.PROPERTY, "1:open");
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		DrawLog.frame(true);  // the one requested frame is written: the block ends
		assertFalse(DrawLog.on());

		System.setProperty(DrawLog.PROPERTY, "1:objects");  // a new String object with a new value
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		DrawLog.frame(true);
		assertFalse(DrawLog.on());

		String text = Files.readString(this.file);
		assertTrue(text.contains("SCENE open frames=1"), text);
		assertTrue(text.contains("SCENE objects frames=1"), text);
		assertTrue(text.contains("FRAME 1 scene=open"), text);
		assertTrue(text.contains("FRAME 2 scene=objects"), text);
		assertTrue(text.indexOf("SCENE open") < text.indexOf("SCENE objects"), text);
	}

	@Test
	void theSameValueDoesNotRearm() {
		System.setProperty(DrawLog.PROPERTY, "1:open");
		DrawLog.frame(true);
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		System.setProperty(DrawLog.PROPERTY, new String("1:open"));  // equal text, another object: not a change
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
	}

	@Test
	void settingZeroEndsTheBlockInProgress() throws IOException {
		System.setProperty(DrawLog.PROPERTY, "5:open");
		DrawLog.frame(true);
		assertTrue(DrawLog.on());
		System.setProperty(DrawLog.PROPERTY, "0");
		DrawLog.frame(true);
		assertFalse(DrawLog.on());
		assertNull(MetalHooks.encoderTrace);
		assertTrue(Files.readString(this.file).contains("END scene=open"));
	}

	@Test
	void pushConstantsDecodeTheRegionOffset() {
		System.setProperty(DrawLog.PROPERTY, "1");
		DrawLog.frame(true);
		ByteBuffer push = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
		push.putFloat(1.5f).putFloat(-2f).putFloat(-48f).putInt(7).putInt(9);
		push.flip();
		DrawLog.notePush(push);
		assertEquals(0, push.position());
		StringBuilder sb = new StringBuilder();
		DrawLog.appendPush(sb);
		assertTrue(sb.toString().contains("region=(1.5000,-2.0000,-48.0000)"), sb.toString());
	}
}
