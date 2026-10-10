package dev.gmodcraft;

import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.net.SkyNet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * GMod's noclip for Minecraft players (protocol v22, N1). GMod decides (its noclip bind,
 * PlayerNoClip, sbox_noclip, admins, gmodcraft_noclip_mc) and marks the player kHostPlayerNoclip in
 * HostPlayers. Here, once per server tick, a marked player's Minecraft player gets:
 * <ul>
 * <li>flight (mayfly + flying, kept on every tick) and no physics (PlayerNoclipMixin, both sides):
 * it flies through Minecraft blocks and the host's geometry; survival stays (HUD, hotbar, health,
 * building), unlike spectator mode. The server's move checks skip a noPhysics player;</li>
 * <li>no fall damage (ServerPlayerMixin), also after noclip ends until it first lands;</li>
 * <li>its client is told by a SkyNet packet (the client runs the movement).</li>
 * </ul>
 * Flight kicks are off while linked anyway (the flight mixins); mayfly covers the rest.
 */
public final class Noclip {
	private static final NoclipState STATE = new NoclipState();
	// The player object whose client was told (a respawn makes a new object, a client may not be
	// ready to receive yet: told again until it is).
	private static final Map<UUID, ServerPlayer> APPLIED = new HashMap<>();
	/** The local player's state as its server last said (client side; set from SkyNet). */
	public static volatile boolean clientLocal;

	private Noclip() {
	}

	/** Does this player fly through everything right now (either side)? */
	public static boolean active(Player player) {
		if (player.level().isClientSide()) {
			return clientLocal && player.isLocalPlayer();
		}
		return STATE.active(player.getUUID());
	}

	/** No fall damage: in noclip, or falling out of it (server side). */
	public static boolean noFall(ServerPlayer player) {
		return STATE.noFall(player.getUUID());
	}

	/** Once per server tick, after HostPlayers was read. {@code linked} false turns everyone off. */
	static void tick(MinecraftServer server, boolean linked, Function<ServerPlayer, ServerLink.@Nullable HostPlayer> hostPlayerOf) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			ServerLink.HostPlayer hp = linked ? hostPlayerOf.apply(player) : null;
			boolean want = hp != null && hp.noclip() && GmodCraftConfig.rules().noclipMc();  // v24: noclipMc off: never
			NoclipState.Change change = STATE.update(id, want);
			if (change == NoclipState.Change.ON) {
				turnOn(player);
			} else if (change == NoclipState.Change.OFF) {
				turnOff(player);
			}
			// The client hears the state until it has received it: on for this player object (a
			// respawn makes a new one), and off once after being told on.
			if (want && APPLIED.get(id) != player && tell(player, true)) {
				APPLIED.put(id, player);
			} else if (!want && APPLIED.containsKey(id) && tell(player, false)) {
				APPLIED.remove(id);
			}
			if (STATE.active(id)) {
				// Kept every tick: a double jump would otherwise end flying, and with no physics the
				// player would then sink through the world.
				var a = player.getAbilities();
				if (!a.mayfly || !a.flying) {
					a.mayfly = true;
					a.flying = true;
					player.onUpdateAbilities();
				}
				player.resetFallDistance();
			} else if (player.onGround()) {
				STATE.landed(id);
			}
		}
	}

	private static void turnOn(ServerPlayer player) {
		var a = player.getAbilities();
		a.mayfly = true;
		a.flying = true;
		player.onUpdateAbilities();
		player.resetFallDistance();
		GmodCraft.LOG.info("GmodCraft: {} noclip on (GMod)", player.getPlainTextName());
	}

	private static void turnOff(ServerPlayer player) {
		player.noPhysics = player.isSpectator();
		player.gameMode.getGameModeForPlayer().updatePlayerAbilities(player.getAbilities());
		player.onUpdateAbilities();
		player.resetFallDistance();
		GmodCraft.LOG.info("GmodCraft: {} noclip off (GMod)", player.getPlainTextName());
	}

	/** Tells the player's client; false when it can't receive it (yet). */
	private static boolean tell(ServerPlayer player, boolean on) {
		if (!ServerPlayNetworking.canSend(player, SkyNet.Noclip.TYPE)) {
			return false;
		}
		ServerPlayNetworking.send(player, new SkyNet.Noclip(on));
		return true;
	}

	static void forget(UUID player) {
		STATE.forget(player);
		APPLIED.remove(player);
	}

	static void clear() {
		STATE.clear();
		APPLIED.clear();
	}

	/** Players in noclip now (debug). */
	public static List<UUID> activePlayers() {
		return STATE.activePlayers();
	}
}
