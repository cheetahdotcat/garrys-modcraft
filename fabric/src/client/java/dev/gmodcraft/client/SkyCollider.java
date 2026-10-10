package dev.gmodcraft.client;

import dev.gmodcraft.world.SkyCollision;
import dev.gmodcraft.world.SkyTri;
import dev.gmodcraft.world.TriCollider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Feeds the local player's movement through {@link TriCollider} against nearby Skyrim triangles. */
public final class SkyCollider {
	private SkyCollider() {
	}

	public static Vec3 collide(LocalPlayer player, Vec3 move) {
		AABB box = player.getBoundingBox();
		double step = player.maxUpStep();
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.CLIENT.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
		withoutCarrier(tris, box.minY, step);
		if (tris.isEmpty()) {
			return move;
		}
		double[] r = TriCollider.resolve(
			tris, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, box.getXsize() * 0.5, box.getYsize(), step, player.onGround(),
			move.x, move.y, move.z
		);
		if (r[0] == move.x && r[1] == move.y && r[2] == move.z) {
			return move;
		}
		// The triangle pass (snapping down a slope, pushing out of a wall) can move the player into a
		// Minecraft block placed on the terrain; collide that result with Minecraft blocks again.
		return Entity.collideBoundingBox(player, new Vec3(r[0], r[1], r[2]), box, player.level(), List.of());
	}

	/**
	 * P6i: drops the floor of the platform the player is pinned on (CarryClient): its triangles lag
	 * the platform (placed at most every 250 ms) and would shove the player; the pin is the floor.
	 * Only floor-like ones (|normal y| over 0.5) and anything below the feet plus the step height go:
	 * a cabin's walls and railings still block.
	 */
	static void withoutCarrier(List<SkyTri> tris, double feetY, double step) {
		int ent = CarryClient.skipEntity();
		if (ent != 0) {
			tris.removeIf(t -> t.dynamic && t.entity == ent && (Math.abs(t.ny) > 0.5 || t.maxY <= feetY + step));
		}
	}

	/** Highest Skyrim surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(double x, double y, double z, double maxAbove) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.CLIENT.trianglesNear(new AABB(x - 1, y - 4, z - 1, x + 1, y + maxAbove + 1, z + 1), tris);
		return TriCollider.groundAt(tris, x, y, z, maxAbove);
	}
}
