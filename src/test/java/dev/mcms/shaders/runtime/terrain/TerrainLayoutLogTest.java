// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * A globals block that fails the engine's check: the layout registration logs nothing for it. The engine's frame start
 * prints the refusal once (the set's one ERROR, ADR 0007), so one failure prints one line. No other test in this JVM
 * registers the layout, so the registration runs here.
 */
class TerrainLayoutLogTest {

	@Test
	void aGlobalsFailureLogsNothingFromTheLayoutRegistration() {
		Logger log = Logger.getLogger(TerrainEngine.class.getName());
		List<LogRecord> records = new ArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		log.addHandler(capture);
		try {
			TerrainEngine.registerLayout(null);  // no globals block: the globals check fails
		} finally {
			log.removeHandler(capture);
		}
		long severe = records.stream().filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()).count();
		assertEquals(0, severe, "the layout logs no ERROR: the engine's frame start logs the one refusal");
	}
}
