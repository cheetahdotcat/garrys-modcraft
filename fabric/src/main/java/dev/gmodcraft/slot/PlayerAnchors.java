package dev.gmodcraft.slot;

import com.mojang.serialization.Codec;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.wire.SlotArea;
import dev.gmodcraft.world.MapSlots;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

/**
 * Offline players across a re-anchor (P8 WP2): a player who logged out inside a slot that was then
 * re-anchored would come back at the old height (in the ground, or in the air). Each player carries
 * (persistent attachment) how many re-anchors of each map it has seen; when it joins inside a slot
 * that has more, its position and respawn point (when in that slot) move by the blocks of the ones
 * it missed. Online players are refused in the slot during a job; everyone online is marked current
 * when a job finishes. A player that never played has seen everything.
 */
public final class PlayerAnchors {
	private static final Codec<Map<String, Integer>> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT);
	public static final AttachmentType<Map<String, Integer>> SEEN = AttachmentRegistry.<Map<String, Integer>>builder()
		.persistent(CODEC)
		.copyOnDeath()  // a respawn must not make the player "unseen" (it would be moved again)
		.buildAndRegister(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "anchor_seen"));

	private PlayerAnchors() {
	}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.getPlayer()));
	}

	private static void onJoin(MinecraftServer server, ServerPlayer player) {
		Map<String, Integer> seen = player.getAttached(SEEN);
		boolean veteran = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) > 0;
		MapSlots.Slot slot = player.level() == server.overworld() ? MapSlots.at(server, player.getBlockX(), player.getBlockZ()) : null;
		if (slot != null && !slot.reanchors().isEmpty()) {
			int had = seen != null && seen.containsKey(slot.map()) ? seen.get(slot.map()) : veteran ? 0 : slot.reanchors().size();
			int blocks = SlotShift.missedBlocks(slot, had);
			if (blocks != 0) {
				player.teleportTo(player.getX(), player.getY() + blocks, player.getZ());
				GmodCraft.LOG.info("GmodCraft: {} logged out in the slot of {} before {} re-anchor(s): moved {} blocks with it", player.getGameProfile().name(),
					slot.map(), slot.reanchors().size() - had, blocks);
			}
		}
		// the respawn point: moved by the re-anchors of the slot it lies in that this player missed
		ServerPlayer.RespawnConfig cfg = player.getRespawnConfig();
		if (cfg != null && cfg.respawnData().dimension() == Level.OVERWORLD) {
			BlockPos rp = cfg.respawnData().pos();
			MapSlots.Slot rs = MapSlots.at(server, rp.getX(), rp.getZ());
			if (rs != null && !rs.reanchors().isEmpty()) {
				int had = seen != null && seen.containsKey(rs.map()) ? seen.get(rs.map()) : veteran ? 0 : rs.reanchors().size();
				int blocks = SlotShift.missedBlocks(rs, had);
				if (blocks != 0) {
					player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(Level.OVERWORLD, rp.above(blocks),
						cfg.respawnData().yaw(), cfg.respawnData().pitch()), cfg.forced()), false);
				}
			}
		}
		markAll(server, player);
	}

	/** The player has seen every re-anchor of every slot (its position is current). */
	private static void markAll(MinecraftServer server, ServerPlayer player) {
		Map<String, Integer> m = new HashMap<>();
		for (MapSlots.Slot s : MapSlots.all(server)) {
			if (!s.reanchors().isEmpty()) {
				m.put(s.map(), s.reanchors().size());
			}
		}
		player.setAttached(SEEN, m);
	}

	/** After a re-anchor: everyone online is current for that slot (they were all outside it). */
	static void markOnline(MinecraftServer server, MapSlots.Slot slot) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Map<String, Integer> m = new HashMap<>(p.getAttachedOrElse(SEEN, Map.of()));
			m.put(slot.map(), slot.reanchors().size());
			p.setAttached(SEEN, m);
			if (SlotArea.contains(slot.originX(), slot.originZ(), p.getBlockX(), p.getBlockZ())) {
				GmodCraft.LOG.warn("GmodCraft: {} walked into the slot of {} during its re-anchor and wasn't moved", p.getGameProfile().name(), slot.map());
			}
		}
	}
}
