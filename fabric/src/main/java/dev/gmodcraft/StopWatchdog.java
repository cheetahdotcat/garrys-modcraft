package dev.gmodcraft;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.Nullable;

/**
 * Times every server stop and, when one takes long, dumps all threads to the log.
 *
 * Vanilla 26.3 logs nothing after "Saving chunks for level ..." until the JVM exits, so a slow stop
 * (a backlog of synchronous chunk writes on a slow disk, sync-chunk-writes=true) looks like a hang
 * in the log. This logs how long each stop took, and if one is still running after the threshold
 * (default 30 s; -Dgmodcraft.stopWatchdog=S or GMODCRAFT_STOP_WATCHDOG_S, 0 = off) a full thread
 * dump with lock owners, repeated every minute (at most {@link #MAX_DUMPS} times). A stop started by
 * SIGTERM/SIGINT (Ctrl-C) runs after log4j shut down: then both go to stderr instead.
 */
public final class StopWatchdog {
	static final long DEFAULT_SECONDS = 30;
	static final long REPEAT_MS = 60_000;
	static final int MAX_DUMPS = 4;

	private static volatile @Nullable Thread watchdog;
	private static volatile long stopStart;
	private static volatile boolean jvmExiting;

	private StopWatchdog() {
	}

	public static void init() {
		// A signal's stop runs in the JVM's shutdown hooks, next to log4j's own: from then on, stderr.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> jvmExiting = true, "GmodCraft stop watchdog hook"));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			stopStart = System.nanoTime();
			long s = thresholdSeconds(System.getProperty("gmodcraft.stopWatchdog"), System.getenv("GMODCRAFT_STOP_WATCHDOG_S"));
			if (s > 0) {
				watchdog = start(s * 1000, REPEAT_MS, MAX_DUMPS, msg -> emit(msg, true));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Thread t = watchdog;
			watchdog = null;
			if (t != null) {
				t.interrupt();
			}
			long start = stopStart;
			stopStart = 0;
			if (start != 0) { // STOPPED without STOPPING: the server failed to start, nothing to time
				emit(String.format(java.util.Locale.ROOT, "GmodCraft: server stop took %.1f s (players, worlds and chunks saved)",
					(System.nanoTime() - start) / 1e9), false);
			}
		});
	}

	/**
	 * To the log; after a SIGTERM/SIGINT (the JVM's shutdown hook stops the server) log4j is already
	 * shut down and System.err is routed into it, so then straight to the process's stderr.
	 */
	static void emit(String msg, boolean warn) {
		if (jvmExiting || loggingStopped()) {
			ERR.println("[GmodCraft stop watchdog] " + msg);
			ERR.flush();
		} else if (warn) {
			GmodCraft.LOG.warn(msg);
		} else {
			GmodCraft.LOG.info(msg);
		}
	}

	private static final PrintStream ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

	private static boolean loggingStopped() {
		try {
			return org.apache.logging.log4j.LogManager.getContext(false) instanceof org.apache.logging.log4j.core.LifeCycle lc
				&& (lc.getState() == org.apache.logging.log4j.core.LifeCycle.State.STOPPING || lc.isStopped());
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** The threshold in seconds: the system property, else the environment variable, else 30; bad values give 30. */
	static long thresholdSeconds(@Nullable String property, @Nullable String env) {
		String v = property != null && !property.isBlank() ? property : env;
		if (v == null || v.isBlank()) {
			return DEFAULT_SECONDS;
		}
		try {
			return Math.max(0, Long.parseLong(v.trim()));
		} catch (NumberFormatException e) {
			return DEFAULT_SECONDS;
		}
	}

	/** Starts the daemon that reports to sink after thresholdMs, then every repeatMs, until interrupted. */
	static Thread start(long thresholdMs, long repeatMs, int maxDumps, Consumer<String> sink) {
		long t0 = System.nanoTime();
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(thresholdMs);
				for (int i = 0; i < maxDumps; i++) {
					sink.accept(String.format(java.util.Locale.ROOT,
						"GmodCraft: the server stop is still running after %.0f s (dump %d of at most %d); all threads:%n%s",
						(System.nanoTime() - t0) / 1e9, i + 1, maxDumps, dumpThreads()));
					Thread.sleep(repeatMs);
				}
			} catch (InterruptedException e) {
				// the stop finished
			}
		}, "GmodCraft stop watchdog");
		t.setDaemon(true);
		t.start();
		return t;
	}

	/** All threads' stacks (every frame), states and lock owners, like jstack. */
	static String dumpThreads() {
		StringBuilder sb = new StringBuilder();
		for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
			sb.append('"').append(ti.getThreadName()).append("\" #").append(ti.getThreadId())
				.append(ti.isDaemon() ? " daemon" : "").append(' ').append(ti.getThreadState());
			if (ti.getLockName() != null) {
				sb.append(" on ").append(ti.getLockName());
			}
			if (ti.getLockOwnerName() != null) {
				sb.append(" owned by \"").append(ti.getLockOwnerName()).append("\" #").append(ti.getLockOwnerId());
			}
			sb.append('\n');
			StackTraceElement[] st = ti.getStackTrace();
			MonitorInfo[] monitors = ti.getLockedMonitors();
			for (int i = 0; i < st.length; i++) {
				sb.append("\tat ").append(st[i]).append('\n');
				for (MonitorInfo m : monitors) {
					if (m.getLockedStackDepth() == i) {
						sb.append("\t- locked ").append(m).append('\n');
					}
				}
			}
			for (LockInfo l : ti.getLockedSynchronizers()) {
				sb.append("\t- holds ").append(l).append('\n');
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
