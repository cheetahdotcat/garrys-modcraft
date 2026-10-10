package dev.gmodcraft.prop;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.world.SkyClip;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyRay;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * P1 (protocol v33): GMod props as {@code gmodcraft:gmod_prop} stacks. Server thread only.
 *
 * <p>Pickup: sneak + use with an empty main hand on a GMod prop's collision (a kTriDynamic
 * triangle carrying the entity index) sends kEvPropRequest (kPropOpPickup); the GMod server checks
 * its gates, removes the prop and answers kHostEvPropResult + text: a stack appears. Place: using a
 * stack on any surface sends kEvPropRequest (kPropOpPlace, the prop text); the GMod server spawns
 * it with its bottom on the hit point, facing the player, and the answer consumes one stack (not in
 * creative). Refusals are shown on the action bar. One place in flight per player.
 */
public final class GmodProps {
	public static final DataComponentType<PropData> PROP = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
		Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "prop"),
		DataComponentType.<PropData>builder().persistent(PropData.CODEC).networkSynchronized(PropData.STREAM_CODEC).build());
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "gmod_prop"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
		new GmodPropItem(new Item.Properties().setId(ITEM_KEY).stacksTo(Proto.PROP_MAX_STACK)));

	/** A request is forgotten (no answer: the stack stays) after this long. */
	static final long PENDING_NS = 5_000_000_000L;
	/** Pickups a player may send at most this often (ticks of the request clock, ns). */
	private static final long PICKUP_GAP_NS = 250_000_000L;

	private record Pending(int op, UUID player, @Nullable PropData data, InteractionHand hand, long at) {
	}

	/** A pickup answer whose text slots are still being collected. */
	private record PendingGive(long steamId, int requestId, int entId, int skin, int color, int chunks, byte[] text, int[] received) {
	}

	private static final Map<Integer, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, Long> LAST_PICKUP = new HashMap<>();
	private static @Nullable PendingGive give;
	private static int nextRequest = 1;
	private static int generation = -1;

	private GmodProps() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register(GmodProps::useBlock);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PENDING.clear();
			LAST_PICKUP.clear();
			give = null;
			generation = -1;
		});
	}

	public static @Nullable PropData data(ItemStack stack) {
		PropData d = stack.is(ITEM) ? stack.get(PROP) : null;
		return d != null && !d.inert() ? d : null;
	}

	public static ItemStack makeStack(PropData d, int count) {
		ItemStack stack = new ItemStack(ITEM, count);
		stack.set(PROP, d);
		return stack;
	}

	// ---- pickup ----------------------------------------------------------------------------------

	/** The GMod entity index of a triangle the player could pick up, or 0. */
	static int pickable(SkyRay.@Nullable Hit hit) {
		return hit != null && hit.tri() != null && hit.tri().dynamic ? hit.tri().entity : 0;
	}

	private static InteractionResult useBlock(Player player, net.minecraft.world.level.Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty() || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			// The client's own pick knows the triangle: a GMod prop -> the server gets the use packet and decides.
			return hit instanceof SkyClip.SkyrimHitResult sky && pickable(sky.hit) != 0 ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer sp) || !ServerHost.linked()) {
			return InteractionResult.PASS;
		}
		// The server's copy of the host geometry, along the ray to the point the client clicked.
		Vec3 eye = sp.getEyePosition();
		Vec3 to = hit.getLocation();
		Vec3 dir = to.subtract(eye);
		double len = dir.length();
		if (len < 1e-3 || len > sp.blockInteractionRange() + 2.0) {
			return InteractionResult.PASS;
		}
		SkyRay.Hit h = SkyClip.cast(SkyCollision.of(level), eye, eye.add(dir.scale((len + 0.25) / len)));
		int ent = pickable(h);
		if (ent == 0 || Math.abs(h.t() * (len + 0.25) - len) > 0.75) {
			return InteractionResult.PASS; // not a GMod prop there (or something else in between)
		}
		pickup(sp, ent, new Vec3(h.x(), h.y(), h.z()));
		return InteractionResult.SUCCESS;
	}

	static void pickup(ServerPlayer player, int ent, Vec3 at) {
		long steamId = ServerHost.steamIdOf(player);
		if (steamId == 0) {
			player.sendOverlayMessage(Component.translatable("gmodcraft.prop.unpaired"));
			return;
		}
		long now = System.nanoTime();
		Long last = LAST_PICKUP.get(player.getUUID());
		if (last != null && now - last < PICKUP_GAP_NS) {
			return;
		}
		LAST_PICKUP.put(player.getUUID(), now);
		session();
		int id = request();
		if (!ServerLink.INSTANCE.pushPropRequest(steamId, id, Proto.PROP_OP_PICKUP, ent, (float) at.x, (float) at.y, (float) at.z, 0.0F, 0, new byte[0])) {
			player.sendOverlayMessage(Component.translatable("gmodcraft.prop.result." + Proto.PROP_FAILED));
			return;
		}
		PENDING.put(id, new Pending(Proto.PROP_OP_PICKUP, player.getUUID(), null, InteractionHand.MAIN_HAND, now));
		GmodCraft.LOG.debug("GmodCraft: prop pickup {}: {} asks for GMod entity {}", id, player.getPlainTextName(), ent);
	}

	// ---- place -----------------------------------------------------------------------------------

	static void place(ServerPlayer player, InteractionHand hand, PropData d, Vec3 at) {
		if (!ServerHost.linked()) {
			player.sendOverlayMessage(Component.translatable("gmodcraft.prop.result." + Proto.PROP_DISABLED));
			return;
		}
		long steamId = ServerHost.steamIdOf(player);
		if (steamId == 0) {
			player.sendOverlayMessage(Component.translatable("gmodcraft.prop.unpaired"));
			return;
		}
		session();
		long now = System.nanoTime();
		for (Pending p : PENDING.values()) {
			if (p.op() == Proto.PROP_OP_PLACE && p.player().equals(player.getUUID()) && now - p.at() < PENDING_NS) {
				return; // one place in flight per player (the item is consumed by its answer)
			}
		}
		int id = request();
		int flags = Proto.PROP_OP_PLACE | d.skin() << Proto.PROP_SKIN_SHIFT;
		if (!ServerLink.INSTANCE.pushPropRequest(steamId, id, flags, 0, (float) at.x, (float) at.y, (float) at.z, player.getYRot(), d.color(), d.text())) {
			player.sendOverlayMessage(Component.translatable("gmodcraft.prop.result." + Proto.PROP_FAILED));
			return;
		}
		PENDING.put(id, new Pending(Proto.PROP_OP_PLACE, player.getUUID(), d, hand, now));
		GmodCraft.LOG.debug("GmodCraft: prop place {}: {} places {}", id, player.getPlainTextName(), d.model());
	}

	private static int request() {
		int id = nextRequest;
		nextRequest = id == Integer.MAX_VALUE ? 1 : id + 1;
		return id;
	}

	/** A new link session: requests of the old one get no answer. Also drops stale requests. */
	private static void session() {
		int gen = ServerLink.INSTANCE.generation();
		if (gen != generation) {
			generation = gen;
			PENDING.clear();
			give = null;
		}
		if (PENDING.size() > 64) {
			long now = System.nanoTime();
			PENDING.values().removeIf(p -> now - p.at() > PENDING_NS);
		}
		if (LAST_PICKUP.size() > 256) {
			LAST_PICKUP.clear();
		}
	}

	// ---- host events -----------------------------------------------------------------------------

	/** Takes the v33 prop events out of the host event stream; true when {@code ev} was one. */
	public static boolean accept(MinecraftServer server, ServerLink.HostEvent ev) {
		if (ev.type() == Proto.HOST_EV_PROP_TEXT) {
			PendingGive g = give;
			if (g == null || ev.text() == null || ev.code() != g.received()[0]) {
				GmodCraft.LOG.warn("GmodCraft: prop text slot out of sequence (chunk {}); dropped", ev.code());
				give = null;
				return true;
			}
			byte[] t = ev.text();
			int at = g.received()[0] * Proto.PROP_HOST_CHUNK_BYTES;
			System.arraycopy(t, 0, g.text(), at, Math.max(0, Math.min(Math.min(t.length, Proto.PROP_HOST_CHUNK_BYTES), g.text().length - at)));
			g.received()[0]++;
			if (g.received()[0] == g.chunks()) {
				give = null;
				given(server, g);
			}
			return true;
		}
		endOfSequence(); // any other event ends a give's text sequence
		if (ev.type() != Proto.HOST_EV_PROP_RESULT) {
			return false;
		}
		session();
		Pending p = PENDING.remove(ev.requestId());
		int op = ev.a();
		int result = ev.flags();
		if (op == Proto.PROP_OP_PICKUP && result == Proto.PROP_OK) {
			int chunks = ev.code();
			if (chunks < 1 || chunks > Proto.PROP_HOST_MAX_CHUNKS) {
				GmodCraft.LOG.warn("GmodCraft: malformed prop give {} ({} text slots); dropped", ev.requestId(), chunks);
				return true;
			}
			give = new PendingGive(ev.steamId(), ev.requestId(), ev.entId(), clampInt(ev.x(), 0, 65535), (int) (long) clampLong(ev.y()), chunks,
				new byte[Proto.PROP_HOST_CHUNK_BYTES * Proto.PROP_HOST_MAX_CHUNKS], new int[1]);
			return true;
		}
		ServerPlayer player = ServerHost.playerBySteamId(server, ev.steamId());
		if (result != Proto.PROP_OK) {
			// Not a loose prop (a door, a breakable, gone) on pickup: silent; sneak + use on GMod geometry is common.
			if (player != null && !(op == Proto.PROP_OP_PICKUP && result == Proto.PROP_NOT_A_PROP)) {
				player.sendOverlayMessage(Component.translatable("gmodcraft.prop.result." + Math.min(result, Proto.PROP_FAILED + 1)));
			}
			GmodCraft.LOG.info("GmodCraft: prop {} {} refused by GMod: result {}", op == Proto.PROP_OP_PLACE ? "place" : "pickup", ev.requestId(), result);
			return true;
		}
		if (op == Proto.PROP_OP_PLACE) {
			if (p == null || p.op() != Proto.PROP_OP_PLACE || p.data() == null) {
				GmodCraft.LOG.warn("GmodCraft: prop place {} answered, but it isn't pending (expired / another session); no stack consumed", ev.requestId());
				return true;
			}
			if (player == null || !player.getUUID().equals(p.player())) {
				return true;
			}
			consume(player, p.hand(), p.data());
			GmodCraft.LOG.info("GmodCraft: prop place {}: {} placed {} (GMod entity {})", ev.requestId(), player.getPlainTextName(), p.data().model(), ev.entId());
		}
		return true;
	}

	/** After a drain (and before any other event): a give still missing text slots is dropped. */
	public static void endOfSequence() {
		PendingGive g = give;
		if (g != null) {
			give = null;
			GmodCraft.LOG.warn("GmodCraft: prop give {} is missing text slots ({} of {}); dropped (GMod already removed entity {})", g.requestId(),
				g.received()[0], g.chunks(), g.entId());
		}
	}

	private static int clampInt(double v, int lo, int hi) {
		return Double.isFinite(v) ? (int) Math.max(lo, Math.min(hi, Math.round(v))) : lo;
	}

	private static long clampLong(double v) {
		return Double.isFinite(v) ? Math.max(0L, Math.min(0xFFFFFFFFL, Math.round(v))) : 0xFFFFFFFFL;
	}

	private static void given(MinecraftServer server, PendingGive g) {
		PropData d = PropData.parse(g.text(), g.skin(), g.color());
		if (d == null) {
			GmodCraft.LOG.warn("GmodCraft: prop give {}: no model in its text; dropped", g.requestId());
			return;
		}
		ServerPlayer player = ServerHost.playerBySteamId(server, g.steamId());
		if (player == null) {
			GmodCraft.LOG.warn("GmodCraft: prop give {} ({}) for SteamID {}: no Minecraft player plays as them; the prop is lost", g.requestId(), d.model(),
				Long.toUnsignedString(g.steamId()));
			return;
		}
		ItemStack stack = makeStack(d, 1);
		if (!(player.getInventory().add(stack) && stack.isEmpty())) {
			player.drop(stack, false, Prediction.SERVER_ONLY);
		}
		GmodCraft.LOG.info("GmodCraft: prop give {}: {} picked up {} (GMod entity {})", g.requestId(), player.getPlainTextName(), d.model(), g.entId());
	}

	/** One stack of {@code d}: the hand it was used from first, then the inventory. Creative keeps it. */
	private static void consume(ServerPlayer player, InteractionHand hand, PropData d) {
		if (player.hasInfiniteMaterials()) {
			return;
		}
		ItemStack held = player.getItemInHand(hand);
		if (d.equals(data(held))) {
			held.shrink(1);
			return;
		}
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (d.equals(data(s))) {
				s.shrink(1);
				inv.setChanged();
				return;
			}
		}
		GmodCraft.LOG.info("GmodCraft: prop placed, but {} no longer has a stack of {}", player.getPlainTextName(), d.model());
	}
}
