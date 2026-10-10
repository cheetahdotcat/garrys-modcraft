package dev.gmodcraft.link;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * When a Minecraft started for GMod quits after its GMod has gone (L2). Pure decision + a /proc probe, so
 * the decision is unit-tested on its own (QuitPolicyTest).
 * <ul>
 * <li>Linked: never.</li>
 * <li>A GMod process still runs (map change, disconnected to the menu, a hang): never; Minecraft stays for
 * the next join. Minecraft runs on the host, which sees GMod's process even when GMod is in Steam's
 * container.</li>
 * <li>No GMod process: {@link #GONE_CLEAN_MS} after the link went down when its discovery files are gone
 * too (a clean exit), {@link #GONE_CRASHED_MS} when they were left behind (a crash).</li>
 * <li>Can't tell (no /proc): the old timers, {@link #UNKNOWN_CLEAN_MS} / {@link #UNKNOWN_CRASHED_MS}.</li>
 * </ul>
 */
public final class QuitPolicy {
	public static final long GONE_CLEAN_MS = 15_000;
	public static final long GONE_CRASHED_MS = 30_000;
	public static final long UNKNOWN_CLEAN_MS = 60_000;
	public static final long UNKNOWN_CRASHED_MS = 120_000;
	/** Process names (/proc/PID/comm) of GMod's client; override with -Dgmodcraft.hostProcessNames=a,b. */
	public static final Set<String> DEFAULT_HOST_NAMES = Set.of("gmod", "gmod_linux64");

	public enum Host { ALIVE, GONE, UNKNOWN }

	private QuitPolicy() {
	}

	/** Milliseconds after the link went down at which to quit, or -1 never (for these facts). */
	public static long quitAfterMs(Host host, boolean discoveryGone) {
		return switch (host) {
			case ALIVE -> -1;
			case GONE -> discoveryGone ? GONE_CLEAN_MS : GONE_CRASHED_MS;
			case UNKNOWN -> discoveryGone ? UNKNOWN_CLEAN_MS : UNKNOWN_CRASHED_MS;
		};
	}

	/** True when Minecraft should save and quit now. */
	public static boolean shouldQuit(boolean enabled, boolean tookOver, boolean linked, long goneForMs, Host host, boolean discoveryGone) {
		if (!enabled || !tookOver || linked) {
			return false;
		}
		long after = quitAfterMs(host, discoveryGone);
		return after >= 0 && goneForMs > after;
	}

	/** Is a process with one of these names running? UNKNOWN when /proc can't be read. */
	public static Host probe(Path proc, Set<String> names) {
		if (!Files.isDirectory(proc)) {
			return Host.UNKNOWN;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc)) {
			for (Path p : ds) {
				String n = p.getFileName().toString();
				if (n.isEmpty() || !Character.isDigit(n.charAt(0))) {
					continue;
				}
				try {
					String comm = Files.readString(p.resolve("comm")).trim();
					if (names.contains(comm) && !zombie(p)) {
						return Host.ALIVE;
					}
				} catch (IOException | RuntimeException e) {
					// gone meanwhile, or not ours to read
				}
			}
		} catch (IOException | RuntimeException e) {
			return Host.UNKNOWN;
		}
		return Host.GONE;
	}

	/** A zombie (exited, not yet reaped: state Z or X in /proc/PID/stat) isn't a running GMod. */
	static boolean zombie(Path procPid) {
		try {
			String stat = Files.readString(procPid.resolve("stat"));
			int close = stat.lastIndexOf(')');
			if (close < 0 || close + 2 >= stat.length()) {
				return false;
			}
			char state = stat.charAt(close + 2);
			return state == 'Z' || state == 'X';
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	public static Set<String> hostNames() {
		String p = System.getProperty("gmodcraft.hostProcessNames");
		if (p == null || p.isBlank()) {
			return DEFAULT_HOST_NAMES;
		}
		Set<String> out = new java.util.HashSet<>();
		for (String n : p.trim().split("\\s*,\\s*")) {
			if (!n.isEmpty()) {
				out.add(n);
			}
		}
		return out.isEmpty() ? DEFAULT_HOST_NAMES : Set.copyOf(out);
	}
}
