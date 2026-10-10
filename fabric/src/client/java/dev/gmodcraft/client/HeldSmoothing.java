package dev.gmodcraft.client;

import dev.gmodcraft.client.mixin.InterpolationStepsAccessor;
import dev.gmodcraft.net.SkyNet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.AbstractInterpolationHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;

/**
 * T2b: entities GMod holds (SkyNet.HeldIds) follow their position packets with a 1-tick interpolation
 * instead of their type's update interval (3 for mobs). With 3, the stepped handler keeps about three
 * ticks of positions buffered (SteppedInterpolationHandler.advance), so a roped or carried mob trailed
 * its GMod body by ~150 ms on top of the network; with 1 it drains to the newest position every tick
 * (still smooth between ticks). The server sends their positions every tick while held (needsSync).
 */
public final class HeldSmoothing {
	private static final IntOpenHashSet HELD = new IntOpenHashSet();
	/** Handlers changed by us -> their own step count, restored when the entity is released. */
	private static final Map<AbstractInterpolationHandler, Integer> CHANGED = new IdentityHashMap<>();
	static final int HELD_STEPS = 1;

	private HeldSmoothing() {
	}

	static void register() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(SkyNet.HeldIds.TYPE,
			(payload, context) -> context.client().execute(() -> {
				HELD.clear();
				HELD.addAll(payload.ids());
			}));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(HeldSmoothing::clear));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(HeldSmoothing::tick);
	}

	private static void clear() {
		HELD.clear();
		CHANGED.clear(); // the level (and its handlers) is gone
	}

	private static void tick(Minecraft minecraft) {
		var level = minecraft.level;
		if (level == null) {
			if (!CHANGED.isEmpty() || !HELD.isEmpty()) {
				clear();
			}
			return;
		}
		// Released (or gone): their own step count back.
		var it = CHANGED.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			AbstractInterpolationHandler h = e.getKey();
			Entity owner = ((InterpolationStepsAccessor) h).gmodcraft$entity();
			if (owner.isRemoved() || !HELD.contains(owner.getId()) || owner.getInterpolation() != h) {
				((InterpolationStepsAccessor) h).gmodcraft$setInterpolationSteps(e.getValue());
				it.remove();
			}
		}
		if (HELD.isEmpty()) {
			return;
		}
		var ids = HELD.iterator();
		while (ids.hasNext()) {
			Entity entity = level.getEntity(ids.nextInt());
			if (entity == null || entity == minecraft.player) {
				continue;
			}
			InterpolationHandler handler = entity.getInterpolation();
			if (handler instanceof AbstractInterpolationHandler h && !CHANGED.containsKey(h)) {
				InterpolationStepsAccessor a = (InterpolationStepsAccessor) h;
				int own = a.gmodcraft$interpolationSteps();
				if (own > HELD_STEPS) {
					CHANGED.put(h, own);
					a.gmodcraft$setInterpolationSteps(HELD_STEPS);
				}
			}
		}
	}

	/** Debug / tests: how many entities are followed without the buffer now. */
	public static int active() {
		return CHANGED.size();
	}
}
