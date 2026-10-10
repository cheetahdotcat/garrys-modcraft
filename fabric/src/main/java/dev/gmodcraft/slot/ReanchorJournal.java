package dev.gmodcraft.slot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A re-anchor's journal (P8 WP2): what is being changed and a backup of it, so a crash part-way
 * leaves the world as it was. No Minecraft types (unit-tested with plain directories).
 *
 * <p>Layout, under the world's {@code data/gmodcraft/reanchor/}: {@code journal.json} and
 * {@code backup/<relative path>} for every file the job may change (paths relative to the world
 * folder: the slot's 16 region, entities and poi files, demos.dat, map_slots.json). A file that did
 * not exist before is listed with {@code existed = false}: a restore deletes it.
 *
 * <p>Phases, in order: PREPARING (the backup is being written; the world is untouched),
 * BACKED_UP, MOVING, COMMITTED (map_slots.json has the new offset), DONE. At server start (before
 * the levels load) {@link #recover} restores the backup for BACKED_UP / MOVING / COMMITTED and just
 * drops it for PREPARING and DONE.
 */
public final class ReanchorJournal {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String DIR = "reanchor";

	public enum Phase {
		PREPARING, BACKED_UP, MOVING, COMMITTED, DONE
	}

	/**
	 * One file the job may change (relative to the world folder, '/' separators). size / sha256: its
	 * backup's, recorded once the copy is complete (sha256 null: not backed up yet, or it didn't exist).
	 */
	public record Entry(String path, boolean existed, long size, @Nullable String sha256) {
		public Entry(String path, boolean existed) {
			this(path, existed, -1, null);
		}
	}

	/** The journal file's content. */
	public static final class State {
		public int id;
		public String map = "";
		public int worldId;
		public int dyUnits;
		public int blocks;
		public int oyBefore;
		public Phase phase = Phase.PREPARING;
		public long startedAtMs;
		public List<Entry> files = new ArrayList<>();
	}

	/** REVIEW crash injection: run after the first backup file is copied (PREPARING, partial backup). */
	static @Nullable Runnable midBackupHook;

	private final Path world;
	private final Path dir;
	private State state;

	private ReanchorJournal(Path world, State state) {
		this.world = world;
		this.dir = dirOf(world);
		this.state = state;
	}

	public static Path dirOf(Path world) {
		return world.resolve("data").resolve("gmodcraft").resolve(DIR);
	}

	public State state() {
		return this.state;
	}

	/**
	 * Starts a journal: writes it (PREPARING), copies every existing file of {@code relPaths} into
	 * the backup, then marks it BACKED_UP. A journal that is already there means another job (or a
	 * crash not yet recovered): IllegalStateException.
	 */
	public static ReanchorJournal begin(Path world, State state, List<String> relPaths) throws IOException {
		Path dir = dirOf(world);
		if (Files.exists(dir.resolve("journal.json"))) {
			throw new IllegalStateException("a re-anchor journal is already there: " + dir);
		}
		deleteTree(dir.resolve("backup"));
		state.phase = Phase.PREPARING;
		state.files = new ArrayList<>();
		for (String rel : relPaths) {
			state.files.add(new Entry(rel, Files.isRegularFile(world.resolve(rel))));
		}
		ReanchorJournal j = new ReanchorJournal(world, state);
		j.write();
		for (int i = 0; i < state.files.size(); i++) {
			Entry e = state.files.get(i);
			if (e.existed()) {
				Path to = dir.resolve("backup").resolve(e.path());
				Files.createDirectories(to.getParent());
				Files.copy(world.resolve(e.path()), to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				state.files.set(i, new Entry(e.path(), true, Files.size(to), sha256(to)));
				if (midBackupHook != null) {
					midBackupHook.run();
				}
			}
		}
		j.phase(Phase.BACKED_UP);  // with every backup's size and hash
		return j;
	}

	/** Moves the journal to {@code phase} (written to disk before this returns). */
	public void phase(Phase phase) throws IOException {
		this.state.phase = phase;
		write();
	}

	/** Finished: the journal and the backup are deleted. */
	public void finish() throws IOException {
		phase(Phase.DONE);
		deleteTree(this.dir);
	}

	/** Gave up before anything moved (dry run refused, an error while backing up): the backup is dropped. */
	public void abandon() throws IOException {
		if (this.state.phase.compareTo(Phase.BACKED_UP) > 0) {
			throw new IllegalStateException("can't abandon a journal in phase " + this.state.phase);
		}
		deleteTree(this.dir);
	}

	private void write() throws IOException {
		Files.createDirectories(this.dir);
		Path f = this.dir.resolve("journal.json");
		Path tmp = this.dir.resolve("journal.json.tmp");
		Files.writeString(tmp, GSON.toJson(this.state), StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** The journal left in {@code world}, or null. */
	public static @Nullable State read(Path world) throws IOException {
		Path f = dirOf(world).resolve("journal.json");
		if (!Files.isRegularFile(f)) {
			return null;
		}
		State s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class);
		return s != null && s.phase != null ? s : null;
	}

	/** What {@link #recover} did. */
	public enum Recovery {
		NOTHING, DROPPED, RESTORED
	}

	/**
	 * At server start, before the levels load: a journal stuck in BACKED_UP / MOVING / COMMITTED
	 * has its backup put back (files that didn't exist are deleted); PREPARING / DONE leave the world
	 * as it is. Either way the journal and the backup are gone afterwards. A journal that can't be
	 * read is left alone (IOException: the caller refuses to start rather than guess).
	 */
	public static Recovery recover(Path world) throws IOException {
		State s = read(world);
		Path dir = dirOf(world);
		if (s == null) {
			if (Files.exists(dir.resolve("journal.json"))) {
				throw new IOException("unreadable re-anchor journal " + dir.resolve("journal.json"));
			}
			deleteTree(dir); // a stray backup without a journal
			return Recovery.NOTHING;
		}
		if (s.phase == Phase.PREPARING || s.phase == Phase.DONE) {
			deleteTree(dir);
			return Recovery.DROPPED;
		}
		// Every backup must be there and whole BEFORE anything is put back: a half restore is worse
		// than none (the caller then refuses to start).
		for (Entry e : s.files) {
			if (!e.existed()) {
				continue;
			}
			Path from = dir.resolve("backup").resolve(e.path());
			if (!Files.isRegularFile(from)) {
				throw new IOException("re-anchor backup is missing " + from + "; nothing was restored");
			}
			if (e.sha256() == null) {
				throw new IOException("re-anchor backup " + from + " was never completed (no hash in the journal); nothing was restored");
			}
			if (Files.size(from) != e.size() || !e.sha256().equals(sha256(from))) {
				throw new IOException("re-anchor backup " + from + " is damaged (size or hash differ); nothing was restored");
			}
		}
		for (Entry e : s.files) {
			Path target = world.resolve(e.path());
			if (e.existed()) {
				Path from = dir.resolve("backup").resolve(e.path());
				Files.createDirectories(target.getParent());
				Files.copy(from, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			} else {
				Files.deleteIfExists(target);
			}
		}
		deleteTree(dir);
		return Recovery.RESTORED;
	}

	static String sha256(Path p) throws IOException {
		try (var in = Files.newInputStream(p)) {
			java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0;) {
				d.update(buf, 0, n);
			}
			return java.util.HexFormat.of().formatHex(d.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Bytes the backup of these files takes (for the free-space check). */
	public static long sizeOf(Path world, List<String> relPaths) throws IOException {
		long n = 0;
		for (String rel : relPaths) {
			Path p = world.resolve(rel);
			if (Files.isRegularFile(p)) {
				n += Files.size(p);
			}
		}
		return n;
	}

	public static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) {
			return;
		}
		try (Stream<Path> s = Files.walk(p)) {
			for (Path q : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(q);
			}
		}
	}
}
