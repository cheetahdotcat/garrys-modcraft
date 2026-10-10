package dev.gmodcraft.combat;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.GmodCraftConfig;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.ServerLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * Combat between Minecraft players and GMod NPCs, server side, over the server link only.
 *
 * <p>Every GMod NPC the host lists gets an invisible {@link HostActorEntity} at its exact position.
 * Minecraft weapons hit those like any mob; the resulting damage is sent to the GMod server
 * (kEvHitActor, with the hitting player's SteamID64), which applies it to the real NPC. GMod's hits
 * on a player come back as host events (kHostEvHurt, by SteamID64) and are applied as Minecraft
 * damage from the attacker's stand-in, so armor, shields, knockback, hurt sounds and death all work
 * the Minecraft way.
 */
public final class SkyCombat {
	public static final ResourceKey<EntityType<?>> HOST_ACTOR_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, "host_actor"));
	public static final EntityType<HostActorEntity> HOST_ACTOR = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		HOST_ACTOR_KEY,
		EntityType.Builder.<HostActorEntity>of(HostActorEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.noSave()
			.noSummon()
			.noLootTable()
			.clientTrackingRange(10)
			.updateInterval(1)
			.build(HOST_ACTOR_KEY)
	);

	private static final Map<Integer, HostActorEntity> PROXIES = new HashMap<>();
	private static final List<GLink.Actor> ACTORS = new ArrayList<>();

	private SkyCombat() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(HOST_ACTOR, LivingEntity.createLivingAttributes());
		ServerTickEvents.END_SERVER_TICK.register(SkyCombat::serverTick);
	}

	public static @Nullable HostActorEntity proxy(int entId) {
		return PROXIES.get(entId);
	}

	private static void serverTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		ServerLink link = ServerLink.INSTANCE;
		if (!ServerHost.linked() || players.isEmpty()) {
			removeAll();
			if (ProjectileTestHook.ENABLED && ServerHost.linked() && link.readActors(ACTORS)) {
				ProjectileTestHook.tick(server.overworld(), ACTORS); // the test shoots on a dedicated server with nobody on it
			}
			return;
		}
		ServerLevel level = players.getFirst().level();
		for (ServerPlayer player : players) {
			pickUpNearby(player);
		}
		if (link.readActors(ACTORS)) {
			sync(level);
		}
		// (Host events, hurt included, are run by ServerHost at the end of the tick, after this.)
		// Hits land during the tick (melee, sweeps, arrows, fire); send one combined hit per actor.
		for (HostActorEntity proxy : PROXIES.values()) {
			ServerPlayer hitter = proxy.hitter(); // before takeHit, which clears it
			float[] hit = proxy.takeHit();
			if (hit != null && (hit[0] > 0.0F || hit[3] > 0.0F)) {
				long steamId = hitter != null ? ServerHost.steamIdOf(hitter) : 0L;
				link.pushEvent(
					Proto.EV_HIT_ACTOR, proxy.entId(), steamId, hit[0], hit[1], hit[2], hit[3], Float.floatToRawIntBits(hit[4]), Float.floatToRawIntBits(hit[5])
				);
				GmodCraft.LOG.info("GmodCraft: {} hit {} for {} (knockback {})", hitter != null ? hitter.getPlainTextName() + " (SteamID " + Long.toUnsignedString(steamId) + ")"
					: "something", proxy.getName().getString(), hit[0], hit[3]);
			}
		}
	}

	private static void sync(ServerLevel level) {
		Map<Integer, GLink.Actor> live = new HashMap<>();
		for (GLink.Actor a : ACTORS) {
			if (!a.dead()) {
				live.put(a.entId(), a);
			}
		}
		for (Iterator<Map.Entry<Integer, HostActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, HostActorEntity> e = it.next();
			HostActorEntity proxy = e.getValue();
			if (!live.containsKey(e.getKey()) || proxy.isRemoved() || proxy.level() != level) {
				proxy.discard();
				it.remove();
			}
		}
		int before = PROXIES.size();
		for (GLink.Actor a : live.values()) {
			HostActorEntity proxy = PROXIES.get(a.entId());
			if (proxy == null) {
				proxy = new HostActorEntity(HOST_ACTOR, level);
				proxy.setEntId(a.entId());
				proxy.setSize(a.width(), a.height());
				proxy.snapTo(a.x(), a.y(), a.z(), a.yaw(), 0.0F);
				if (!a.name().isEmpty()) {
					proxy.setCustomName(Component.literal(a.name()));
				}
				if (!level.addFreshEntity(proxy)) {
					continue;
				}
				PROXIES.put(a.entId(), proxy);
				continue;
			}
			proxy.setSize(a.width(), a.height());
			proxy.setPos(a.x(), a.y(), a.z());
			proxy.setYRot(a.yaw());
			proxy.setYHeadRot(a.yaw());
			// Plates, tripwires, lava, fire, cactus and magma act on the proxy in its own tick
			// (HostActorEntity.applyBlockEffects), from this position to the next.
		}
		if (HazardTestHook.ENABLED) {
			HazardTestHook.tick(level, live.values());
		}
		if (ProjectileTestHook.ENABLED) {
			ProjectileTestHook.tick(level, live.values());
		}
		if (PROXIES.size() != before && (PROXIES.size() % 5 == 0 || PROXIES.size() < 5)) {
			GmodCraft.LOG.info("GmodCraft: {} GMod NPCs mirrored as hittable stand-ins", PROXIES.size());
		}
	}

	/**
	 * Items and stuck arrows on Skyrim ground rest on its collision voxels, which on steep or rough
	 * terrain can sit a little off from where the player (on Skyrim's exact triangles) stands.
	 * Touch them over a slightly bigger area than vanilla's so walking over them picks them up.
	 * playerTouch applies all of Minecraft's own rules (pickup delay, owner, inventory space).
	 */
	private static void pickUpNearby(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator()) {
			return;
		}
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(1.25, 1.0, 1.25))) {
			if (!entity.isRemoved() && (entity instanceof net.minecraft.world.entity.item.ItemEntity
				|| entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)) {
				entity.playerTouch(player);
			}
		}
	}

	private static void removeAll() {
		if (PROXIES.isEmpty()) {
			return;
		}
		PROXIES.values().forEach(Entity::discard);
		PROXIES.clear();
	}

	/**
	 * The host hurt a player. Runs on the server thread. {@code kind} is a Proto.HURT_* value and
	 * {@code hostDamage} is what GMod would have taken off the player's health.
	 */
	public static void hurtPlayer(ServerPlayer player, int kind, float hostDamage, int attackerEntId, int flags) {
		hurtPlayer(player, kind, hostDamage, attackerEntId, flags, null);
	}

	/**
	 * As above. {@code attackerPlayer} (protocol v14 PvP): the Minecraft player of the GMod player
	 * who did it (kHostEvHurt entId was their entIndex); the damage is then theirs, so Minecraft's
	 * armour, knockback, pvp rule and kill credit apply as for any player hit. Otherwise the host
	 * actor stand-in for {@code attackerEntId} is the attacker, if there is one nearby.
	 */
	public static void hurtPlayer(ServerPlayer player, int kind, float hostDamage, int attackerEntId, int flags, @Nullable ServerPlayer attackerPlayer) {
		if (!player.isAlive() || hostDamage <= 0.0F) {
			return;
		}
		ServerLevel level = player.level();
		DamageSources sources = level.damageSources();
		LivingEntity attacker;
		DamageSource source;
		if (attackerPlayer != null && attackerPlayer != player && attackerPlayer.isAlive()) {
			attacker = attackerPlayer;
			source = switch (kind) {
				case Proto.HURT_MELEE -> sources.playerAttack(attackerPlayer);
				case Proto.HURT_PROJECTILE -> sources.thrown(attackerPlayer, attackerPlayer);
				case Proto.HURT_MAGIC -> sources.indirectMagic(attackerPlayer, attackerPlayer);
				default -> sources.explosion(attackerPlayer, attackerPlayer); // GMod's "other" from a player: grenades, rockets
			};
		} else {
			HostActorEntity proxy = PROXIES.get(attackerEntId);
			if (proxy != null && proxy.distanceToSqr(player) > 64.0 * 64.0) {
				proxy = null; // far away: plain damage, no knockback from across the map
			}
			attacker = proxy;
			source = switch (kind) {
				case Proto.HURT_MELEE -> proxy != null ? sources.mobAttack(proxy) : sources.generic();
				case Proto.HURT_PROJECTILE -> proxy != null ? sources.mobProjectile(proxy, proxy) : sources.generic();
				case Proto.HURT_MAGIC -> proxy != null ? sources.indirectMagic(proxy, proxy) : sources.magic();
				default -> sources.generic();
			};
		}
		float damage = hostDamage / GmodCraftConfig.hostDamagePerMcDamage;
		float healthBefore = player.getHealth();
		boolean hurt = player.hurtServer(level, source, damage);
		GmodCraft.LOG.info("GmodCraft: GMod hit {} for {} ({} Minecraft){}: health {} -> {}{}", player.getPlainTextName(), hostDamage, damage,
			attacker instanceof ServerPlayer p ? " by player " + p.getPlainTextName() : "", healthBefore, player.getHealth(),
			hurt ? "" : attacker instanceof ServerPlayer p && !player.canHarmPlayer(p) ? " (blocked: pvp is off)" : " (blocked/immune)");
		if (hurt && attacker != null && (flags & Proto.HURT_POWER_ATTACK) != 0 && !player.isBlocking()) {
			// Power attacks shove harder, like a sprint hit does in Minecraft.
			player.knockback(0.5, attacker.getX() - player.getX(), attacker.getZ() - player.getZ(), source, damage);
		}
	}

	/**
	 * GMod entity index of the attacker behind a damage source, or 0: a host actor's entId, or (v14)
	 * the entIndex of the GMod player behind a Minecraft player.
	 */
	public static int attackerEntId(DamageSource source) {
		if (source.getEntity() instanceof HostActorEntity proxy) {
			return proxy.entId();
		}
		return source.getEntity() instanceof ServerPlayer p ? ServerHost.entIndexOf(p) : 0;
	}
}
