package dev.gmodcraft.client;

import dev.gmodcraft.client.mixin.EntityCollideInvoker;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.world.Carry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The local player's half of {@link Carry} (P6i, protocol v30): moves it with the GMod entity it
 * stands on, from HostState's carry block (client/carry.lua samples it every GMod frame). Runs once
 * per tick after the player's own movement and before the move goes to the server
 * (LocalPlayerCarryMixin), so the server sees the carried position and "on ground".
 * <p>While pinned on a platform its triangles are left out of the player's collision and push-out
 * ({@link #skipEntity}): the client link places them at most every 250 ms and drops fast props
 * entirely (D-012), so they lag the platform; the pin is the floor instead.
 */
public final class CarryClient {
	/** Ticks a platform's triangles stay out of the collision after the last pin (a flickering top). */
	private static final int SKIP_TICKS = 3;
	private static final Carry CARRY = new Carry();
	private static int carried;
	private static int skipEnt;
	private static int skipTicks;

	private CarryClient() {
	}

	/** The GMod entity whose triangles the player ignores right now (pinned on it), 0 = none. */
	public static int skipEntity() {
		return skipEnt;
	}

	/** The GMod entity the player was carried with this tick (McState.carryEnt), 0 = none. */
	public static int carriedEntity() {
		return carried;
	}

	/** Off: a teleport, a respawn, the link went down. */
	public static void stop() {
		CARRY.reset();
		carried = 0;
		skipEnt = 0;
		skipTicks = 0;
	}

	/** LocalPlayer.sendChanges (after the entity tick), right before sendPosition. */
	public static void beforeSend(LocalPlayer player) {
		ClientLink.HostState sky = SkyClient.sky();
		if (!SkyClient.linked() || SkyClient.holding() || sky.carryEnt == 0 || player.noPhysics || player.isSpectator() || player.isPassenger()
			|| player.isSleeping() || player.getAbilities().flying || player.isDeadOrDying()) {
			stop();
			return;
		}
		double[] m = CARRY.advance(sky.carryEnt, sky.carrySeq, sky.carryPivotX, sky.carryPivotY, sky.carryPivotZ, sky.carryYaw, sky.carryVelX,
			sky.carryVelY, sky.carryVelZ, sky.carryYawRate);
		if (sky.carryEnt != carried) {
			skipEnt = 0;
			skipTicks = 0;
		}
		carried = sky.carryEnt;
		double x = player.getX(), y = player.getY(), z = player.getZ();
		if (m != null) {
			double[] to = Carry.carryPoint(x, y, z, m);
			Vec3 want = new Vec3(to[0] - x, to[1] - y, to[2] - z);
			if (want.lengthSqr() > 1e-12) {
				// Collided like any move (walls, Minecraft blocks, other GMod entities; the platform's own
				// triangles are out while pinned): a train doesn't push the player through a tunnel wall.
				Vec3 got = ((EntityCollideInvoker) player).gmodcraft$collide(want);
				player.setPos(x + got.x, y + got.y, z + got.z);
			}
		}
		// A jump (dy > 0) ends the pin (shouldPin); the platform's floor comes back after SKIP_TICKS, so
		// its stale triangles can't catch the feet right after leaving a platform going down.
		Vec3 dm = player.getDeltaMovement();
		if (Carry.shouldPin((sky.carryFlags & Proto.CARRY_ON_TOP) != 0, player.getY(), dm.y, sky.carryTopY)) {
			player.setPos(player.getX(), sky.carryTopY, player.getZ());
			player.setDeltaMovement(dm.x, 0.0, dm.z);
			player.setOnGround(true);
			player.resetFallDistance();
			skipTicks = SKIP_TICKS;
		} else if (skipTicks > 0) {
			skipTicks--;
		}
		skipEnt = skipTicks > 0 ? carried : 0;
	}
}
