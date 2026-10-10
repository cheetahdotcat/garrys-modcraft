package dev.gmodcraft.combat;

import dev.gmodcraft.link.Proto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * T2 (protocol v29): which HeldMcEntities records apply this tick. Pure (no Minecraft classes), so the
 * release rules are unit-tested; {@link HeldMcEntities} applies the result to entities.
 * <ul>
 * <li>a torn read keeps the last good table (never "everything dropped out");</li>
 * <li>a new link session, or a table whose seq has not moved for kHeldStaleMs (the host stopped
 * writing; it writes at 20 Hz, also when empty), or never written: nothing is held;</li>
 * <li>records with id 0, no flags or a non-finite / absurd position or motion are ignored.</li>
 * </ul>
 */
public final class HeldTable {
	/** Largest |motion| component accepted, MC blocks per tick (a physgun flick stays far below it). */
	public static final double MAX_SPEED = 10.0;

	private final Map<Integer, HeldRecord> current = new LinkedHashMap<>();
	private int lastSeq = -1;
	private int lastGeneration = Integer.MIN_VALUE;
	private long seqChangedNs;

	/** One record as it applies (MC coords, blocks per tick; yaw / pitch MC degrees, NaN: Minecraft keeps its own). */
	public record HeldRecord(int entityId, int flags, long holderSteamId, double x, double y, double z, double vx, double vy, double vz, float yaw,
		float pitch) {
		/** v32: the record's facing, or null when it has none (NaN, infinite). */
		public boolean hasYaw() {
			return Float.isFinite(this.yaw);
		}

		public boolean hasPitch() {
			return Float.isFinite(this.pitch);
		}
	}

	/** v32: {@code target} unwrapped to the turn nearest {@code from} (no lerp the long way round, 359 -&gt; 1 is +2). */
	public static float nearestTurn(float from, float target) {
		float d = (target - from) % 360.0F;
		if (d >= 180.0F) {
			d -= 360.0F;
		} else if (d < -180.0F) {
			d += 360.0F;
		}
		return from + d;
	}

	/**
	 * One tick. {@code seq}: what the read returned (-1: torn / no link), {@code read}: the records it
	 * read. Returns the records that apply now, by entity id (in table order).
	 */
	public Map<Integer, HeldRecord> update(int generation, int seq, List<HeldRecord> read, long nowNs) {
		if (generation != this.lastGeneration) {
			this.lastGeneration = generation;
			this.lastSeq = -1;
			this.current.clear();
		}
		if (seq >= 0) {
			if (seq != this.lastSeq) {
				this.lastSeq = seq;
				this.seqChangedNs = nowNs;
			}
			this.current.clear();
			if (seq != 0) {
				for (HeldRecord r : read) {
					if (valid(r)) {
						this.current.put(r.entityId(), r);
					}
				}
			}
		}
		if (this.lastSeq <= 0 || nowNs - this.seqChangedNs > Proto.HELD_STALE_MS * 1_000_000L) {
			this.current.clear();
		}
		return this.current;
	}

	/** Forget everything (the link went down). */
	public void clear() {
		this.current.clear();
		this.lastSeq = -1;
	}

	static boolean valid(HeldRecord r) {
		return r.entityId() != 0 && r.flags() != 0 && finite(r.x()) && finite(r.y()) && finite(r.z()) && Math.abs(r.x()) < 3.0e7
			&& Math.abs(r.z()) < 3.0e7 && Math.abs(r.y()) < 1.0e5 && speedOk(r.vx()) && speedOk(r.vy()) && speedOk(r.vz());
	}

	private static boolean finite(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v);
	}

	private static boolean speedOk(double v) {
		return finite(v) && Math.abs(v) <= MAX_SPEED;
	}

	/** A punt impulse (kHostEvPuntMcEntity) scaled down to at most {@code max} blocks per tick; null if not finite. */
	public static double @Nullable [] clampImpulse(double x, double y, double z, double max) {
		if (!finite(x) || !finite(y) || !finite(z)) {
			return null;
		}
		double len = Math.sqrt(x * x + y * y + z * z);
		double k = len > max ? max / len : 1.0;
		return new double[] { x * k, y * k, z * k };
	}
}
