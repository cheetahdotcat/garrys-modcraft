package dev.gmodcraft;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side settings from config/gmodcraft.properties (the same file the client keeps its own
 * settings in: destruction=, join=). Read at startup; the server rules (ServerRules, P8 WP3) also
 * change live through kAdminSetRules, which rewrites the file.
 *
 * <pre>
 * # GMod damage per Minecraft damage point. A GMod player has 100 health, a Minecraft player 20,
 * # so 5 maps a GMod hit on a player to the same share of their health. GMod NPCs have about
 * # 50-200 health: the host multiplies a kEvHitActor's Minecraft damage by the same factor.
 * hostDamagePerMcDamage=5.0
 * </pre>
 *
 * See ServerRules for the other keys.
 */
public final class GmodCraftConfig {
	/** GMod damage that equals one Minecraft damage point (half a heart). */
	public static volatile float hostDamagePerMcDamage = 5.0F;
	private static volatile ServerRules rules = ServerRules.DEFAULT;
	private static final Object FILE_LOCK = new Object();

	private GmodCraftConfig() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("gmodcraft.properties");
	}

	/** The current rules. */
	public static ServerRules rules() {
		return rules;
	}

	/** floorY for a new world (its generator, its first map slot table). */
	public static int floorY() {
		return rules.floorY();
	}

	static void load() {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file())) {
			props.load(in);
		} catch (NoSuchFileException e) {
			set(ServerRules.DEFAULT);
			return;
		} catch (IOException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't read {}", file(), e);
			return;
		}
		set(ServerRules.fromProperties(props, msg -> GmodCraft.LOG.warn("GmodCraft: {}: {}", file(), msg)));
		GmodCraft.LOG.info("GmodCraft: rules {}", describe(rules));
	}

	private static void set(ServerRules r) {
		rules = r;
		hostDamagePerMcDamage = r.hostDamagePerMcDamage();
		dev.gmodcraft.world.SkyDig.digIntoMap = r.digIntoMap();
	}

	static String describe(ServerRules r) {
		StringBuilder b = new StringBuilder();
		for (String k : ServerRules.KEYS) {
			if (b.length() > 0) {
				b.append(", ");
			}
			b.append(k).append('=').append(r.value(k)).append(r.isSet(k) ? "" : " (default)");
		}
		return b.toString();
	}

	/**
	 * kAdminSetRules: the pairs checked (all or nothing), written to the file (atomically, other keys
	 * and comments kept), then made current. Throws Bad for a bad pair, IOException if the file
	 * couldn't be written (nothing changed either way). Returns the new rules; the caller applies them.
	 */
	public static ServerRules change(String text) throws ServerRules.Bad, IOException {
		return change(ServerRules.parsePairs(text));
	}

	/**
	 * Sets these keys in the properties file (other lines kept), atomically, under the same lock as
	 * kAdminSetRules: the client's "GMod destruction" toggle uses it too (single player: one file, two
	 * threads).
	 */
	public static void writeKeys(Map<String, String> keys) throws IOException {
		synchronized (FILE_LOCK) {
			Path f = file();
			String old;
			try {
				old = Files.readString(f, StandardCharsets.UTF_8);
			} catch (NoSuchFileException e) {
				old = "# Garry's Modcraft\n";
			}
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, ServerRules.rewrite(old, keys), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/** As change(text), for pairs already parsed (a staged change's texts together). */
	public static ServerRules change(Map<String, String> pairs) throws ServerRules.Bad, IOException {
		synchronized (FILE_LOCK) {
			ServerRules next = rules.withAll(pairs);
			Map<String, String> write = new LinkedHashMap<>();
			for (String k : pairs.keySet()) {
				write.put(k, next.value(k));
			}
			Path f = file();
			String old;
			try {
				old = Files.readString(f, StandardCharsets.UTF_8);
			} catch (NoSuchFileException e) {
				old = "# Garry's Modcraft\n";
			}
			String text2 = ServerRules.rewrite(old, write);
			Properties check = new Properties();
			check.load(new StringReader(text2));  // what the next start will read
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, text2, StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			set(next);
			return next;
		}
	}
}
