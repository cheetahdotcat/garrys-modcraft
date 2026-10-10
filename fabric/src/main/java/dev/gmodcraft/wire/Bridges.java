package dev.gmodcraft.wire;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * The redstone side of the Wiremod bridge (P7, protocol v16), server thread only.
 *
 * <p>Works only while the GMod server reports Wiremod (ServerState kServerWiremod) and the current
 * map's slot is known, and only for bridges in the overworld inside that slot. Then every loaded
 * bridge is announced once (kEvBridgePlaced, again for a new link session, map or slot), its input
 * levels go out when they change (kEvBridgeInputs, at most one per bridge per tick, latest wins),
 * and a broken bridge is reported (kEvBridgeRemoved). A chunk unload sends nothing: the GMod entity
 * (and its wires) stays, and the bridge is announced again when the chunk loads. GMod's outputs
 * (kHostEvBridgeOutputs) are applied here, followed by a neighbour update. When the bridge stops
 * working (link down, no Wiremod, map change) every driven face is released.
 */
public final class Bridges {
	public static final ResourceKey<Block> BLOCK_KEY =
		ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "redstone_bridge"));
	public static final ResourceKey<Item> ITEM_KEY =
		ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "redstone_bridge"));
	public static final RedstoneBridgeBlock BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
		new RedstoneBridgeBlock(BlockBehaviour.Properties.of()
			.setId(BLOCK_KEY)
			.mapColor(MapColor.COLOR_RED)
			.strength(1.5F, 6.0F)
			.sound(SoundType.COPPER)
			.isRedstoneConductor((state, level, pos) -> false)));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
		new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix()
			.component(DataComponents.LORE, new ItemLore(List.of(
				Component.translatable("block.gmodcraft.redstone_bridge.tooltip").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)),
				Component.translatable("block.gmodcraft.redstone_bridge.tooltip2").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))))));
	public static final BlockEntityType<RedstoneBridgeBlockEntity> BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "redstone_bridge"), new BlockEntityType<>(RedstoneBridgeBlockEntity::new, Set.of(BLOCK)));
	private static final ResourceKey<CreativeModeTab> REDSTONE_TAB =
		ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("redstone_blocks"));

	/** What GMod was told about one bridge: which block entity, and the inputs last sent. */
	private record Sent(RedstoneBridgeBlockEntity be, int inputs) {
	}

	// Loaded overworld bridges (registered on their first server tick).
	private static final Map<BlockPos, RedstoneBridgeBlockEntity> LOADED = new HashMap<>();
	// Unloads and breaks, possibly from other threads: taken in at the next tick.
	private static final ConcurrentLinkedQueue<RedstoneBridgeBlockEntity> UNLOADED = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<BlockPos> BROKEN = new ConcurrentLinkedQueue<>();
	private static final Map<BlockPos, Sent> SENT = new HashMap<>();
	private static boolean active;
	private static int activeGeneration;
	private static int activeWorldId;
	private static int activeOx;
	private static int activeOz;
	private static int activeOy;  // v21: a new vertical offset moves every bridge's GMod entity
	private static int droppedOutputs;
	/** Bridges announced to GMod at most (GMod's wire entity cap, wirebridge.lua W.MAX_ENTITIES). */
	static final int MAX_BRIDGES = 512;
	/** Placed + inputs events per server tick at most (the event ring has 1024 slots shared with combat). */
	static final int EVENTS_PER_TICK = 64;
	private static final List<BlockPos> ORDER = new ArrayList<>();
	private static int cursor;
	private static boolean capLogged;

	private Bridges() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(REDSTONE_TAB).register(output -> output.accept(ITEM));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LOADED.clear();
			UNLOADED.clear();
			BROKEN.clear();
			SENT.clear();
			active = false;
		});
	}

	static void loaded(RedstoneBridgeBlockEntity be) {
		if (be.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
			LOADED.put(be.getBlockPos().immutable(), be);
		}
	}

	static void unloaded(RedstoneBridgeBlockEntity be) {
		UNLOADED.add(be);
	}

	/** The block was broken or replaced (not a chunk unload). */
	static void broken(ServerLevel level, BlockPos pos) {
		if (level.dimension() == Level.OVERWORLD) {
			BROKEN.add(pos.immutable());
		}
	}

	/** Whether a bridge at {@code pos} would work: overworld, inside the current map's slot. */
	public static boolean inSlot(Level level, BlockPos pos) {
		if (level.dimension() != Level.OVERWORLD || !ServerHost.slotKnown()) {
			return false;
		}
		return SlotArea.contains(ServerHost.slotOriginX(), ServerHost.slotOriginZ(), pos.getX(), pos.getZ());
	}

	private static boolean inActiveSlot(BlockPos pos) {
		return SlotArea.contains(activeOx, activeOz, pos.getX(), pos.getZ());
	}

	public static void tick(MinecraftServer server) {
		for (RedstoneBridgeBlockEntity be; (be = UNLOADED.poll()) != null; ) {
			LOADED.remove(be.getBlockPos(), be);
		}
		ServerLink link = ServerLink.INSTANCE;
		boolean want = (ServerHost.wiremod() || ServerHost.mapIo()) && ServerHost.slotKnown(); // R2: map links work without Wiremod
		if (active && (!want || link.generation() != activeGeneration || ServerHost.worldId() != activeWorldId
			|| ServerHost.slotOriginX() != activeOx || ServerHost.slotOriginZ() != activeOz || ServerHost.slotOriginY() != activeOy)) {
			deactivate(server);
		}
		if (!want) {
			BROKEN.clear();
			return;
		}
		if (!active) {
			active = true;
			activeGeneration = link.generation();
			activeWorldId = ServerHost.worldId();
			activeOx = ServerHost.slotOriginX();
			activeOz = ServerHost.slotOriginZ();
			activeOy = ServerHost.slotOriginY();
			GmodCraft.LOG.info("GmodCraft: redstone bridges on ({}, map {}, {} loaded)", ServerHost.wiremod() ? "Wiremod and map links" : "map links",
				Integer.toHexString(activeWorldId), LOADED.size());
		}
		// Removals first (they free cap room); one the ring can't take now is tried again next tick.
		List<BlockPos> retry = new ArrayList<>();
		for (BlockPos pos; (pos = BROKEN.poll()) != null; ) {
			if (inActiveSlot(pos)) {
				if (push(Proto.EV_BRIDGE_REMOVED, pos, 0)) {
					SENT.remove(pos);
				} else {
					retry.add(pos);
				}
			}
		}
		BROKEN.addAll(retry);
		for (Iterator<Map.Entry<BlockPos, Sent>> it = SENT.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<BlockPos, Sent> e = it.next();
			if (LOADED.get(e.getKey()) != e.getValue().be()) {
				it.remove(); // unloaded (or a new block entity there): announced again once loaded
			}
		}
		// Placed / inputs events: at most EVENTS_PER_TICK per tick (a clock farm must not crowd out
		// acks and hits on the shared ring), round-robin so every bridge gets its turn; at most
		// MAX_BRIDGES announced (GMod's entity cap). SENT is recorded only for events the ring took,
		// so anything dropped goes out on a later tick.
		ORDER.clear();
		for (BlockPos pos : LOADED.keySet()) {
			if (inActiveSlot(pos)) {
				ORDER.add(pos);
			}
		}
		int n = ORDER.size();
		int budget = EVENTS_PER_TICK;
		int skippedCap = 0;
		int k = 0;
		for (; k < n && budget > 0; k++) {
			BlockPos pos = ORDER.get((cursor + k) % n);
			RedstoneBridgeBlockEntity be = LOADED.get(pos);
			int inputs = be.packedInputs();
			Sent sent = SENT.get(pos);
			int type;
			if (sent == null) {
				if (SENT.size() >= MAX_BRIDGES) {
					skippedCap++;
					continue;
				}
				type = Proto.EV_BRIDGE_PLACED;
			} else if (sent.inputs() != inputs) {
				type = Proto.EV_BRIDGE_INPUTS;
			} else {
				continue;
			}
			budget--;
			if (!push(type, pos, inputs)) {
				break; // ring full: the rest waits for the next tick
			}
			if (type == Proto.EV_BRIDGE_PLACED) {
				be.linkDirty = true; // R2: its link follows the (re)announcement
			}
			SENT.put(pos, new Sent(be, inputs));
		}
		// R2: links of announced bridges that changed (or were announced again), within the same budget.
		for (Map.Entry<BlockPos, Sent> e : SENT.entrySet()) {
			RedstoneBridgeBlockEntity be = e.getValue().be();
			if (!be.linkDirty) {
				continue;
			}
			if (budget-- <= 0) {
				break;
			}
			MapLink l = be.link();
			boolean linked = l != null && l.worldId() == activeWorldId;
			BlockPos pos = e.getKey();
			if (!ServerLink.INSTANCE.pushEvent(Proto.EV_BRIDGE_LINK, linked ? 1 : 0, 0L, pos.getX(), pos.getY(), pos.getZ(), 0.0F, linked ? l.flags() : 0,
				linked ? l.creationId() : -1, activeWorldId, 0)) {
				break;
			}
			be.linkDirty = false;
		}
		cursor = n == 0 ? 0 : (cursor + k) % n; // next tick starts where this one stopped
		if (skippedCap > 0 && !capLogged) {
			capLogged = true;
			GmodCraft.LOG.warn("GmodCraft: more than {} redstone bridges in this map: the extra ones stay inert", MAX_BRIDGES);
		}
	}

	private static boolean push(int type, BlockPos pos, int levels) {
		return ServerLink.INSTANCE.pushEvent(type, 0, 0L, pos.getX(), pos.getY(), pos.getZ(), 0.0F, levels, 0, activeWorldId, 0);
	}

	private static void deactivate(MinecraftServer server) {
		active = false;
		SENT.clear();
		capLogged = false;
		int released = 0;
		for (RedstoneBridgeBlockEntity be : LOADED.values()) {
			be.linkDirty = true;
			if (be.clearOutputs() && be.getLevel() != null) {
				be.getLevel().updateNeighborsAt(be.getBlockPos(), BLOCK, null);
				released++;
			}
		}
		GmodCraft.LOG.info("GmodCraft: redstone bridges off ({} released)", released);
	}

	/** What kAdminBridgeLink did: an AdminResult and a chat message. */
	public record LinkResult(int code, String message) {
	}

	/**
	 * R2 kAdminBridgeLink: link (a = MapCreationID) or unlink (a = -1) the bridge at x/y/z. Needs
	 * kAdminByAdmin or kAdminEveryone. The link is saved with the block and announced (kEvBridgeLink).
	 */
	public static LinkResult adminLink(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		if ((cmd.flags() & (Proto.ADMIN_BY_ADMIN | Proto.ADMIN_EVERYONE)) == 0) {
			return new LinkResult(Proto.ADMIN_NOT_ALLOWED, "not allowed (admins only)");
		}
		BlockPos pos = BlockPos.containing(cmd.x(), cmd.y(), cmd.z());
		if (!ServerHost.slotKnown() || !inSlot(server.overworld(), pos)) {
			return new LinkResult(Proto.ADMIN_OUTSIDE_SLOT, "outside the GMod map's Minecraft area");
		}
		if (!(server.overworld().getBlockEntity(pos) instanceof RedstoneBridgeBlockEntity be)) {
			return new LinkResult(Proto.ADMIN_NOTHING, "no redstone bridge block there");
		}
		if (cmd.a() < 0) {
			if (be.link() == null) {
				return new LinkResult(Proto.ADMIN_NOTHING, "that bridge isn't linked");
			}
			be.setLink(null);
			releaseIfIdle(be);
			GmodCraft.LOG.info("GmodCraft: bridge {} unlinked by {}", pos.toShortString(), Long.toUnsignedString(cmd.steamId()));
			return new LinkResult(Proto.ADMIN_OK, "unlinked");
		}
		MapLink l = MapLink.parse(text, ServerHost.worldId(), ServerHost.currentMap(), cmd.a());
		if (l == null) {
			return new LinkResult(Proto.ADMIN_MALFORMED, "can't link that: '" + text + "'");
		}
		be.setLink(l);
		GmodCraft.LOG.info("GmodCraft: bridge {} linked to map entity {} ({} '{}', {}) on {} by {}", pos.toShortString(), l.creationId(), text.split("\\s+")[0], l.name(),
			l.mode() == Proto.LINK_IN ? "in" : "out", l.map(), Long.toUnsignedString(cmd.steamId()));
		return new LinkResult(Proto.ADMIN_OK, "linked");
	}

	/** An unlinked bridge stops giving the power its link made (GMod sends no outputs for it any more). */
	private static void releaseIfIdle(RedstoneBridgeBlockEntity be) {
		if (!ServerHost.wiremod() && be.clearOutputs() && be.getLevel() != null) {
			be.getLevel().updateNeighborsAt(be.getBlockPos(), BLOCK, null);
		}
	}

	/** kHostEvBridgeOutputs: x/y/z = the block, code = driven mask, flags = levels. */
	public static void hostOutputs(MinecraftServer server, ServerLink.HostEvent ev) {
		BlockPos pos = BlockPos.containing(ev.x(), ev.y(), ev.z());
		RedstoneBridgeBlockEntity be = active && ev.worldId() == activeWorldId && inActiveSlot(pos) && !dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ())
			? LOADED.get(pos) : null;  // not while a re-anchor moves the slot (GMod sends them again on the next kEvBridgePlaced)
		if (be == null || be.isRemoved() || be.getLevel() == null) {
			if (droppedOutputs++ < 5) {
				GmodCraft.LOG.info("GmodCraft: bridge outputs for {} dropped ({})", pos.toShortString(),
					!active ? "bridges off" : ev.worldId() != activeWorldId ? "other map" : !inActiveSlot(pos) ? "outside the map's slot"
						: "no loaded bridge there");
			}
			return;
		}
		if (be.applyOutputs(ev.code(), ev.flags())) {
			be.getLevel().updateNeighborsAt(pos, BLOCK, null);
		}
	}
}
