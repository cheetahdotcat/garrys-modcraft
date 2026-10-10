package dev.gmodcraft;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only server commands from the host (protocol v14 kHostEvDevCommand): live tests set up the
 * Minecraft world through the server link instead of typing commands into chat. Run as the server
 * console (permission level 4), and ONLY when this server was started with
 * {@code -Dgmodcraft.devCommands=true} or {@code GMODCRAFT_DEV_COMMANDS=1}; otherwise every request is
 * answered kDevCommandDisabled without running. Server thread only.
 */
public final class DevCommands {
	public static final boolean ENABLED = Boolean.getBoolean("gmodcraft.devCommands") || "1".equals(System.getenv("GMODCRAFT_DEV_COMMANDS"));
	private static boolean warnedDisabled;

	/** A kHostEvDevCommand whose text slots are still being collected. */
	private static final class Pending {
		final int requestId;
		final int length;
		final int chunks;
		final byte[] bytes = new byte[Proto.DEV_COMMAND_MAX_BYTES];
		int received;

		Pending(int requestId, int length, int chunks) {
			this.requestId = requestId;
			this.length = length;
			this.chunks = chunks;
		}
	}

	private static @Nullable Pending pending;

	private DevCommands() {
	}

	/**
	 * Takes the dev-command events out of the host event stream. Returns true when {@code ev} was one
	 * of them; any other event first ends a sequence that is still missing text slots (malformed).
	 */
	static boolean accept(MinecraftServer server, ServerLink.HostEvent ev) {
		if (ev.type() == Proto.HOST_EV_DEV_COMMAND_TEXT) {
			Pending p = pending;
			if (p == null || ev.text() == null || ev.code() != p.received) {
				GmodCraft.LOG.warn("GmodCraft: dev command text slot out of sequence (chunk {}); dropped", ev.code());
				if (p != null) {
					pending = null;
					reply(p.requestId, Proto.DEV_COMMAND_MALFORMED, 0, "text slots out of order");
				}
				return true;
			}
			System.arraycopy(ev.text(), 0, p.bytes, p.received * Proto.DEV_COMMAND_CHUNK_BYTES, Proto.DEV_COMMAND_CHUNK_BYTES);
			p.received++;
			if (p.received == p.chunks) {
				pending = null;
				run(server, p);
			}
			return true;
		}
		endOfSequence();
		if (ev.type() != Proto.HOST_EV_DEV_COMMAND) {
			return false;
		}
		int len = ev.a(), chunks = ev.code();
		if (ev.requestId() == 0 || len <= 0 || len > Proto.DEV_COMMAND_MAX_BYTES || chunks > Proto.DEV_COMMAND_MAX_CHUNKS
			|| chunks * Proto.DEV_COMMAND_CHUNK_BYTES < len) {
			reply(ev.requestId(), Proto.DEV_COMMAND_MALFORMED, 0, "bad length " + len + " / " + chunks + " text slots");
			return true;
		}
		pending = new Pending(ev.requestId(), len, chunks);
		return true;
	}

	/** After a drain (and before any other event): a sequence still missing text slots is malformed. */
	static void endOfSequence() {
		Pending p = pending;
		if (p != null) {
			pending = null;
			reply(p.requestId, Proto.DEV_COMMAND_MALFORMED, 0, "missing text slots (" + p.received + " of " + p.chunks + ")");
		}
	}

	private static void run(MinecraftServer server, Pending p) {
		String command;
		try {
			command = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(java.nio.ByteBuffer.wrap(p.bytes, 0, p.length)).toString().trim();
		} catch (CharacterCodingException e) {
			reply(p.requestId, Proto.DEV_COMMAND_MALFORMED, 0, "not UTF-8");
			return;
		}
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		if (!ENABLED) {
			if (!warnedDisabled) {
				warnedDisabled = true;
				GmodCraft.LOG.warn("GmodCraft: the host sent a dev command; ignored (start Minecraft with -Dgmodcraft.devCommands=true or "
					+ "GMODCRAFT_DEV_COMMANDS=1 to allow them). Further ones are ignored silently.");
			}
			reply(p.requestId, Proto.DEV_COMMAND_DISABLED, 0, "dev commands are off");
			return;
		}
		StringBuilder out = new StringBuilder();
		boolean[] success = { false };
		int[] count = { 0 };
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				if (out.isEmpty()) {
					out.append(message.getString());
				}
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack stack = server.createCommandSourceStack().withSource(capture).withPermission(LevelBasedPermissionSet.OWNER)
			.withCallback((ok, result) -> {
				success[0] = ok;
				count[0] = result;
			});
		GmodCraft.LOG.info("GmodCraft: dev command {}: /{}", p.requestId, command);
		try {
			server.getCommands().performPrefixedCommand(stack, command);
		} catch (RuntimeException e) {
			success[0] = false;
			if (out.isEmpty()) {
				out.append(e);
			}
		}
		String line = out.toString().lines().findFirst().orElse("");
		GmodCraft.LOG.info("GmodCraft: dev command {}: {} (result {}) {}", p.requestId, success[0] ? "ok" : "failed", count[0], line);
		reply(p.requestId, success[0] ? Proto.DEV_COMMAND_OK : Proto.DEV_COMMAND_FAILED, count[0], line);
	}

	private static void reply(int requestId, int result, int count, String output) {
		ServerLink.INSTANCE.pushDevCommandResult(requestId, result, count, truncateUtf8(output, Proto.DEV_OUTPUT_MAX_BYTES));
	}

	/** UTF-8 of {@code s}, cut to at most {@code max} bytes without splitting a character. */
	static byte[] truncateUtf8(String s, int max) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		if (b.length <= max) {
			return b;
		}
		int n = max;
		while (n > 0 && (b[n] & 0xC0) == 0x80) {
			n--;
		}
		return java.util.Arrays.copyOf(b, n);
	}
}
