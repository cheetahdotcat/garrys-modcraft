package dev.gmodcraft;

import dev.gmodcraft.link.Proto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * v34 (control centre): the dedicated server's world and its backups, and the world operation an
 * admin scheduled for the next start (kAdminWorldList / kAdminWorldOp). Pure file logic, no
 * Minecraft classes (unit-tested); ServerAdmin runs it.
 *
 * <p>Backups are the server dir's {@code <level-name>.bak-YYYYMMDD-HHMMSS} folders (what
 * run_mc_server.sh --reset-world makes). Nothing here moves a world: Java only writes
 * {@value #OP_FILE} ("new &lt;type&gt;" | "restore &lt;stamp&gt;") into the server dir, and
 * tools/run_mc_server.sh applies it before Java starts (the running world moved aside first). With
 * a supervisor (that script's restart loop: env {@value #SUPERVISED_ENV}=1) {@value #RESTART_FILE}
 * asks it to start Java again after a stop. Paths from GMod are never used: a restore names a stamp,
 * which must match a backup that exists right now.
 */
public final class WorldOps {
	public static final String OP_FILE = "gmodcraft-world-op";
	public static final String RESTART_FILE = "gmodcraft-restart";
	public static final String SUPERVISED_ENV = "GMODCRAFT_SUPERVISED";
	public static final Pattern STAMP = Pattern.compile("\\d{8}-\\d{6}");
	private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withResolverStyle(ResolverStyle.STRICT);

	private WorldOps() {
	}

	/** One world-list entry: the folder, the backup stamp ("" for the running world), its size in bytes. */
	public record Entry(Path dir, String stamp, long bytes) {
		public boolean backup() {
			return !this.stamp.isEmpty();
		}

		/** Days since 1970-01-01 and the second of that day of the stamp ({0, 0} for the running world). */
		public int[] dayAndSecond() {
			if (this.stamp.isEmpty()) {
				return new int[] { 0, 0 };
			}
			LocalDateTime t = LocalDateTime.parse(this.stamp, STAMP_FORMAT);
			return new int[] { (int) t.toLocalDate().toEpochDay(), t.toLocalTime().toSecondOfDay() };
		}
	}

	/** The scheduled operation: Proto.WORLD_OP_NEW + a world type name, or WORLD_OP_RESTORE + a stamp. */
	public record Op(int op, String arg) {
		public String line() {
			return (this.op == Proto.WORLD_OP_NEW ? "new " : "restore ") + this.arg;
		}
	}

	/** Is this a valid backup stamp (YYYYMMDD-HHMMSS, a real date and time)? */
	public static boolean validStamp(@Nullable String s) {
		if (s == null || !STAMP.matcher(s).matches()) {
			return false;
		}
		try {
			LocalDateTime.parse(s, STAMP_FORMAT);
			return true;
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	/** The running world (entry 0) and its backups, newest first. Symlinks are skipped. */
	public static List<Entry> list(Path worldDir, boolean sizes) throws IOException {
		Path dir = worldDir.toAbsolutePath().normalize();
		String prefix = dir.getFileName().toString() + ".bak-";
		List<Entry> backups = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir.getParent())) {
			for (Path p : (Iterable<Path>) s::iterator) {
				String n = p.getFileName().toString();
				if (n.startsWith(prefix) && validStamp(n.substring(prefix.length())) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
					backups.add(new Entry(p, n.substring(prefix.length()), sizes ? size(p) : 0));
				}
			}
		}
		backups.sort(Comparator.comparing(Entry::stamp).reversed());
		List<Entry> out = new ArrayList<>();
		out.add(new Entry(dir, "", sizes && Files.isDirectory(dir) ? size(dir) : 0));
		out.addAll(backups);
		return out;
	}

	private static long size(Path dir) {
		long[] total = { 0 };
		try (Stream<Path> s = Files.walk(dir)) {
			s.forEach(p -> {
				try {
					if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
						total[0] += Files.size(p);
					}
				} catch (IOException e) {
					// gone meanwhile (the server saves): not counted
				}
			});
		} catch (IOException | java.io.UncheckedIOException e) {
			// partly counted
		}
		return total[0];
	}

	/**
	 * Checks an admin's operation against the world type names and the backups there are now. Returns
	 * the op to schedule; throws IllegalArgumentException (why) for anything else.
	 */
	public static Op check(int op, @Nullable String arg, List<Entry> entries) {
		String a = arg == null ? "" : arg.trim();
		if (op == Proto.WORLD_OP_NEW) {
			String t = a.startsWith("gmodcraft:") ? a.substring("gmodcraft:".length()) : a;
			if (!ServerRules.WORLD_TYPES.contains(t)) {
				throw new IllegalArgumentException("world type: one of " + String.join(", ", ServerRules.WORLD_TYPES) + ": '" + a + "'");
			}
			return new Op(op, t);
		}
		if (op == Proto.WORLD_OP_RESTORE) {
			if (!validStamp(a)) {
				throw new IllegalArgumentException("not a backup stamp (YYYYMMDD-HHMMSS): '" + a + "'");
			}
			for (Entry e : entries) {
				if (e.backup() && e.stamp().equals(a)) {
					return new Op(op, a);
				}
			}
			throw new IllegalArgumentException("no backup " + a);
		}
		throw new IllegalArgumentException("unknown world operation " + op);
	}

	/** The scheduled operation in serverDir, or null (none, or a line the script would refuse). */
	public static @Nullable Op pending(Path serverDir) {
		String text;
		try {
			text = Files.readString(serverDir.resolve(OP_FILE), StandardCharsets.UTF_8).trim();
		} catch (NoSuchFileException e) {
			return null;
		} catch (IOException e) {
			return null;
		}
		String[] w = text.split("\\s+");
		if (w.length != 2) {
			return null;
		}
		if (w[0].equals("new") && ServerRules.WORLD_TYPES.contains(w[1])) {
			return new Op(Proto.WORLD_OP_NEW, w[1]);
		}
		if (w[0].equals("restore") && validStamp(w[1])) {
			return new Op(Proto.WORLD_OP_RESTORE, w[1]);
		}
		return null;
	}

	/** Writes (atomically) or, with null, removes the scheduled operation. */
	public static void schedule(Path serverDir, @Nullable Op op) throws IOException {
		Path f = serverDir.resolve(OP_FILE);
		if (op == null) {
			Files.deleteIfExists(f);
			return;
		}
		Path tmp = serverDir.resolve(OP_FILE + ".tmp");
		Files.writeString(tmp, op.line() + "\n", StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Is a supervisor (run_mc_server.sh's restart loop) there to start the server again? */
	public static boolean supervised() {
		return "1".equals(System.getenv(SUPERVISED_ENV));
	}

	/** Asks the supervisor to start Java again after this stop. */
	public static void requestRestart(Path serverDir) throws IOException {
		Files.writeString(serverDir.resolve(RESTART_FILE), "restart\n", StandardCharsets.UTF_8);
	}
}
