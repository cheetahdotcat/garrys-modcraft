package dev.gmodcraft.world;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

/**
 * Day-night sync (protocol v43). The GMod server's rule (ServerState::sunSync) says who owns the time of
 * day; this side runs or stops the overworld clock accordingly, publishes the overworld's time of day
 * (McServerSky) for the GMod side, and applies kHostEvSetDayTime when GMod drives the time.
 */
public final class DayNight {
	private static final int PUBLISH_EVERY_TICKS = 5;
	private static final float[] V = new float[15];
	private static int appliedMode = -1;  // the sunSync mode last applied (-1: none since the link came up)
	private static int ticks;
	private static boolean publishNow;

	private DayNight() {
	}

	/** The link went down or a new session started: apply the mode again on the next read. */
	public static void reset() {
		appliedMode = -1;
	}

	/** Once per server tick with a live link: the host's mode (Proto.SUN_SYNC_*) and the McServerSky write. */
	public static void tick(MinecraftServer server, ServerLink link, int mode) {
		if (mode != appliedMode) {
			applyMode(server, mode);
			appliedMode = mode;
			publishNow = true;
		}
		if (publishNow || ++ticks >= PUBLISH_EVERY_TICKS) {
			ticks = 0;
			publishNow = false;
			publish(server, link);
		}
	}

	private static void applyMode(MinecraftServer server, int mode) {
		ServerLevel level = server.overworld();
		Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
		boolean run = mode == Proto.SUN_SYNC_MC_TO_GMOD || mode == Proto.SUN_SYNC_GMOD_TO_MC;
		server.getGameRules().set(GameRules.ADVANCE_TIME, run, server);
		if (clock.isPresent()) {
			ServerClockManager clocks = level.clockManager();
			clocks.setPaused(clock.get(), false);
			clocks.setRate(clock.get(), 1.0F);
		}
		GmodCraft.LOG.info("GmodCraft: day-night sync {}: the overworld clock {}", modeName(mode), run ? "runs" : "stands");
	}

	static String modeName(int mode) {
		return switch (mode) {
			case Proto.SUN_SYNC_OFF -> "off";
			case Proto.SUN_SYNC_MC_TO_GMOD -> "Minecraft -> GMod";
			case Proto.SUN_SYNC_GMOD_TO_MC -> "GMod -> Minecraft";
			default -> "mode " + mode;
		};
	}

	private static void publish(MinecraftServer server, ServerLink link) {
		ServerLevel level = server.overworld();
		Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
		int flags = 0;
		long dayTime = 0;
		int moonPhase = 0;
		try {
			EnvironmentAttributeSystem env = level.environmentAttributes();
			float rate = 1.0F;
			boolean paused = false;
			if (clock.isPresent()) {
				ClockInstance c = level.clockManager().getInstance(clock.get());
				dayTime = c.totalTicks();
				rate = c.rate();
				paused = c.isPaused();
			}
			boolean advancing = clock.isPresent() && !paused && server.getGameRules().get(GameRules.ADVANCE_TIME);
			V[0] = env.getDimensionValue(EnvironmentAttributes.SUN_ANGLE) * ((float) Math.PI / 180.0F);
			V[1] = env.getDimensionValue(EnvironmentAttributes.MOON_ANGLE) * ((float) Math.PI / 180.0F);
			V[2] = env.getDimensionValue(EnvironmentAttributes.STAR_BRIGHTNESS);
			V[3] = env.getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
			V[4] = rate;
			Vector3fc sky = env.getDimensionValue(EnvironmentAttributes.SKY_COLOR);
			Vector3fc fog = env.getDimensionValue(EnvironmentAttributes.FOG_COLOR);
			Vector4fc sunrise = env.getDimensionValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR);
			V[5] = sky.x();
			V[6] = sky.y();
			V[7] = sky.z();
			V[8] = fog.x();
			V[9] = fog.y();
			V[10] = fog.z();
			V[11] = sunrise.x();
			V[12] = sunrise.y();
			V[13] = sunrise.z();
			V[14] = sunrise.w();
			moonPhase = env.getDimensionValue(EnvironmentAttributes.MOON_PHASE).index();
			flags = Proto.MSV_SKY_VALID | (advancing ? Proto.MSV_SKY_ADVANCING : 0);
		} catch (RuntimeException e) {
			flags = 0;  // a half-loaded world: the GMod side keeps its own sky
		}
		link.writeMcServerSky(flags, dayTime, moonPhase, V);
	}

	/** kHostEvSetDayTime: GMod's day-night addon drives the clock (only in mode GMod -> Minecraft). */
	public static void hostSetDayTime(MinecraftServer server, ServerLink.HostEvent ev) {
		if (appliedMode != Proto.SUN_SYNC_GMOD_TO_MC) {
			return;
		}
		ServerLevel level = server.overworld();
		Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
		if (clock.isEmpty()) {
			return;
		}
		ServerClockManager clocks = level.clockManager();
		ClockInstance c = clocks.getInstance(clock.get());
		float rate = clampRate(ev.x());
		if (rate <= 0.0F) {
			if (!c.isPaused()) {
				clocks.setPaused(clock.get(), true);
			}
		} else {
			if (c.isPaused()) {
				clocks.setPaused(clock.get(), false);
			}
			if (Math.abs(c.rate() - rate) > 1e-4F) {
				clocks.setRate(clock.get(), rate);
			}
		}
		if ((ev.flags() & Proto.DAY_TIME_SET) != 0) {
			long target = nearestTotal(c.totalTicks(), ev.a());
			if (target != c.totalTicks()) {
				clocks.setTotalTicks(clock.get(), target);
			}
		}
		publishNow = true;
	}

	/** The clock rate of an event (NaN / negative: 0, at most kDayTimeMaxRate). */
	static float clampRate(double x) {
		if (!(x > 0)) {
			return 0.0F;
		}
		return (float) Math.min(x, Proto.DAY_TIME_MAX_RATE);
	}

	/**
	 * The total tick count nearest to {@code current} whose time of day is {@code timeOfDay} (taken mod
	 * kTicksPerDay): the day count stays unless the move crosses midnight (a half day or less either way).
	 */
	static long nearestTotal(long current, long timeOfDay) {
		long day = Proto.TICKS_PER_DAY;
		long tod = Math.floorMod(timeOfDay, day);
		long base = current - Math.floorMod(current, day);
		long t = base + tod;
		if (t - current > day / 2) {
			t -= day;
		} else if (current - t > day / 2) {
			t += day;
		}
		return Math.max(0, t);
	}
}
