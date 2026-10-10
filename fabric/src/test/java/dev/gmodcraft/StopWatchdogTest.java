package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The server stop watchdog: threshold parsing, the dump, and that a finished stop cancels it. */
class StopWatchdogTest {
	@Test
	void thresholdParsing() {
		assertEquals(30, StopWatchdog.thresholdSeconds(null, null));
		assertEquals(30, StopWatchdog.thresholdSeconds("", " "));
		assertEquals(5, StopWatchdog.thresholdSeconds("5", "60"));
		assertEquals(60, StopWatchdog.thresholdSeconds(null, "60"));
		assertEquals(0, StopWatchdog.thresholdSeconds("0", null));
		assertEquals(0, StopWatchdog.thresholdSeconds("-3", null));
		assertEquals(30, StopWatchdog.thresholdSeconds("soon", null));
	}

	@Test
	void dumpListsThreadsWithFullStacks() {
		String d = StopWatchdog.dumpThreads();
		assertTrue(d.contains('"' + Thread.currentThread().getName() + '"'), d);
		assertTrue(d.contains("StopWatchdogTest.dumpListsThreadsWithFullStacks"), "the calling frame is in the dump");
	}

	@Test
	void slowStopDumpsRepeatedlyUntilInterrupted() throws Exception {
		List<String> out = new CopyOnWriteArrayList<>();
		Thread t = StopWatchdog.start(50, 50, 3, out::add);
		t.join(5000);
		assertFalse(t.isAlive());
		assertEquals(3, out.size());
		assertTrue(out.get(0).contains("still running after") && out.get(0).contains("dump 1 of at most 3"), out.get(0));
		assertTrue(out.get(0).contains("\"GmodCraft stop watchdog\""), "the dump includes all threads");
	}

	@Test
	void finishedStopDumpsNothing() throws Exception {
		List<String> out = new CopyOnWriteArrayList<>();
		Thread t = StopWatchdog.start(2000, 1000, 3, out::add);
		assertTrue(t.isDaemon());
		t.interrupt();
		t.join(5000);
		assertFalse(t.isAlive());
		assertTrue(out.isEmpty());
	}
}
