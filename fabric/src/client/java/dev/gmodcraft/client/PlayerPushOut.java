package dev.gmodcraft.client;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.world.PushOut;
import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The local player's half of {@link PushOut} (P6i): after GMod's collision around the player
 * changed (a door, a prop moved), a player shut inside a host entity is moved to the nearest free
 * spot. Runs at the end of the client tick for a few ticks after each batch of region updates
 * (a region's voxels and triangles are separate messages: the second may land a tick later).
 */
final class PlayerPushOut {
	private static final int RECHECK_TICKS = 10;
	private static int recheck;
	private static long noSpotLoggedAt;

	private PlayerPushOut() {
	}

	/** A region's triangles changed (SkyDigClient drains the queue and tells us). */
	static void regionChanged() {
		recheck = RECHECK_TICKS;
	}

	/** {@code held}: SkyClient is holding the player for collision to arrive (leave it alone). */
	static void tick(Minecraft minecraft, boolean held) {
		if (recheck <= 0) {
			return;
		}
		recheck--;
		LocalPlayer player = minecraft.player;
		if (held || player == null || minecraft.level == null || !SkyClient.linked() || player.noPhysics || player.isSpectator()
			|| player.isPassenger() || player.isSleeping() || !SkyCollision.usesSmoothCollider(player)) {
			return;
		}
		double x = player.getX(), y = player.getY(), z = player.getZ();
		double radius = player.getBbWidth() * 0.5, height = player.getBbHeight(), step = player.maxUpStep();
		// Shut in: host voxels and blocks only (an NPC stand-in, solid since P6i, overlapping the player
		// isn't a trap: Minecraft lets a box walk out of one it overlaps). A free spot: entities too.
		// The way there: blocks and dug-hole walls (BlockCollisionsMixin leaves host voxels out for the
		// smooth collider), what the server replays the move against.
		PushOut.BoxTest blocks = (x0, y0, z0, x1, y1, z1) -> blockSolid(minecraft, player, new AABB(x0, y0, z0, x1, y1, z1));
		PushOut.BoxTest trap = (x0, y0, z0, x1, y1, z1) -> hostSolid(x0, y0, z0, x1, y1, z1) || blocks.solid(x0, y0, z0, x1, y1, z1);
		PushOut.BoxTest free = (x0, y0, z0, x1, y1, z1) -> hostSolid(x0, y0, z0, x1, y1, z1)
			|| !minecraft.level.noCollision(player, new AABB(x0, y0, z0, x1, y1, z1));
		if (!PushOut.coreSolid(trap, x, y, z, radius, height, step)) {
			return; // the cheap test first: nothing in the body
		}
		List<SkyTri> tris = new ArrayList<>();
		double reach = PushOut.MAX_DIST + 1.0;
		SkyCollision.CLIENT.trianglesNear(new AABB(x - reach, y - 1.0, z - reach, x + reach, y + height + reach, z + reach), tris);
		SkyCollider.withoutCarrier(tris, y, step); // P6i: inside the (lagging) platform the player rides: not shut in
		if (!PushOut.dynamicNear(tris, x, y, z, radius, height)) {
			return;
		}
		double[] to = PushOut.find(tris, free, blocks, x, y, z, radius, height, step, PushOut.MAX_DIST);
		if (to == null) {
			long now = System.currentTimeMillis();
			if (now - noSpotLoggedAt < 1000) {
				return; // checked again every tick of the recheck window: log at most once a second
			}
			noSpotLoggedAt = now;
			GmodCraft.LOG.info("GmodCraft: player shut inside a GMod entity at {} {} {}; no free spot within {} blocks", x, y, z, PushOut.MAX_DIST);
			return;
		}
		player.setPos(to[0], to[1], to[2]);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		GmodCraft.LOG.info("GmodCraft: player shut inside a GMod entity: moved {} blocks to {} {} {}",
			String.format("%.2f", Math.sqrt((to[0] - x) * (to[0] - x) + (to[1] - y) * (to[1] - y) + (to[2] - z) * (to[2] - z))), to[0], to[1], to[2]);
	}

	/** Any Minecraft block (or dug-hole wall) collision in the box? Entities not included. */
	private static boolean blockSolid(Minecraft minecraft, LocalPlayer player, AABB box) {
		for (VoxelShape shape : minecraft.level.getBlockCollisions(player, box)) {
			if (!shape.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Any of GMod's voxels (client link) in the box? */
	private static boolean hostSolid(double x0, double y0, double z0, double x1, double y1, double z1) {
		int bx0 = (int) Math.floor(x0), by0 = (int) Math.floor(y0), bz0 = (int) Math.floor(z0);
		int bx1 = (int) Math.floor(x1), by1 = (int) Math.floor(y1), bz1 = (int) Math.floor(z1);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int bx = bx0; bx <= bx1; bx++) {
			for (int by = by0; by <= by1; by++) {
				for (int bz = bz0; bz <= bz1; bz++) {
					VoxelShape shape = SkyCollision.CLIENT.shapeAt(pos.set(bx, by, bz));
					if (shape == null || shape.isEmpty()) {
						continue;
					}
					for (AABB a : shape.toAabbs()) {
						if (a.maxX + bx > x0 && a.minX + bx < x1 && a.maxY + by > y0 && a.minY + by < y1 && a.maxZ + bz > z0 && a.minZ + bz < z1) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
