package dev.gmodcraft.combat;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.GmodCraftConfig;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.net.SkyNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * T2 (protocol v29): GMod's physgun / gravgun on Minecraft entities (authority handoff).
 * <ul>
 * <li>HeldMcEntities (host -&gt; MC, 20 Hz): the entities whose proxy GMod owns (held, frozen,
 * constrained). Each is pinned every tick to GMod's position and motion; its AI ({@code
 * Mob.serverAiStep}), travel and gravity are skipped by mixins while it is in {@link #PINNED}. That set
 * is transient: NoAI / NoGravity are never touched, so nothing of a hold can reach a save (server
 * stop, chunk unload, crash mid-hold leave a normal entity).</li>
 * <li>Release (the tick an id drops out, the link drops, the table goes stale): its
 * last motion once (the throw), fall distance reset, off the ground, navigation stopped (no walk back
 * to a target picked before the grab); AI and gravity come back with it out of the set.</li>
 * <li>kHostEvPuntMcEntity: a gravgun punt on an entity Minecraft owns, an impulse added once.</li>
 * </ul>
 * Overworld only (the mirror dimension), any entity but players and the host actor stand-ins.
 */
public final class HeldMcEntities {
	/** Entities pinned right now (identity; read by the mixins on the server thread). */
	private static final Set<Entity> PINNED = Collections.newSetFromMap(new IdentityHashMap<>());
	/** v44: every entity GMod ever pinned (weak: gone with the entity). A falling block among them never ends as an item. */
	private static final Set<Entity> ONCE = Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private static final Map<Integer, Pin> PINS = new HashMap<>();
	private static final HeldTable TABLE = new HeldTable();
	private static final List<ServerLink.HeldMcEntity> READ = new ArrayList<>();
	private static final List<HeldTable.HeldRecord> RECORDS = new ArrayList<>();
	/** T2b: the ids the clients were last told (SkyNet.HeldIds), and the resend clock. */
	private static final Set<Integer> TOLD = new java.util.LinkedHashSet<>();
	private static final int TELL_EVERY = 20;
	private static int tellTicks;
	/** Largest punt impulse, blocks per tick. */
	static final double MAX_PUNT = 3.0;

	private record Pin(Entity entity, HeldTable.HeldRecord last) {
	}

	private HeldMcEntities() {
	}

	/** Is this entity held by GMod right now (its AI, travel and gravity skipped)? */
	public static boolean pinned(Entity e) {
		return !PINNED.isEmpty() && PINNED.contains(e);
	}

	/** v44: was this entity ever held / simulated by GMod (or detached for it: a gravgun pull)? */
	public static boolean oncePinned(Entity e) {
		return !ONCE.isEmpty() && ONCE.contains(e);
	}

	/** v44: a block the gravgun pulled out: GMod's from the start (never an item, see oncePinned). */
	public static void markForGmod(Entity e) {
		ONCE.add(e);
	}

	/** The entities GMod holds right now (read-only view). */
	public static java.util.Collection<Entity> pinnedEntities() {
		return Collections.unmodifiableSet(PINNED);
	}

	public static boolean enabled() {
		return GmodCraftConfig.rules().physgunMobs();
	}

	/** Can GMod hold this one at all? */
	static boolean holdable(Entity e) {
		return !e.isRemoved() && !(e instanceof Player) && !(e instanceof HostActorEntity) && !(e instanceof LivingEntity le && le.isDeadOrDying());
	}

	/** Server tick start, linked: read the table, release what dropped out, pin the rest. */
	public static void tick(MinecraftServer server, ServerLink link) {
		int seq = link.readHeldMcEntities(READ);
		RECORDS.clear();
		for (ServerLink.HeldMcEntity h : READ) {
			RECORDS.add(new HeldTable.HeldRecord(h.entityId(), h.flags(), h.holderSteamId(), h.x(), h.y(), h.z(), h.vx(), h.vy(), h.vz(), h.yaw(), h.pitch()));
		}
		Map<Integer, HeldTable.HeldRecord> want = TABLE.update(link.generation(), seq, RECORDS, System.nanoTime());
		if (!enabled()) {
			// Rule physgunMobs off: no new holds (GMod refuses them too); a hold already running ends normally.
			Map<Integer, HeldTable.HeldRecord> running = new HashMap<>(want);
			running.keySet().retainAll(PINS.keySet());
			want = running;
		}
		Iterator<Map.Entry<Integer, Pin>> it = PINS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Pin> e = it.next();
			Pin pin = e.getValue();
			if (!want.containsKey(e.getKey()) || pin.entity().isRemoved()) {
				it.remove();
				release(pin);
			}
		}
		if (want.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		for (HeldTable.HeldRecord r : want.values()) {
			Pin pin = PINS.get(r.entityId());
			Entity e = pin != null ? pin.entity() : level.getEntity(r.entityId());
			if (e == null || e.level() != level || !holdable(e)) {
				if (pin != null) {
					PINS.remove(r.entityId());
					release(pin);
				}
				continue;
			}
			if (pin == null) {
				grab(e);
			}
			apply(e, r);
			PINS.put(r.entityId(), new Pin(e, r));
		}
	}

	/**
	 * T2b, server tick end: tell the clients which entities GMod holds (SkyNet.HeldIds) when the set
	 * changes, and about once a second while it isn't empty (players who start tracking one mid-hold).
	 */
	public static void tellClients(MinecraftServer server) {
		boolean changed = !PINS.keySet().equals(TOLD);
		if (!changed && (TOLD.isEmpty() || ++tellTicks < TELL_EVERY)) {
			return;
		}
		tellTicks = 0;
		TOLD.clear();
		TOLD.addAll(PINS.keySet());
		SkyNet.HeldIds msg = new SkyNet.HeldIds(List.copyOf(TOLD));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, SkyNet.HeldIds.TYPE)) {
				ServerPlayNetworking.send(player, msg);
			}
		}
	}

	/** Server tick end: pin again what the entity tick may have moved (carts and boats move in their own tick). */
	public static void reapply() {
		for (Pin pin : PINS.values()) {
			if (!pin.entity().isRemoved()) {
				HeldTable.HeldRecord r = pin.last();
				pin.entity().setPos(r.x(), r.y(), r.z());
				turn(pin.entity(), r);  // the living tick turns the body toward its motion
			}
		}
	}

	/** The link went down: everything goes back to Minecraft. */
	public static void releaseAll() {
		TABLE.clear();
		if (PINS.isEmpty()) {
			return;
		}
		for (Pin pin : PINS.values()) {
			release(pin);
		}
		PINS.clear();
		PINNED.clear();
	}

	private static void grab(Entity e) {
		if (e.isPassenger()) {
			e.stopRiding();
		}
		if (e instanceof Mob mob) {
			mob.getNavigation().stop();
		}
		PINNED.add(e);
		ONCE.add(e);
		GmodCraft.LOG.debug("GmodCraft: GMod took {} {}", e.getType().getDescription().getString(), e.getId());
	}

	private static void apply(Entity e, HeldTable.HeldRecord r) {
		e.setPos(r.x(), r.y(), r.z());
		e.setDeltaMovement(r.vx(), r.vy(), r.vz());
		e.resetFallDistance();
		e.setOnGround(false);
		turn(e, r);
		e.needsSync = true;
	}

	/**
	 * v32 (T2c): face the way the GMod body faces. yRot, head and body all the same (mobs have no roll: a
	 * tilt stays upright), each unwrapped to the turn nearest its current value so neither the server's
	 * old values nor the client's interpolation go the long way round at 359 -&gt; 1.
	 */
	private static void turn(Entity e, HeldTable.HeldRecord r) {
		if (r.hasPitch()) {
			e.setXRot(Math.max(-90.0F, Math.min(90.0F, r.pitch())));
		}
		if (!r.hasYaw()) {
			return;
		}
		e.setYRot(HeldTable.nearestTurn(e.getYRot(), r.yaw()));
		if (e instanceof LivingEntity le) {
			le.setYHeadRot(HeldTable.nearestTurn(le.getYHeadRot(), r.yaw()));
			le.setYBodyRot(HeldTable.nearestTurn(le.yBodyRot, r.yaw()));
		}
	}

	/** Back to Minecraft: the last motion once (the throw), no stale fall, no walk back. */
	private static void release(Pin pin) {
		Entity e = pin.entity();
		PINNED.remove(e);
		if (e.isRemoved()) {
			return;
		}
		HeldTable.HeldRecord r = pin.last();
		if (dev.gmodcraft.world.PhysicsBlocks.landReleased(e, r.flags(), r.vy())) {
			return; // v44: a falling block that came to rest in GMod is the block there now
		}
		e.setDeltaMovement(new Vec3(r.vx(), r.vy(), r.vz()));
		e.resetFallDistance();
		e.setOnGround(false);
		if (e instanceof Mob mob) {
			mob.getNavigation().stop();
		}
		e.needsSync = true;
		GmodCraft.LOG.debug("GmodCraft: GMod released {} {} with {}, {}, {}", e.getType().getDescription().getString(), e.getId(), r.vx(), r.vy(), r.vz());
	}

	/** kHostEvPuntMcEntity: requestId = entity id, x/y/z = impulse (blocks per tick). */
	public static void punt(MinecraftServer server, ServerLink.HostEvent ev) {
		if (!enabled()) {
			return;
		}
		ServerLevel level = server.overworld();
		Entity e = level.getEntity(ev.requestId());
		double[] v = HeldTable.clampImpulse(ev.x(), ev.y(), ev.z(), MAX_PUNT);
		if (e == null || v == null || !holdable(e) || pinned(e)) {
			return; // gone, not ours to move, or GMod owns it (its own physics launches it)
		}
		e.push(v[0], v[1], v[2]);
		e.setOnGround(false);
		e.needsSync = true;
		GmodCraft.LOG.debug("GmodCraft: GMod punted {} {} by {}, {}, {}", e.getType().getDescription().getString(), e.getId(), v[0], v[1], v[2]);
	}

	/** Test / debug: how many entities GMod holds. */
	public static int count() {
		return PINS.size();
	}
}
