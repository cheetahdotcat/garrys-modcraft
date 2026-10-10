package dev.gmodcraft;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * e4mc (an optional mod: "Open to LAN" through an internet relay) as a soft dependency: nothing
 * here links against it, so the mod builds, tests and runs without it.
 *
 * <ul>
 * <li>Presence: Fabric's mod list, confirmed by loading its main class by name.</li>
 * <li>Session state: {@code link.e4mc.E4mcClient.session.state}, read by reflection.</li>
 * <li>The relay address: e4mc doesn't keep it anywhere readable; it logs
 * {@code "Domain assigned: <domain>"} on its "e4mc" logger and puts it in chat. A Log4j appender on
 * that logger picks it up (Minecraft logs through Log4j 2).</li>
 * </ul>
 * Everything is best effort: if e4mc changes, {@link #installed()} still says it's there and the
 * address just never arrives (the host falls back to the LAN address).
 */
public final class E4mc {
	static final String DOMAIN_PREFIX = "Domain assigned: ";
	private static final boolean INSTALLED = detect();
	private static volatile @Nullable String domain;
	private static boolean hooked;

	private E4mc() {
	}

	private static boolean detect() {
		if (!FabricLoader.getInstance().isModLoaded("e4mc")) {
			return false;
		}
		try {
			Class.forName("link.e4mc.E4mcClient", false, E4mc.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			GmodCraft.LOG.warn("GmodCraft: e4mc is listed but its classes aren't where we expect ({}); treating it as absent", e.toString());
			return false;
		}
	}

	/** True when the e4mc mod is present. */
	public static boolean installed() {
		return INSTALLED;
	}

	/** Starts listening for e4mc's relay address (once; does nothing without e4mc). */
	public static synchronized void init() {
		if (!INSTALLED || hooked) {
			return;
		}
		hooked = true;
		try {
			Log4jHook.attach();
			GmodCraft.LOG.info("GmodCraft: e4mc found; its relay address will be passed to GMod");
		} catch (Throwable t) {
			GmodCraft.LOG.warn("GmodCraft: e4mc found, but its relay address can't be picked up ({}); LAN address only", t.toString());
		}
	}

	/** The relay domain e4mc assigned to the open world, or null (none yet, closed, or no e4mc). */
	public static @Nullable String domain() {
		String d = domain;
		if (d == null) {
			return null;
		}
		// Polled every server tick: look at e4mc's session (reflection) at most every 2 s.
		long now = System.nanoTime();
		if (now - checkedAt > 2_000_000_000L) {
			checkedAt = now;
			String state = sessionState();
			// The session object is gone or stopping: the domain no longer leads anywhere.
			sessionLive = state == null || state.equals("STARTED") || state.equals("UNHEALTHY");
		}
		return sessionLive ? d : null;
	}

	private static volatile long checkedAt = System.nanoTime() - 3_000_000_000L;
	private static volatile boolean sessionLive = true;

	/** Forget the domain (the world was closed or the server stopped). */
	public static void forget() {
		domain = null;
		checkedAt = System.nanoTime() - 3_000_000_000L;
	}

	/** e4mc's session state by reflection ("STARTING", "STARTED", ...), or null if it can't be read / there is none. */
	public static @Nullable String sessionState() {
		if (!INSTALLED) {
			return null;
		}
		try {
			Class<?> client = Class.forName("link.e4mc.E4mcClient", false, E4mc.class.getClassLoader());
			Object session = client.getField("session").get(null);
			if (session == null) {
				return null;
			}
			Object state = session.getClass().getField("state").get(session);
			return state instanceof Enum<?> e ? e.name() : null;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	/** The domain in one of e4mc's log messages, or null if it isn't the "domain assigned" one. */
	static @Nullable String parseDomain(@Nullable String message) {
		if (message == null || !message.startsWith(DOMAIN_PREFIX)) {
			return null;
		}
		String d = message.substring(DOMAIN_PREFIX.length()).trim();
		return d.isEmpty() || d.length() >= 128 || d.chars().anyMatch(Character::isWhitespace) ? null : d;
	}

	static void onDomainLogged(String d) {
		domain = d;
		checkedAt = System.nanoTime() - 3_000_000_000L; // a new session: look at it on the next poll
		GmodCraft.LOG.info("GmodCraft: e4mc relay address {} picked up", d);
	}

	/** Kept in its own class so Log4j core classes load only when e4mc is present. */
	private static final class Log4jHook {
		static void attach() {
			var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("e4mc");
			var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("gmodcraft-e4mc", null, null, true,
				org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
				@Override
				public void append(org.apache.logging.log4j.core.LogEvent event) {
					String d = parseDomain(event.getMessage() != null ? event.getMessage().getFormattedMessage() : null);
					if (d != null) {
						onDomainLogged(d);
					}
				}
			};
			appender.start();
			logger.addAppender(appender);
		}
	}
}
