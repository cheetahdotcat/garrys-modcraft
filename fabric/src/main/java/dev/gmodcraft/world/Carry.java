package dev.gmodcraft.world;

/**
 * Moving the local player with the GMod entity it stands on (P6i, protocol v30): an elevator, a
 * train, a pushed or physgun-held prop. The GMod client samples the entity every frame (HostState
 * carry block: its origin and yaw in MC space, their rates per tick, the top under the feet); each
 * client tick the player is moved by the platform's motion since the previous tick (translation plus
 * a turn about the origin) and, while it stands on it, pinned to its top.
 * <p>The motion is taken from the change of the sampled origin / yaw between two ticks, not by
 * integrating the rates, so the player never drifts against the platform; the rates only stand in
 * for a few ticks without a new sample (GMod's frames slower than the 20 Hz tick).
 * <p>Pure: {@code CarryClient} feeds it; CarryTest checks the math.
 */
public final class Carry {
	/** Feet up to this far under the platform's top are lifted onto it (blocks). */
	public static final double PIN_BELOW = 0.75;
	/** Feet up to this far over the top are pulled down onto it (a platform going down). */
	public static final double PIN_ABOVE = 0.25;
	/** Ticks the rates stand in for samples that don't come; then the platform counts as still. */
	public static final int MAX_EXTRAPOLATE = 3;

	private int ent;
	private int seq;
	private double refX, refY, refZ, refYaw;
	private int stale;

	/** No platform (left it, a teleport, a new player). */
	public void reset() {
		this.ent = 0;
	}

	/** The platform being followed, 0 = none. */
	public int ent() {
		return this.ent;
	}

	/**
	 * One tick with the latest sample. Returns the platform's motion since the previous tick as
	 * {fromX, fromY, fromZ, toX, toY, toZ, dYaw} (its origin before and after, the MC yaw turn in
	 * radians), or null on the first tick on a platform (nothing to compare with) and with ent 0.
	 */
	public double[] advance(int ent, int seq, double pivotX, double pivotY, double pivotZ, double yaw, double velX, double velY, double velZ,
		double yawRate) {
		if (ent == 0) {
			this.ent = 0;
			return null;
		}
		if (ent != this.ent) {
			this.ent = ent;
			this.seq = seq;
			this.stale = 0;
			this.refX = pivotX;
			this.refY = pivotY;
			this.refZ = pivotZ;
			this.refYaw = yaw;
			return null;
		}
		double tx, ty, tz, tyaw;
		if (seq != this.seq) {
			this.seq = seq;
			this.stale = 0;
			tx = pivotX;
			ty = pivotY;
			tz = pivotZ;
			tyaw = yaw;
		} else if (this.stale < MAX_EXTRAPOLATE) {
			this.stale++;
			tx = this.refX + velX;
			ty = this.refY + velY;
			tz = this.refZ + velZ;
			tyaw = this.refYaw + yawRate;
		} else {
			tx = this.refX;
			ty = this.refY;
			tz = this.refZ;
			tyaw = this.refYaw;
		}
		double[] m = { this.refX, this.refY, this.refZ, tx, ty, tz, wrap(tyaw - this.refYaw) };
		this.refX = tx;
		this.refY = ty;
		this.refZ = tz;
		this.refYaw = tyaw;
		return m;
	}

	/**
	 * Where a point riding the platform goes with the motion {@code m} (from {@link #advance}): turned
	 * by dYaw about the old origin, then moved with it. MC yaw grows clockwise seen from above (south
	 * 0, west 90), so a turn by d takes (x, z) to (x cos d - z sin d, x sin d + z cos d).
	 */
	public static double[] carryPoint(double x, double y, double z, double[] m) {
		double rx = x - m[0], rz = z - m[2];
		double c = Math.cos(m[6]), s = Math.sin(m[6]);
		return new double[] { m[3] + rx * c - rz * s, y + (m[4] - m[1]), m[5] + rx * s + rz * c };
	}

	/**
	 * Pin the feet at {@code y} to the platform's top? Only with the top known this frame, not while
	 * the player moves up by itself (a jump: that ends the pin) and only near the top.
	 */
	public static boolean shouldPin(boolean onTop, double y, double dy, double topY) {
		return onTop && dy <= 1e-9 && y >= topY - PIN_BELOW && y <= topY + PIN_ABOVE;
	}

	/** An angle difference in radians, wrapped to (-pi, pi]. */
	public static double wrap(double a) {
		a = Math.IEEEremainder(a, 2.0 * Math.PI);
		return a <= -Math.PI ? a + 2.0 * Math.PI : a;
	}
}
