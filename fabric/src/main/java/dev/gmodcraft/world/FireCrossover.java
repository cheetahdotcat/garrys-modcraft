package dev.gmodcraft.world;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.GmodCraftConfig;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.slot.SlotJobs;
import dev.gmodcraft.wire.Bridges;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * F1 (protocol v28): fire both ways between Minecraft and the host.
 * <ul>
 * <li>MC to host: every {@link #SCAN_TICKS} ticks the blocks around each player inside the map's slot
 * are scanned (sections without fire / lava skipped by palette); a fire or lava block touching the host's
 * dynamic collision layer (a GMod prop) is sent as kEvFireContact (a cell at most every
 * {@link #CELL_COOLDOWN_TICKS}, at most kFireContactsPerScan per scan). Flame arrows and fire charges set
 * kProjOnFire on their kEvProjectileHit (ProjectileHits).</li>
 * <li>Host to MC: kHostEvFire (something of the host burns at x/y/z): fire is set on air cells next to
 * flammable MC blocks within a blocks; only inside the map's slot, never in a dug cell, a cell with host
 * geometry (the map's surfaces aren't MC blocks) or a column a re-anchor holds. At most
 * kFireBlocksPerEvent per event and kFireBlocksPerTick per tick. Vanilla fire spreads from there.</li>
 * </ul>
 * Both need the server rule fireCrossover and fire spread on (gamerule fireSpreadRadiusAroundPlayer not 0).
 */
public final class FireCrossover {
	static final int SCAN_TICKS = 10;
	static final int SCAN_RADIUS = 24;       // blocks around a player, horizontally
	static final int SCAN_HEIGHT = 12;       // blocks above / below
	static final int CELL_COOLDOWN_TICKS = 40;
	private static final Long2LongOpenHashMap SENT = new Long2LongOpenHashMap();
	private static int budget = Proto.FIRE_BLOCKS_PER_TICK;
	private static long ticks;
	private static int placedLogged, contactsLogged;
	private static long placedTotal, contactsTotal, refusedTotal;

	private FireCrossover() {
	}

	/** The rule fireCrossover and MC's fire spread (gamerule fireSpreadRadiusAroundPlayer: 0 = off). */
	public static boolean enabled(Level level) {
		if (level.isClientSide() || !GmodCraftConfig.rules().fireCrossover() || !(level instanceof ServerLevel sl)) {
			return false;
		}
		Integer radius = sl.getServer().getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
		return radius == null || radius != 0;
	}

	static boolean burning(BlockState s) {
		return s.is(BlockTags.FIRE) || s.getFluidState().is(FluidTags.LAVA);
	}

	/** Once per server tick (linked): refill the fire budget, scan for contacts every SCAN_TICKS. */
	public static void tick(MinecraftServer server) {
		budget = Proto.FIRE_BLOCKS_PER_TICK;
		if (++ticks % SCAN_TICKS != 0 || !ServerHost.slotKnown() || !SkyCollision.SERVER.active()) {
			return;
		}
		ServerLevel level = server.overworld();
		if (!enabled(level)) {
			return;
		}
		if (SENT.size() > 4096) {
			SENT.long2LongEntrySet().removeIf(e -> ticks - e.getLongValue() > CELL_COOLDOWN_TICKS);
		}
		int sent = 0;
		List<SkyTri> tris = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (ServerPlayer player : level.players()) {
			BlockPos c = player.blockPosition();
			if (!Bridges.inSlot(level, c)) {
				continue;
			}
			int minY = Math.max(level.getMinY(), c.getY() - SCAN_HEIGHT), maxY = Math.min(level.getMaxY(), c.getY() + SCAN_HEIGHT);
			for (int cx = (c.getX() - SCAN_RADIUS) >> 4; cx <= (c.getX() + SCAN_RADIUS) >> 4; cx++) {
				for (int cz = (c.getZ() - SCAN_RADIUS) >> 4; cz <= (c.getZ() + SCAN_RADIUS) >> 4; cz++) {
					if (!level.hasChunk(cx, cz)) {
						continue;
					}
					LevelChunk chunk = level.getChunk(cx, cz);
					LevelChunkSection[] sections = chunk.getSections();
					for (int si = 0; si < sections.length; si++) {
						LevelChunkSection sec = sections[si];
						int sy = level.getSectionYFromSectionIndex(si) << 4;
						if (sec == null || sec.hasOnlyAir() || sy + 15 < minY || sy > maxY || !sec.maybeHas(FireCrossover::burning)) {
							continue;
						}
						// no host prop anywhere near this section: none of its fire / lava can touch one
						int bx = cx << 4, bz = cz << 4;
						if (!SkyCollision.SERVER.hasDynamicNear(bx - 1, sy - 1, bz - 1, bx + 17, sy + 17, bz + 17)) {
							continue;
						}
						for (int i = 0; i < 4096; i++) {
							int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
							BlockState s = sec.getBlockState(x, y, z);
							if (!burning(s)) {
								continue;
							}
							p.set(bx + x, sy + y, bz + z);
							long key = p.asLong();
							if (ticks - SENT.getOrDefault(key, -1000L) < CELL_COOLDOWN_TICKS
								|| !SkyCollision.SERVER.hasDynamicNear(p.getX() - 0.3, p.getY() - 0.3, p.getZ() - 0.3, p.getX() + 1.3, p.getY() + 1.3, p.getZ() + 1.3)) {
								continue;
							}
							tris.clear();
							SkyCollision.SERVER.trianglesNear(new AABB(p).inflate(0.3), tris);
							boolean touches = false;
							for (SkyTri t : tris) {
								if (t.dynamic) {
									touches = true;
									break;
								}
							}
							if (!touches) {
								continue;
							}
							int hazard = s.getFluidState().is(FluidTags.LAVA) ? Proto.HAZARD_LAVA : Proto.HAZARD_FIRE;
							if (!ServerLink.INSTANCE.pushEvent(Proto.EV_FIRE_CONTACT, 0, 0L, p.getX() + 0.5F, p.getY() + 0.5F, p.getZ() + 0.5F, 0.0F, 0, hazard,
								ServerHost.worldId(), 0)) {
								return;
							}
							SENT.put(key, ticks);
							contactsTotal++;
							if (contactsLogged++ < 5) {
								GmodCraft.LOG.info("GmodCraft: {} at {} touches a GMod entity: fire contact sent ({} so far)", hazard == Proto.HAZARD_LAVA ? "lava" : "fire",
									p.toShortString(), contactsTotal);
							}
							if (++sent >= Proto.FIRE_CONTACTS_PER_SCAN) {
								return;
							}
						}
					}
				}
			}
		}
	}

	/** kHostEvFire: x/y/z = where the host's thing burns (MC coords), a = radius (blocks), code = FireCause. */
	public static void hostFire(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerLevel level = server.overworld();
		if (!enabled(level) || !ServerHost.slotKnown() || ev.worldId() != ServerHost.worldId() || !Double.isFinite(ev.x() + ev.y() + ev.z())) {
			refusedTotal++;
			return;
		}
		BlockPos centre = BlockPos.containing(ev.x(), ev.y(), ev.z());
		int r = Math.max(1, Math.min(3, ev.a()));
		int placed = 0;
		// nearest cells first (distance shells), so a small radius stays next to the burning thing
		for (int d = 0; d <= r && placed < Proto.FIRE_BLOCKS_PER_EVENT && budget > 0; d++) {
			for (int dx = -d; dx <= d && placed < Proto.FIRE_BLOCKS_PER_EVENT && budget > 0; dx++) {
				for (int dy = -d; dy <= d && placed < Proto.FIRE_BLOCKS_PER_EVENT && budget > 0; dy++) {
					for (int dz = -d; dz <= d && placed < Proto.FIRE_BLOCKS_PER_EVENT && budget > 0; dz++) {
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != d) {
							continue;
						}
						BlockPos pos = centre.offset(dx, dy, dz);
						if (canIgnite(level, pos)) {
							level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
							placed++;
							budget--;
						}
					}
				}
			}
		}
		if (placed > 0) {
			placedTotal += placed;
			if (placedLogged++ < 5) {
				GmodCraft.LOG.info("GmodCraft: GMod {} at {}: {} fire block(s) set ({} so far)", ev.code() == Proto.FIRE_EXPLOSION ? "explosion" : "fire",
					centre.toShortString(), placed, placedTotal);
			}
		}
	}

	/** An air cell MC may set on fire for the host: in the slot, no host geometry, not dug or held, next to something flammable. */
	static boolean canIgnite(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos) || !Bridges.inSlot(level, pos) || SlotJobs.lockedAt(pos.getX(), pos.getZ()) || !level.getBlockState(pos).isAir()
			|| SkyCollision.SERVER.hasGeometry(pos) || SkyDig.isDug(level, ServerHost.worldId(), pos)) {
			return false;
		}
		boolean flammable = false;
		for (Direction dir : Direction.values()) {
			if (level.getBlockState(pos.relative(dir)).ignitedByLava()) {
				flammable = true;
				break;
			}
		}
		return flammable && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP);
	}

	/** For the debug dump. */
	public static String stats() {
		return "contacts " + contactsTotal + ", fire blocks set " + placedTotal + ", refused " + refusedTotal;
	}
}
