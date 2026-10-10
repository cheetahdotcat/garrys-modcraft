package dev.gmodcraft.combat;

import dev.gmodcraft.DevCommands;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.GmodCraftConfig;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.weapon.Fnv;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * B1 (protocol v27), Tier 1 "MC mob hulls": the Minecraft entities GMod gives an invisible body.
 * <ul>
 * <li>publish: every server tick, the overworld's mobs, animals, minecarts and boats within
 * kMcEntityRange blocks of a Minecraft player that a GMod player plays as, nearest first, at most
 * kMaxMcEntities (McEntities on the server link); v44: falling blocks and primed TNT too. Never players, never the host actor stand-ins
 * (GMod's own NPCs in Minecraft: that would mirror them back), no items.</li>
 * <li>hurt: kHostEvHurtMcEntity, GMod's damage on such a body, applied to the entity with the
 * attacker's credit like PvP (the GMod player's Minecraft player), else the GMod NPC's stand-in.</li>
 * </ul>
 */
public final class McEntities {
	/** F2: burn time set by GMod fire (5 s; GMod re-sends at most every 0.5 s while it keeps burning). */
	static final int FIRE_TICKS = 100;
	private static final List<ServerLink.McEntity> TABLE = new ArrayList<>();
	private static final Map<EntityType<?>, Integer> TYPE_HASH = new HashMap<>();
	private static int lastCount = -1;
	private static long lastLogNs;

	private McEntities() {
	}

	/** Does this entity get a GMod proxy at all (before the range)? */
	public static boolean wanted(Entity e) {
		if (e.isRemoved() || e instanceof Player || e instanceof HostActorEntity || e instanceof net.minecraft.world.entity.decoration.ArmorStand) {
			return false;
		}
		return e instanceof LivingEntity || e instanceof AbstractMinecart || e instanceof AbstractBoat || e instanceof FallingBlockEntity || e instanceof PrimedTnt;
	}

	public static int category(Entity e) {
		if (e instanceof FallingBlockEntity) {
			return Proto.MC_ENT_FALLING_BLOCK; // v44: GMod simulates it while it flies
		}
		if (e instanceof PrimedTnt) {
			return Proto.MC_ENT_TNT;
		}
		if (e instanceof AbstractMinecart || e instanceof AbstractBoat) {
			return Proto.MC_ENT_VEHICLE;
		}
		if (e instanceof Enemy) {
			return Proto.MC_ENT_HOSTILE;
		}
		return e instanceof LivingEntity ? Proto.MC_ENT_PASSIVE : Proto.MC_ENT_OTHER;
	}

	/** v31: the category in the low bits, the body yaw (MC degrees, any range) quantized above (McEntity::category). */
	public static int packCategory(int category, float yaw) {
		float w = yaw % 360.0F;
		if (w < 0.0F) {
			w += 360.0F;
		}
		int q = Math.round(w / 360.0F * Proto.MC_ENT_YAW_STEPS) & (Proto.MC_ENT_YAW_STEPS - 1);
		return (category & Proto.MC_ENT_CATEGORY_MASK) | (q << Proto.MC_ENT_YAW_SHIFT);
	}

	/** The yaw the model is drawn at: the body's for living entities, else the entity's. */
	static float bodyYaw(Entity e) {
		return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
	}

