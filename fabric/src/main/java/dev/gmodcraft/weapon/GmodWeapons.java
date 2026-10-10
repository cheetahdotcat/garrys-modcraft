package dev.gmodcraft.weapon;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.link.WeaponSetCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.jspecify.annotations.Nullable;

/**
 * Hybrid mode, part 1 (D-016, protocol v17): GMod weapons as {@code gmodcraft:gmod_weapon} stacks.
 * Server thread only.
 *
 * <p>The Minecraft inventory owns possession: every tick (after McPlayers) each online player's
 * weapon stacks go to McWeaponSets (rewritten on change and every 2 s), and the GMod server makes
 * the GMod weapons equal them. What GMod does on its side comes back as host events: Give (a
 * weapon picked up there: a stack appears, one per class, a full inventory drops it at the
 * player's feet), Take (one stack goes), State (clips into the stack). Give and Take are acked
 * through the set's lastRequestId. Select (v45) puts the stack GMod switched to into the main hand. While the main hand holds a gmod_weapon, Minecraft attacks,
 * digging and block/entity use are refused (the mouse belongs to the GMod weapon).
 */
public final class GmodWeapons {
	public static final DataComponentType<SwepData> SWEP = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
		Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "swep"),
		DataComponentType.<SwepData>builder().persistent(SwepData.CODEC).networkSynchronized(SwepData.STREAM_CODEC).build());
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "gmod_weapon"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, new GmodWeaponItem(new Item.Properties().setId(ITEM_KEY).stacksTo(1)));

	/** A set is rewritten at least this often (ticks), changed or not. */
	static final int REWRITE_TICKS = 40;
	/** Item entities searched for a State whose stack has left the inventory (blocks). */
	private static final double DROPPED_RADIUS = 32.0;

	/** A Give whose text slots are still being collected. */
	private record PendingGive(long steamId, int requestId, int hash, int category, int clip1, int clip2, int chunks, byte[] text, int[] received,
		int targetSlot) { // v35: inventory slot to put it into, -1 = anywhere
	}

	private static @Nullable PendingGive pending;
	// The last Give / Take carried out per SteamID64 (McWeaponSet::lastRequestId); cleared on a new link session.
	private static final Map<Long, Integer> LAST_REQUEST = new HashMap<>();
	private static int generation = -1;
	private static final WeaponSetCodec.Set[] WRITTEN = new WeaponSetCodec.Set[Proto.MAX_PLAYERS];
	private static final long[] WRITTEN_AT = new long[Proto.MAX_PLAYERS];
	private static int writtenCount;
	private static long ticks;

	private GmodWeapons() {
	}

	public static void init() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> holdsWeapon(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> holdsWeapon(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> holdsWeapon(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> holdsWeapon(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !holdsWeapon(player));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			pending = null;
			LAST_REQUEST.clear();
			generation = -1;
			WeaponTestHook.forget();
		});
	}

	/** The main hand holds a gmod_weapon (with or without its component). */
	public static boolean holdsWeapon(Player player) {
		return player.getMainHandItem().is(ITEM);
	}

	/** The weapon a stack stands for, or null (another item, or an inert gmod_weapon). */
	public static @Nullable SwepData data(ItemStack stack) {
		return stack.is(ITEM) ? stack.get(SWEP) : null;
	}

	/** Class hash of the weapon in the main hand (McState::heldWeapon / McWeaponSet::heldHash), 0 = none. */
	public static int heldHash(Player player) {
		SwepData d = data(player.getMainHandItem());
		return d != null && !d.weaponClass().isEmpty() ? d.hash() : 0; // a component without a class is inert
	}

	/** A new stack for {@code d}, with its category sprite (custom_model_data strings[0]). */
	public static ItemStack makeStack(SwepData d) {
		ItemStack stack = new ItemStack(ITEM);
		stack.set(SWEP, d);
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(d.categoryName()), List.of()));
		return stack;
	}

	// ---- inventory access ------------------------------------------------------------------

	/** Where a player's weapon stacks can be: main inventory (hotbar first), offhand, cursor. */
	private interface SlotVisitor {
		/** Returns true to stop. */
		boolean visit(ItemStack stack, int slot);
	}

	private static final int SLOT_CURSOR = -1;

	private static void forEachSlot(Player player, SlotVisitor v) {
		Inventory inv = player.getInventory();
		int main = inv.getNonEquipmentItems().size();
		for (int i = 0; i < main; i++) {
			if (v.visit(inv.getItem(i), i)) {
				return;
			}
		}
		if (v.visit(inv.getItem(Inventory.SLOT_OFFHAND), Inventory.SLOT_OFFHAND)) {
			return;
		}
		v.visit(player.containerMenu.getCarried(), SLOT_CURSOR);
	}

	private static void setSlot(Player player, int slot, ItemStack stack) {
		if (slot == SLOT_CURSOR) {
			player.containerMenu.setCarried(stack);
		} else {
			player.getInventory().setItem(slot, stack);
		}
	}

	private static boolean hasClass(Player player, int hash) {
		boolean[] found = { false };
		forEachSlot(player, (stack, slot) -> {
			SwepData d = data(stack);
			found[0] = d != null && d.hash() == hash;
			return found[0];
		});
		return found[0];
	}

	/** This player's set as it would be published now. */
	static WeaponSetCodec.Set scan(Player player, long steamId, int lastRequestId) {
		int[] hashes = new int[Proto.MAX_WEAPONS_PER_PLAYER];
		int[] clips = new int[Proto.MAX_WEAPONS_PER_PLAYER];
		int[] n = { 0 };
		forEachSlot(player, (stack, slot) -> {
			SwepData d = data(stack);
			if (d != null && !d.weaponClass().isEmpty()) {
				hashes[n[0]] = d.hash();
				clips[n[0]] = d.clip1();
				n[0]++;
			}
			return n[0] >= Proto.MAX_WEAPONS_PER_PLAYER;
		});
		int flags = player.isDeadOrDying() ? Proto.WEAPON_SET_DEAD : 0;
		return new WeaponSetCodec.Set(steamId, heldHash(player), lastRequestId, flags, n[0], hashes, clips);
	}

	// ---- weapon sets (every tick, after McPlayers) -------------------------------------------

	/**
	 * Writes McWeaponSets: set i for {@code players.get(i)} (McPlayers' order and SteamIDs), then the
	 * test hook's stand-in players, if any. A set is rewritten when it changed or every 2 s.
	 */
	public static void publish(MinecraftServer server, ServerLink link, List<ServerPlayer> players, List<Long> steamIds) {
		int gen = link.generation();
		if (gen != generation) {
			generation = gen;
			LAST_REQUEST.clear(); // the new session's GMod counts its requests from 1 again
			java.util.Arrays.fill(WRITTEN, null);
			writtenCount = 0;
			pending = null;
		}
		ticks++;
		List<ServerPlayer> all = new ArrayList<>(players);
		List<Long> ids = new ArrayList<>(steamIds);
		WeaponTestHook.append(server, all, ids);
		int n = Math.min(all.size(), Proto.MAX_PLAYERS);
		for (int i = 0; i < n; i++) {
			long steamId = ids.get(i);
			WeaponSetCodec.Set set = scan(all.get(i), steamId, steamId != 0 ? LAST_REQUEST.getOrDefault(steamId, 0) : 0);
			if (!set.sameAs(WRITTEN[i]) || ticks - WRITTEN_AT[i] >= REWRITE_TICKS) {
				link.writeWeaponSet(i, set);
				WRITTEN[i] = set;
				WRITTEN_AT[i] = ticks;
			}
		}
		for (int i = n; i < writtenCount; i++) {
			// No longer in use: a reader that still looks finds nobody.
			WeaponSetCodec.Set empty = new WeaponSetCodec.Set(0, 0, 0, 0, 0, new int[0], new int[0]);
			link.writeWeaponSet(i, empty);
			WRITTEN[i] = null;
		}
		writtenCount = n;
		link.writeWeaponSetCount(n);
	}

	// ---- host events ---------------------------------------------------------------------------

	/** Takes the v17 weapon events out of the host event stream; true when {@code ev} was one. */
	public static boolean accept(MinecraftServer server, ServerLink.HostEvent ev) {
		if (ev.type() == Proto.HOST_EV_WEAPON_TEXT) {
			PendingGive p = pending;
			if (p == null || ev.text() == null || ev.code() != p.received()[0]) {
				GmodCraft.LOG.warn("GmodCraft: weapon text slot out of sequence (chunk {}); dropped", ev.code());
				if (p != null) {
					pending = null;
					ack(p.steamId(), p.requestId());
				}
				return true;
			}
			byte[] t = ev.text();
			System.arraycopy(t, 0, p.text(), p.received()[0] * Proto.WEAPON_TEXT_CHUNK_BYTES, Math.min(t.length, Proto.WEAPON_TEXT_CHUNK_BYTES));
			p.received()[0]++;
			if (p.received()[0] == p.chunks()) {
				pending = null;
				give(server, p);
			}
			return true;
		}
		endOfSequence();
		switch (ev.type()) {
			case Proto.HOST_EV_WEAPON_GIVE -> {
				int chunks = ev.code();
				if (ev.requestId() == 0 || chunks < 1 || chunks > Proto.WEAPON_TEXT_MAX_CHUNKS) {
					GmodCraft.LOG.warn("GmodCraft: malformed weapon give {} ({} text slots); dropped", ev.requestId(), chunks);
					ack(ev.steamId(), ev.requestId());
					return true;
				}
				pending = new PendingGive(ev.steamId(), ev.requestId(), ev.a(), ev.flags(), clip(ev.x()), clip(ev.y()), chunks,
					new byte[Proto.WEAPON_TEXT_MAX_BYTES], new int[1], ev.entId() - 1);
				return true;
			}
			case Proto.HOST_EV_WEAPON_TAKE -> {
				take(server, ev);
				return true;
			}
			case Proto.HOST_EV_WEAPON_STATE -> {
				state(server, ev);
				return true;
			}
			case Proto.HOST_EV_SELECT_WEAPON -> {
				select(server, ev);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** After a drain (and before any other event): a Give still missing text slots is dropped (but acked). */
	public static void endOfSequence() {
		PendingGive p = pending;
		if (p != null) {
			pending = null;
			GmodCraft.LOG.warn("GmodCraft: weapon give {} is missing text slots ({} of {}); dropped", p.requestId(), p.received()[0], p.chunks());
			ack(p.steamId(), p.requestId());
		}
	}

	private static void ack(long steamId, int requestId) {
		if (steamId != 0 && requestId != 0) {
			LAST_REQUEST.put(steamId, requestId);
		}
	}

	private static int clip(double v) {
		return Double.isFinite(v) ? (int) Math.max(-1, Math.min(1_000_000, Math.round(v))) : -1;
	}

	private static @Nullable ServerPlayer resolve(MinecraftServer server, long steamId) {
		ServerPlayer test = WeaponTestHook.player(steamId);
		return test != null ? test : ServerHost.playerBySteamId(server, steamId);
	}

	private static void give(MinecraftServer server, PendingGive g) {
		ack(g.steamId(), g.requestId());
		String[] t = WeaponText.parse(g.text(), g.hash());
		if (t == null) {
			GmodCraft.LOG.warn("GmodCraft: weapon give {}: malformed class text (or its hash isn't {}); dropped", g.requestId(), Integer.toUnsignedString(g.hash()));
			return;
		}
		ServerPlayer player = resolve(server, g.steamId());
		if (player == null) {
			GmodCraft.LOG.info("GmodCraft: weapon give {} ({}) for SteamID {}: no Minecraft player plays as them", g.requestId(), t[0],
				Long.toUnsignedString(g.steamId()));
			return;
		}
		if (g.targetSlot() >= 0 && placeInSlot(player, g, t)) {
			return;
		}
		if (hasClass(player, g.hash())) {
			GmodCraft.LOG.debug("GmodCraft: weapon give {}: {} already has {}", g.requestId(), player.getPlainTextName(), t[0]);
			return;
		}
		ItemStack stack = makeStack(new SwepData(t[0], t[1], g.category(), g.clip1(), g.clip2()));
		if (player.getInventory().add(stack) && stack.isEmpty()) {
			GmodCraft.LOG.info("GmodCraft: weapon give {}: {} got {}", g.requestId(), player.getPlainTextName(), t[0]);
		} else {
			ItemEntity dropped = player.drop(stack, false, Prediction.SERVER_ONLY);
			GmodCraft.LOG.info("GmodCraft: weapon give {}: {}'s inventory is full; {} dropped at their feet{}", g.requestId(), player.getPlainTextName(), t[0],
				dropped == null ? " (couldn't spawn the item)" : "");
		}
	}

	/**
	 * v35 (S1): a Give with a target slot (a weapon dropped onto a hotbar slot in the GMod
	 * spawnmenu's Minecraft tab). One of that class the player has already moves there (swapping
	 * with what is there); else a new stack goes there and an item already there moves elsewhere in
	 * the inventory (or drops at the feet). Returns false (the plain Give follows) for a slot
	 * outside the main inventory.
	 */
	private static boolean placeInSlot(ServerPlayer player, PendingGive g, String[] t) {
		Inventory inv = player.getInventory();
		int target = g.targetSlot();
		if (target >= inv.getNonEquipmentItems().size()) {
			return false;
		}
		int[] have = { Integer.MIN_VALUE };
		forEachSlot(player, (stack, slot) -> {
			SwepData d = data(stack);
			if (d != null && d.hash() == g.hash()) {
				have[0] = slot;
				return true;
			}
			return false;
		});
		ItemStack old = inv.getItem(target);
		if (have[0] != Integer.MIN_VALUE) {
			if (have[0] != target) {
				ItemStack mine = have[0] == SLOT_CURSOR ? player.containerMenu.getCarried() : inv.getItem(have[0]);
				setSlot(player, have[0], old);
				inv.setItem(target, mine);
			}
			GmodCraft.LOG.info("GmodCraft: weapon give {}: {}'s {} moved to slot {}", g.requestId(), player.getPlainTextName(), t[0], target);
			return true;
		}
		inv.setItem(target, makeStack(new SwepData(t[0], t[1], g.category(), g.clip1(), g.clip2())));
		if (!old.isEmpty() && !(inv.add(old) && old.isEmpty())) {
			player.drop(old, false, Prediction.SERVER_ONLY);
		}
		GmodCraft.LOG.info("GmodCraft: weapon give {}: {} got {} in slot {}", g.requestId(), player.getPlainTextName(), t[0], target);
		return true;
	}

	private static void take(MinecraftServer server, ServerLink.HostEvent ev) {
		ack(ev.steamId(), ev.requestId());
		ServerPlayer player = resolve(server, ev.steamId());
		if (player == null) {
			return;
		}
		int hash = ev.a();
		int[] at = { Integer.MIN_VALUE };
		Inventory inv = player.getInventory();
		SwepData held = data(inv.getSelectedItem());
		if (held != null && held.hash() == hash) {
			at[0] = inv.getSelectedSlot();
		} else {
			SwepData off = data(inv.getItem(Inventory.SLOT_OFFHAND));
			if (off != null && off.hash() == hash) {
				at[0] = Inventory.SLOT_OFFHAND;
			} else {
				forEachSlot(player, (stack, slot) -> {
					SwepData d = data(stack);
					if (d != null && d.hash() == hash) {
						at[0] = slot;
						return true;
					}
					return false;
				});
			}
		}
		if (at[0] == Integer.MIN_VALUE) {
			GmodCraft.LOG.debug("GmodCraft: weapon take {}: {} has no stack of {}", ev.requestId(), player.getPlainTextName(), Integer.toUnsignedString(hash));
			return;
		}
		setSlot(player, at[0], ItemStack.EMPTY);
		GmodCraft.LOG.info("GmodCraft: weapon take {}: removed {} from {} (slot {})", ev.requestId(), Integer.toUnsignedString(hash), player.getPlainTextName(), at[0]);
	}

	/**
	 * v45: GMod switched to weapon a on its own; its stack goes into the main hand the way pick-item
	 * does it (ServerGamePacketListenerImpl.tryPickItem): a hotbar slot is selected, a main inventory
	 * slot is swapped into the hotbar (Inventory.pickSlot), then the held slot and the inventory go to
	 * the client. In the offhand: the hands swap (as the swap-hands key). A stack on the cursor and any
	 * open menu but the inventory are left alone (GMod's switch then lapses: Minecraft's hand wins).
	 */
	private static void select(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerPlayer player = resolve(server, ev.steamId());
		if (player == null || player.isSpectator() || player.isDeadOrDying() || player.containerMenu != player.inventoryMenu) {
			return;
		}
		Inventory inv = player.getInventory();
		int[] main = new int[inv.getNonEquipmentItems().size()];
		for (int i = 0; i < main.length; i++) {
			main[i] = classHash(inv.getItem(i));
		}
		SelectPlan plan = SelectPlan.plan(main, Inventory.getSelectionSize(), inv.getSelectedSlot(), classHash(inv.getItem(Inventory.SLOT_OFFHAND)), ev.a());
		switch (plan.kind()) {
			case NONE -> {
				return;
			}
			case SELECT -> inv.setSelectedSlot(plan.slot());
			case PICK -> inv.pickSlot(plan.slot());
			case OFFHAND -> {
				ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
				player.setItemInHand(InteractionHand.OFF_HAND, player.getItemInHand(InteractionHand.MAIN_HAND));
				player.setItemInHand(InteractionHand.MAIN_HAND, off);
				player.stopUsingItem();
			}
		}
		player.connection.send(new ClientboundSetHeldSlotPacket(inv.getSelectedSlot()));
		player.inventoryMenu.broadcastChanges();
		GmodCraft.LOG.info("GmodCraft: weapon select: {} holds {} ({} {})", player.getPlainTextName(), Integer.toUnsignedString(ev.a()), plan.kind(), plan.slot());
	}

	/** Class hash of a weapon stack, 0 for anything else (or an inert one). */
	private static int classHash(ItemStack stack) {
		SwepData d = data(stack);
		return d != null && !d.weaponClass().isEmpty() ? d.hash() : 0;
	}

	private static void state(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerPlayer player = resolve(server, ev.steamId());
		if (player == null) {
			return;
		}
		int hash = ev.a(), c1 = clip(ev.x()), c2 = clip(ev.y());
		boolean[] any = { false };
		forEachSlot(player, (stack, slot) -> {
			SwepData d = data(stack);
			if (d != null && d.hash() == hash) {
				any[0] = true;
				if (d.clip1() != c1 || d.clip2() != c2) {
					stack.set(SWEP, d.withClips(c1, c2));
				}
			}
			return false;
		});
		if (any[0]) {
			return;
		}
		// Put into a container the player has open (chest, shulker box, ...): that stack. A container
		// closed again before the strip (0.5 s after it left the inventory) keeps the clip the stack
		// last had (stale until GMod sends clips on change, H2).
		if (player.containerMenu != player.inventoryMenu) {
			for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
				if (slot.container == player.getInventory()) {
					continue;
				}
				ItemStack stack = slot.getItem();
				SwepData d = data(stack);
				if (d != null && d.hash() == hash) {
					stack.set(SWEP, d.withClips(c1, c2));
					slot.setChanged();
					return;
				}
			}
		}
		// Minecraft dropped it (Q, death drop off): the newest such item this player threw nearby.
		ItemEntity best = null;
		for (ItemEntity e : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(DROPPED_RADIUS),
			e -> e.getOwner() == player && data(e.getItem()) != null && data(e.getItem()).hash() == hash)) {
			if (best == null || e.getAge() < best.getAge()) {
				best = e;
			}
		}
		if (best != null) {
			ItemStack copy = best.getItem().copy();
			copy.set(SWEP, data(copy).withClips(c1, c2));
			best.setItem(copy);
			GmodCraft.LOG.debug("GmodCraft: weapon state: clips {}/{} into {}'s dropped {}", c1, c2, player.getPlainTextName(), Integer.toUnsignedString(hash));
		}
	}
}
