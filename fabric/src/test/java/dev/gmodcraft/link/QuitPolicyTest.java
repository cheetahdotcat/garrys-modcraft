package dev.gmodcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.QuitPolicy.Host;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuitPolicyTest {
	@Test
	void neverWhileLinkedOrGModRuns() {
		assertFalse(QuitPolicy.shouldQuit(true, true, true, 999_999, Host.GONE, true), "linked");
		assertFalse(QuitPolicy.shouldQuit(true, true, false, 999_999, Host.ALIVE, true), "GMod still runs (menu, map change)");
		assertFalse(QuitPolicy.shouldQuit(false, true, false, 999_999, Host.GONE, true), "quitWithHost=false");
		assertFalse(QuitPolicy.shouldQuit(true, false, false, 999_999, Host.GONE, true), "never linked (separate rule)");
	}

	@Test
	void gmodGone() {
		assertFalse(QuitPolicy.shouldQuit(true, true, false, 14_000, Host.GONE, true));
		assertTrue(QuitPolicy.shouldQuit(true, true, false, 15_001, Host.GONE, true), "clean exit: 15 s");
		assertFalse(QuitPolicy.shouldQuit(true, true, false, 20_000, Host.GONE, false));
		assertTrue(QuitPolicy.shouldQuit(true, true, false, 30_001, Host.GONE, false), "crash (files left): 30 s");
	}

	@Test
	void unknownKeepsTheOldTimers() {
		assertFalse(QuitPolicy.shouldQuit(true, true, false, 59_000, Host.UNKNOWN, true));
		assertTrue(QuitPolicy.shouldQuit(true, true, false, 60_001, Host.UNKNOWN, true));
		assertFalse(QuitPolicy.shouldQuit(true, true, false, 119_000, Host.UNKNOWN, false));
		assertTrue(QuitPolicy.shouldQuit(true, true, false, 120_001, Host.UNKNOWN, false));
		assertEquals(-1, QuitPolicy.quitAfterMs(Host.ALIVE, true));
	}

	@Test
	void probeReadsComm(@TempDir Path proc) throws IOException {
		Files.createDirectories(proc.resolve("123"));
		Files.writeString(proc.resolve("123/comm"), "bash\n");
		Files.createDirectories(proc.resolve("self"));
		assertEquals(Host.GONE, QuitPolicy.probe(proc, Set.of("gmod")));
		Files.createDirectories(proc.resolve("456"));
		Files.writeString(proc.resolve("456/comm"), "gmod\n");
		assertEquals(Host.ALIVE, QuitPolicy.probe(proc, Set.of("gmod", "gmod_linux64")));
		assertEquals(Host.UNKNOWN, QuitPolicy.probe(proc.resolve("missing"), Set.of("gmod")));
		Files.writeString(proc.resolve("456/stat"), "456 (gmod) Z 1 456 456 0 -1\n");
		assertEquals(Host.GONE, QuitPolicy.probe(proc, Set.of("gmod")), "a zombie GMod isn't running");
		Files.writeString(proc.resolve("456/stat"), "456 (gmod) S 1 456 456 0 -1\n");
		assertEquals(Host.ALIVE, QuitPolicy.probe(proc, Set.of("gmod")));
	}

	@Test
	void hostNamesFromThePropertyToleratesDuplicates() {
		System.setProperty("gmodcraft.hostProcessNames", "a, a,b,,");
		try {
			assertEquals(Set.of("a", "b"), QuitPolicy.hostNames());
		} finally {
			System.clearProperty("gmodcraft.hostProcessNames");
		}
		assertEquals(QuitPolicy.DEFAULT_HOST_NAMES, QuitPolicy.hostNames());
	}
}