	private static int typeHash(EntityType<?> type) {
		return TYPE_HASH.computeIfAbsent(type, t -> Fnv.hash32(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
	}

	/** Once per server tick, after HostPlayers was read. {@code centres}: the mapped Minecraft players. */
	public static void publish(MinecraftServer server, ServerLink link, List<ServerPlayer> centres, boolean anyHostPlayerOnline) {
		ServerLevel level = server.overworld();
		List<Entity> candidates = new ArrayList<>();
		List<double[]> points = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (wanted(e)) {
				candidates.add(e);
				points.add(new double[] { e.getX(), e.getY(), e.getZ() });
			}
		}
		List<double[]> cs = new ArrayList<>();
		for (ServerPlayer p : centres) {
			if (p.level() == level) {
				cs.add(new double[] { p.getX(), p.getY(), p.getZ() });
			}
		}
		List<Integer> picked;
		if (cs.isEmpty() && DevCommands.ENABLED && !anyHostPlayerOnline) {
			// Test runs with nobody online at all: every loaded one, by id, so the harness sees what it summons.
			candidates.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
			picked = new ArrayList<>();
			for (int i = 0; i < candidates.size() && i < Proto.MAX_MC_ENTITIES; i++) {
				picked.add(i);
			}
		} else {
			picked = McEntitySelect.select(points, cs, Proto.MC_ENTITY_RANGE, Proto.MAX_MC_ENTITIES);
		}
		TABLE.clear();
		// v29 (T2): what GMod holds first, whatever the range / cap: a frozen or ballooned mob far from
		// everyone keeps its proxy (else GMod would lose the body it owns).
		List<Entity> rows = new ArrayList<>();
		for (Entity e : HeldMcEntities.pinnedEntities()) {
			if (e.level() == level && wanted(e) && rows.size() < Proto.MAX_MC_ENTITIES) {
				rows.add(e);
			}
		}
		for (int i : picked) {
			Entity e = candidates.get(i);
			if (rows.size() < Proto.MAX_MC_ENTITIES && !HeldMcEntities.pinned(e)) {
				rows.add(e);
			}
		}
		for (Entity e : rows) {
			Vec3 v = e.getDeltaMovement();
			float health = e instanceof LivingEntity le ? le.getHealth() : 0.0F;
			float maxHealth = e instanceof LivingEntity le ? le.getMaxHealth() : 0.0F;
			int flags = e instanceof LivingEntity le && le.isDeadOrDying() ? Proto.MC_ENT_DEAD : 0;
			TABLE.add(new ServerLink.McEntity(e.getId(), packCategory(category(e), bodyYaw(e)), flags, typeHash(e.getType()), e.getBbWidth(), e.getBbHeight(), e.getX(), e.getY(),
				e.getZ(), (float) v.x, (float) v.y, (float) v.z, health, maxHealth));
		}
		link.writeMcEntities(TABLE);
		long now = System.nanoTime();
		if (TABLE.size() != lastCount && (lastCount <= 0 || TABLE.isEmpty()) && now - lastLogNs > 10_000_000_000L) {
			lastLogNs = now;
			GmodCraft.LOG.info("GmodCraft: {} Minecraft entities listed for GMod proxies", TABLE.size());
		}
		lastCount = TABLE.size();
	}

	/** kHostEvHurtMcEntity. */
	public static void hurt(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerLevel level = server.overworld();
		Entity target = level.getEntity(ev.requestId());
		float hostDamage = Math.max(0.0F, Math.min(ev.a() / 100.0F, 100000.0F));
		if (target == null || !wanted(target) || hostDamage <= 0.0F) {
			GmodCraft.LOG.debug("GmodCraft: hurt for Minecraft entity {} dropped (gone, or not one GMod has a body for)", ev.requestId());
			return;
		}
		if ((ev.flags() & Proto.HURT_FIRE) != 0) {
			// F2: GMod fire on the proxy. Set it on fire the Minecraft way (refresh, never shorten: lava's longer
			// burn stays); vanilla burning does the damage, the death message and cooked drops, and skips
			// fire-immune mobs. No attacker credit, as with any vanilla burn.
			int before = target.getRemainingFireTicks();
			target.igniteForTicks(FIRE_TICKS);
			GmodCraft.LOG.debug("GmodCraft: GMod fire on {} {}: fire ticks {} -> {}", target.getType().getDescription().getString(), target.getId(), before,
				target.getRemainingFireTicks());
			return;
		}
		ServerPlayer attackerPlayer = ev.steamId() != 0 ? ServerHost.playerBySteamId(server, ev.steamId()) : null;
		if (attackerPlayer == null) {
			attackerPlayer = ServerHost.playerByEntIndex(server, ev.entId());
		}
		if (attackerPlayer != null && !attackerPlayer.isAlive()) {
			attackerPlayer = null; // a dead player's shot: plain damage, no credit
		}
		HostActorEntity actor = attackerPlayer == null ? SkyCombat.proxy(ev.entId()) : null;
		if (actor != null && actor.distanceToSqr(target) > 64.0 * 64.0) {
			actor = null;
		}
		DamageSource source = source(level.damageSources(), ev.code(), attackerPlayer, actor);
		float damage = hostDamage / GmodCraftConfig.hostDamagePerMcDamage;
		float before = target instanceof LivingEntity le ? le.getHealth() : 0.0F;
		boolean hurt = target.hurtServer(level, source, damage);
		GmodCraft.LOG.info("GmodCraft: GMod hit {} {} for {} ({} Minecraft){}: {}", target.getType().getDescription().getString(), target.getId(), hostDamage,
			damage, attackerPlayer != null ? " by player " + attackerPlayer.getPlainTextName() : actor != null ? " by GMod NPC " + ev.entId() : "",
			target instanceof LivingEntity le ? "health " + before + " -> " + le.getHealth() : hurt ? "hit" : "no effect");
	}

	static DamageSource source(DamageSources sources, int kind, @Nullable ServerPlayer player, @Nullable HostActorEntity actor) {
		if (player != null) {
			return switch (kind) {
				case Proto.HURT_MELEE -> sources.playerAttack(player);
				case Proto.HURT_PROJECTILE -> sources.thrown(player, player);
				case Proto.HURT_MAGIC -> sources.indirectMagic(player, player);
				default -> sources.explosion(player, player);
			};
		}
		if (actor != null) {
			return switch (kind) {
				case Proto.HURT_MELEE -> sources.mobAttack(actor);
				case Proto.HURT_PROJECTILE -> sources.mobProjectile(actor, actor);
				case Proto.HURT_MAGIC -> sources.indirectMagic(actor, actor);
				default -> sources.explosion(actor, actor);
			};
		}
		return kind == Proto.HURT_MAGIC ? sources.magic() : sources.generic();
	}
}
