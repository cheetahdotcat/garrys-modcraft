package dev.gmodcraft.net;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Minecraft client <-> Minecraft server packets for digging (the digging player's client knows the
 * host's geometry around them; the server applies it). Hurt and death no longer travel here: the
 * GMod server talks to the Minecraft server directly over the server link.
 */
public final class SkyNet {
	private SkyNet() {
	}


	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server, once after joining (protocol v14 pairing): the join token the GMod server
	 * handed this player's GMod client (JoinInfo::joinToken). Kept per player by ServerHost; never
	 * logged in full.
	 */
	public record JoinToken(String token) implements CustomPacketPayload {
		public static final int MAX_BYTES = 127;  // kJoinTokenBytes - 1
		public static final Type<JoinToken> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "join_token"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JoinToken> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_BYTES), JoinToken::token,
			JoinToken::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}

		@Override
		public String toString() {
			return "JoinToken[" + tokenHash(this.token) + "]"; // never the token itself
		}
	}

	/** A short, log-safe fingerprint of a join token: "#" + the first 8 hex digits of its SHA-256. */
	public static String tokenHash(String token) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return "#" + java.util.HexFormat.of().formatHex(d, 0, 4);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Server -> client (v22 noclip, N1): GMod has this player in noclip (or not any more). */
	public record Noclip(boolean on) implements CustomPacketPayload {
		public static final Type<Noclip> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "noclip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Noclip> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Noclip::on, Noclip::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> client (T2b): the entities GMod holds right now (HeldMcEntities), the whole set. The
	 * client follows their position packets without its usual 3-tick interpolation buffer, so a
	 * roped / carried mob stays with its GMod body. Sent on change and about once a second while any.
	 */
	public record HeldIds(List<Integer> ids) implements CustomPacketPayload {
		public static final int MAX = 256;  // GMod holds at most kMaxHeldMcEntities (64)
		public static final Type<HeldIds> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "held_ids"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HeldIds> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX)), HeldIds::ids,
			HeldIds::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Noclip.TYPE, Noclip.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HeldIds.TYPE, HeldIds.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JoinToken.TYPE, JoinToken.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(JoinToken.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> dev.gmodcraft.ServerHost.joinToken(player, payload.token()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyDig.open(player, payload.world(), payload.pos(), payload.material()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> SkyDig.reveal(player, payload.world(), payload.cells(), materials));
		});
	}

}
