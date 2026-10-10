package dev.gmodcraft.weapon;

import com.mojang.authlib.GameProfile;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Test only (tools/test_mc.sh --server sets GMODCRAFT_TEST_WEAPONS=1; off otherwise): a headless
 * dedicated server has no players, so the hybrid weapon sync gets one stand-in, a Fabric
 * {@link FakePlayer} that is never in the player list (McPlayers stays empty) and is mapped to
 * {@link #STEAM_ID} for the weapon events only (no join token, no other host event reaches it).
 * Its set is appended after the real players' in McWeaponSets. On creation it gets one
 * "creative-forged" stack (a swep component put straight into the inventory, as /give or the
 * creative menu would): the set must report it like any other.
 */
public final class WeaponTestHook {
	public static final boolean ENABLED = "1".equals(System.getenv("GMODCRAFT_TEST_WEAPONS"));
	/** The stand-in's SteamID64 (tools/fake_host.py STEAM_WEAPON_TEST). */
	public static final long STEAM_ID = 76561197960265999L;
	public static final String FORGED_CLASS = "weapon_gmc_forged";
	public static final int FORGED_CLIP = 7;
	private static final GameProfile PROFILE = new GameProfile(UUID.fromString("6d63776e-7465-7374-0000-00000000aa01"), "gmc_weapon_test");

	private static @Nullable ServerPlayer player;

	private WeaponTestHook() {
	}

	/** Appends the stand-in (created on first use while linked) to McWeaponSets' player list. */
	static void append(MinecraftServer server, List<ServerPlayer> players, List<Long> steamIds) {
		if (!ENABLED || !ServerHost.linked()) {
			return;
		}
		if (player == null) {
			ServerPlayer p = FakePlayer.get(server.overworld(), PROFILE);
			p.getInventory().setItem(5, GmodWeapons.makeStack(new SwepData(FORGED_CLASS, "Forged", Proto.WEAP_CAT_PISTOL, FORGED_CLIP, -1)));
			player = p;
			GmodCraft.LOG.info("GmodCraft test: weapon stand-in {} (SteamID {}) with a forged {}", PROFILE.name(), Long.toUnsignedString(STEAM_ID), FORGED_CLASS);
		}
		players.add(player);
		steamIds.add(STEAM_ID);
	}

	/** The stand-in, if {@code steamId} is its SteamID and it exists. */
	static @Nullable ServerPlayer player(long steamId) {
		return ENABLED && steamId == STEAM_ID ? player : null;
	}

	static void forget() {
		player = null;
	}
}
