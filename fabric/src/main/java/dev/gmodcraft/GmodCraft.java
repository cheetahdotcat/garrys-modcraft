package dev.gmodcraft;

import dev.gmodcraft.combat.SkyCombat;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.entity.EquipmentSlot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import dev.gmodcraft.link.Proto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GmodCraft implements ModInitializer {
	public static final String MOD_ID = "gmodcraft";
	public static final String WORLD_NAME = "GmodCraft";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final String KIT2_TAG = "gmodcraft_builder_kit";

	@Override
	public void onInitialize() {
		GmodCraftConfig.load();
		// Players use the smooth triangle collider, never the host's voxels: client-side movement
		// while the client link is up, and the server's re-check of that movement while the server
		// link is up (otherwise the server sees the smooth position dip into a voxel and teleports
		// the player back every few ticks). Installed here, not from client code, so it is right on
		// a dedicated server too; each entity's own side decides which link counts.
		dev.gmodcraft.world.SkyCollision.setSmoothCollider(e -> e instanceof net.minecraft.world.entity.player.Player
			&& (e.level().isClientSide() ? dev.gmodcraft.link.ClientLink.INSTANCE.active() : dev.gmodcraft.link.ServerLink.INSTANCE.active()));
		SkyCombat.init(); // first: its end-of-tick actor sync runs before ServerHost's host events
		ServerHost.init();
		dev.gmodcraft.wire.Bridges.init(); // P7: redstone <-> Wiremod bridge block
		dev.gmodcraft.micro.Microblocks.init(); // 0.5: microblocks + hand saw
		dev.gmodcraft.demo.DemoWorld.init(); // P7b: demo builds + /gmodcraft demo
		dev.gmodcraft.tools.ToolWorld.init(); // P7b-2: STools (repair, blocks, resync) + /gmodcraft tool
		dev.gmodcraft.slot.SlotJobs.init();     // P8 WP2: slot re-anchor / undo / hash + crash recovery at start
		dev.gmodcraft.slot.PlayerAnchors.init(); // P8 WP2: offline players follow a re-anchor when they join
		dev.gmodcraft.weapon.GmodWeapons.init(); // v17 hybrid mode: GMod weapons as items
		dev.gmodcraft.prop.GmodProps.init(); // v33 (P1): GMod props as items
		dev.gmodcraft.net.SkyNet.init();
		dev.gmodcraft.world.SkyDig.init();
		// P8 WP3: the flat world types' generator (both sides: single player makes its world on the client).
		net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.CHUNK_GENERATOR,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "slot_flat"), dev.gmodcraft.world.SlotFlatGenerator.CODEC);
		// W2: the underground world type (vanilla terrain under the maps' floor).
		net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.CHUNK_GENERATOR,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "underground"), dev.gmodcraft.world.UndergroundGenerator.CODEC);
		// After SlotJobs' crash recovery (registered first): the slot table, so the flat generator knows the maps' footprints.
		ServerLifecycleEvents.SERVER_STARTING.register(dev.gmodcraft.world.MapSlots::prime);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> dev.gmodcraft.world.FlatColumns.publish(dev.gmodcraft.world.FlatColumns.EMPTY));
		ServerLifecycleEvents.SERVER_STARTED.register(GmodCraft::configureServer);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			forceGamemode(handler.getPlayer());
			giveStarterKit(handler.getPlayer());
			giveBuilderKit(handler.getPlayer());
			dressTestGuest(handler.getPlayer());
		});
	}

	/** The mirror world is a void that only exists to host the player; GMod drives time and spawning. */
	private static void configureServer(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.PLAYER_MOVEMENT_CHECK, false, server);
		rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		applyRules(server, GmodCraftConfig.rules(), false);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
		LOG.info("GmodCraft: mirror world configured ({} world)", ServerRules.typeName(worldType(server)));
	}

	/**
	 * P8 WP3: the server rules (config/gmodcraft.properties, ServerRules). keepInventory, digIntoMap,
	 * noclipMc, mobSpawning, mobCapPercent and the damage scale always apply; gamemode / forceGamemode /
	 * pvp / difficulty only when set (else
	 * the world keeps its own). live: an admin changed them (kAdminSetRules): a forced game mode reaches
	 * everyone online at once.
	 */
	public static void applyRules(MinecraftServer server, ServerRules r, boolean live) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.KEEP_INVENTORY, r.keepInventory(), server);
		if (r.isSet(ServerRules.PVP)) {
			rules.set(GameRules.PVP, r.pvp(), server);
		}
		GameType mode = GameType.byId(r.gameMode());
		if (r.isSet(ServerRules.GAMEMODE) && server.getDefaultGameType() != mode) {
			server.setDefaultGameType(mode);
		}
		if (r.isSet(ServerRules.FORCE_GAMEMODE)) {
			server.setForceGameMode(r.forceGamemode());
		}
		// v34: difficulty only when set (else the world keeps its own); spawning always (default off, as
		// configureServer had it); the caps are read live (MobCategoryCapMixin)
		if (r.isSet(ServerRules.DIFFICULTY) && server.getWorldData().getDifficulty().getId() != r.difficulty()) {
			server.setDifficulty(net.minecraft.world.Difficulty.byId(r.difficulty()), true);
		}
		rules.set(GameRules.SPAWN_MOBS, r.mobSpawning(), server);
		rules.set(GameRules.SPAWN_MONSTERS, r.mobSpawning(), server);
		if (live && r.forceGamemode()) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				forceGamemode(p);
			}
		}
		LOG.info("GmodCraft: rules applied{}: {}", live ? " (changed by an admin)" : "", GmodCraftConfig.describe(r));
	}

	/** forceGamemode: the player gets the rules' game mode (on join, and when an admin turns it on). */
	private static void forceGamemode(ServerPlayer player) {
		ServerRules r = GmodCraftConfig.rules();
		GameType mode = forcedMode(player.level().getServer(), r);
		if (r.forceGamemode() && player.gameMode() != mode) {
			player.setGameMode(mode);
			LOG.info("GmodCraft: {} gets game mode {} (forceGamemode)", player.getName().getString(), mode.getName());
		}
	}

	/**
	 * The game mode forceGamemode puts players in: the rules' gamemode when the file / an admin set it,
	 * else the server's own default (a creative-default world stays creative: review WP3 #1).
	 */
	static GameType forcedMode(MinecraftServer server, ServerRules r) {
		return r.isSet(ServerRules.GAMEMODE) ? GameType.byId(r.gameMode()) : server.getDefaultGameType();
	}

	/** Proto.WORLD_* of the running world: what generates the overworld. */
	public static int worldType(MinecraftServer server) {
		var overworld = server.overworld();
		if (overworld == null) {
			return Proto.WORLD_OTHER;
		}
		var gen = overworld.getChunkSource().getGenerator();
		if (gen instanceof dev.gmodcraft.world.UndergroundGenerator) {
			return Proto.WORLD_UNDERGROUND;
		}
		if (gen instanceof dev.gmodcraft.world.SlotFlatGenerator g) {
			return g.voidMaps() ? Proto.WORLD_FLAT_VOID_MAPS : Proto.WORLD_FLAT_EVERYWHERE;
		}
		if (gen instanceof net.minecraft.world.level.levelgen.FlatLevelSource f && f.settings().getLayers().stream().allMatch(s -> s == null || s.isAir())) {
			return Proto.WORLD_VOID;
		}
		return Proto.WORLD_OTHER;
	}

	/** What McServerState publishes about the rules (the game's own state where it has one). */
	public static dev.gmodcraft.link.ServerLink.Rules publishedRules(MinecraftServer server) {
		ServerRules r = GmodCraftConfig.rules();
		GameRules rules = server.getGameRules();
		int flags = Proto.RULES_VALID | r.modFlags() | (rules.get(GameRules.PVP) ? Proto.RULE_PVP : 0)
			| (rules.get(GameRules.KEEP_INVENTORY) ? Proto.RULE_KEEP_INVENTORY : 0) | (r.forceGamemode() || server.forceGameMode() ? Proto.RULE_FORCE_GAMEMODE : 0);
		flags = (flags & ~Proto.RULE_MOB_SPAWNING) | (rules.get(GameRules.SPAWN_MOBS) ? Proto.RULE_MOB_SPAWNING : 0);
		return new dev.gmodcraft.link.ServerLink.Rules(flags, server.getDefaultGameType().getId(), worldType(server),
			dev.gmodcraft.world.MapSlots.floorYOrConfig(server), r.hostDamagePerMcDamage(), server.getWorldData().getDifficulty().getId(), r.mobCapPercent());
	}

	/**
	 * Local multiplayer test guests (tools/fake_guest.py; named Guest, Guest2, ...) wear a random
	 * mix of iron and diamond armour, so they're easy to tell apart.
	 */
	private static void dressTestGuest(ServerPlayer player) {
		if (!player.getName().getString().startsWith("Guest")) {
			return;
		}
		var random = player.getRandom();
		EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
		net.minecraft.world.item.Item[][] pieces = {
			{ Items.IRON_HELMET, Items.DIAMOND_HELMET },
			{ Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE },
			{ Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS },
			{ Items.IRON_BOOTS, Items.DIAMOND_BOOTS },
		};
		for (int i = 0; i < slots.length; i++) {
			player.setItemSlot(slots[i], new ItemStack(pieces[i][random.nextBoolean() ? 1 : 0]));
		}
		LOG.info("GmodCraft: dressed test guest {} in iron and diamond", player.getName().getString());
	}

	private static void giveStarterKit(ServerPlayer player) {
		if (!player.getInventory().isEmpty()) {
			return;
		}
		player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
		player.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
		player.getInventory().add(new ItemStack(Items.BOW));
		player.getInventory().add(new ItemStack(Items.COOKED_BEEF, 32));
		player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
		player.getInventory().add(new ItemStack(Items.TORCH, 32));
		player.getInventory().add(new ItemStack(Items.ARROW, 64));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		LOG.info("GmodCraft: gave starter kit to {}", player.getName().getString());
	}

	/**
	 * Once per player: armor (Skyrim's enemies hit back now) and building materials, since there is
	 * no Minecraft terrain to mine in Skyrim.
	 */
	private static void giveBuilderKit(ServerPlayer player) {
		if (player.entityTags().contains(KIT2_TAG)) {
			return;
		}
		equipIfEmpty(player, EquipmentSlot.HEAD, Items.IRON_HELMET);
		equipIfEmpty(player, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
		equipIfEmpty(player, EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
		equipIfEmpty(player, EquipmentSlot.FEET, Items.IRON_BOOTS);
		var inventory = player.getInventory();
		inventory.add(new ItemStack(Items.COBBLESTONE, 64));
		inventory.add(new ItemStack(Items.STONE_BRICKS, 64));
		inventory.add(new ItemStack(Items.OAK_LOG, 64));
		inventory.add(new ItemStack(Items.GLASS, 64));
		inventory.add(new ItemStack(Items.OAK_STAIRS, 64));
		inventory.add(new ItemStack(Items.OAK_SLAB, 64));
		inventory.add(new ItemStack(Items.OAK_DOOR, 8));
		inventory.add(new ItemStack(Items.LADDER, 32));
		inventory.add(new ItemStack(Items.LANTERN, 16));
		inventory.add(new ItemStack(Items.CRAFTING_TABLE));
		inventory.add(new ItemStack(Items.WATER_BUCKET));
		inventory.add(new ItemStack(Items.ARROW, 64));
		inventory.add(new ItemStack(Items.GOLDEN_APPLE, 4));
		inventory.add(new ItemStack(dev.gmodcraft.wire.Bridges.ITEM, 4)); // P7 (works only with Wiremod on the GMod server)
		player.addTag(KIT2_TAG);
		LOG.info("GmodCraft: gave builder kit to {}", player.getName().getString());
	}

	private static void equipIfEmpty(ServerPlayer player, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		if (player.getItemBySlot(slot).isEmpty()) {
			player.setItemSlot(slot, new ItemStack(item));
		} else {
			player.getInventory().add(new ItemStack(item));
		}
	}
}
