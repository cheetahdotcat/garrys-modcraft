package dev.gmodcraft.world;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.tools.BlockRules;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure decisions behind physics blocks (protocol v44), unit-tested without a running game: may a
 * gravgun pull detach this block, how strong is a GMod blast in Minecraft, and the per-tick blast
 * budget (merging blasts closer than a block).
 */
public final class PhysicsBlockRules {
	public enum Pull { OK, OUTSIDE_SLOT, LOCKED, MIRROR, AIR, LIQUID, BLOCK_ENTITY, UNBREAKABLE, ADMIN_ONLY, NO_PERMISSION }

	/** What a pull's block looks like (read from the level by the caller). */
	public record PullTarget(boolean inSlot, boolean locked, boolean mirror, boolean air, boolean liquid, boolean blockEntity, float hardness,
		String idPath) {
	}

	/** Strongest motion a pull gives the block, blocks per tick (the gravgun takes it from there). */
	public static final double PULL_SPEED = 0.35;
	/** Blasts this close (blocks) in one tick are one explosion. */
	public static final double MERGE_DISTANCE = 1.0;

	private PhysicsBlockRules() {
	}

	/**
	 * May the GMod player detach it? {@code admin}: the GMod side's admin decision (kPullByAdmin).
	 * {@code hasPlayer}: the player has a Minecraft player; {@code playerMay}: that player may break
	 * blocks there (game mode, spawn protection). Without a Minecraft player only admins may pull.
	 */
	public static Pull pull(PullTarget t, boolean admin, boolean hasPlayer, boolean playerMay) {
		if (!t.inSlot()) {
			return Pull.OUTSIDE_SLOT;
		}
		if (t.locked()) {
			return Pull.LOCKED;
		}
		if (t.mirror()) {
			return Pull.MIRROR; // the hull world's map blocks never come loose
		}
		if (t.air()) {
			return Pull.AIR;
		}
		if (t.liquid()) {
			return Pull.LIQUID;
		}
		if (t.blockEntity()) {
			return Pull.BLOCK_ENTITY; // chests, signs, microblocks...: their contents would not survive the fall
		}
		if (!(t.hardness() >= 0.0F)) {
			return Pull.UNBREAKABLE; // bedrock, barriers, end portal frames (hardness -1)
		}
		if (!admin && !BlockRules.everyoneMayBreak(t.idPath())) {
			return Pull.ADMIN_ONLY;
		}
		if (hasPlayer ? !playerMay : !admin) {
			return Pull.NO_PERMISSION;
		}
		return Pull.OK;
	}

	/** Vertical speed (blocks per tick) below which a released physics block counts as resting (GMod's 12 u/s). */
	public static final double REST_VY = 12.0 / 800.0;

	/**
	 * GMod gave a falling block back: did it rest there (land it in place) rather than drop out for another
	 * reason (a physgun hold ending mid-air, the age limit while flying)? Only a record with kHeldPhysics
	 * alone and next to no vertical motion.
	 */
	public static boolean restedInGmod(int lastFlags, double vy) {
		return lastFlags == Proto.HELD_PHYSICS && Math.abs(vy) < REST_VY;
	}

	/**
	 * The y a resting falling block lands at (its cell's centre height): the cell nearest its feet, so a block
	 * resting on a prop top a little inside a cell takes that cell, one resting on a top high in a cell the cell
	 * above (FallingBlockMixin's prop band holds either).
	 */
	public static double landingY(double feetY) {
		return Math.floor(feetY + 0.5) + 0.5;
	}

	/** Ticks between re-checks of a block a GMod prop holds up (a prop going away sends Minecraft no block update). */
	public static final int PROP_RECHECK_TICKS = 20;

	/**
	 * Ticks left of a placed block's hold (placed at game time {@code placedAt}, held {@code hold} ticks), 0 when
	 * over or never placed ({@code placedAt} Long.MIN_VALUE). A cancelled block tick must be scheduled again this
	 * far ahead (+1): vanilla never reschedules a falling block's tick by itself.
	 */
	public static long holdRemaining(long placedAt, long now, long hold) {
		if (placedAt == Long.MIN_VALUE || now < placedAt) {
			return placedAt == Long.MIN_VALUE ? 0 : hold;
		}
		return Math.max(0, hold - (now - placedAt));
	}

	/** kHostEvBlast's a (power * 100) -> Minecraft explosion power, 0 when there is none. */
	public static float blastPower(int a) {
		float p = a / 100.0F;
		if (!(p > 0.0F)) {
			return 0.0F;
		}
		return Math.min(p, Proto.BLAST_MAX_POWER);
	}

	/** One tick's blasts: at most kBlastsPerTick, none within MERGE_DISTANCE of an earlier one. */
	public static final class Budget {
		private final List<double[]> done = new ArrayList<>();

		public void reset() {
			this.done.clear();
		}

		/** True if this blast goes off (and counts); false when the budget is spent or it merges. */
		public boolean take(double x, double y, double z) {
			if (this.done.size() >= Proto.BLASTS_PER_TICK) {
				return false;
			}
			for (double[] d : this.done) {
				double dx = d[0] - x, dy = d[1] - y, dz = d[2] - z;
				if (dx * dx + dy * dy + dz * dz < MERGE_DISTANCE * MERGE_DISTANCE) {
					return false;
				}
			}
			this.done.add(new double[] { x, y, z });
			return true;
		}
	}
}
