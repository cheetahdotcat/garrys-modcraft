package dev.gmodcraft.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft explosions (TNT, creepers, fireballs, beds...) blowing up Skyrim's ground and rock as if
 * they were blocks of what they're made of: the blast is soaked up by them like by those blocks
 * (dirt gives way, stone holds more, buildings stop it), and what it reaches becomes that block for
 * the explosion to break the normal way (drops and all), dug out of Skyrim. The crater is then
 * lined like a dug hole (SkyDig). A Minecraft block the blast breaks takes the map in its cell with
 * it (D4); rule digThinWalls off, thin walls stay and stop the blast.
 *
 * Needs Skyrim's geometry around the blast, which only the host's Minecraft has (the integrated
 * server shares its SkyCollision): a guest's explosions far from the host leave Skyrim alone.
 */
public final class SkyDigBlast {
	/** Blast resistance of what can't be dug (buildings): stops a blast like obsidian. */
	private static final float BUILDING_RESISTANCE = 1200.0F;
	private static final double[] SAMPLE = { 0.2, 0.5, 0.8 };

	private final ServerLevel level;
	private final int world;
	private final SkyDig.Probe probe;
	private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap(); // cell -> material, 0 none, KEEP
	private final List<BlockPos> opened = new ArrayList<>();
	private final Host host;

	private SkyDigBlast(ServerLevel level, int world, Vec3 center, float radius) {
		this.level = level;
		this.world = world;
		double reach = radius * 1.4 + 2.0; // rays go up to 1.3 x radius
		this.probe = new SkyDig.Probe(SkyCollision.SERVER).around(center.x - reach, center.y - reach, center.z - reach, center.x + reach, center.y + reach, center.z + reach);
		this.cells.defaultReturnValue(Integer.MIN_VALUE);
		this.host = new Host() {
			@Override
			public boolean dug(int x, int y, int z) {
				return SkyDig.isDug(level, world, new BlockPos(x, y, z));
			}

			@Override
			public boolean thinRefused(int x, int y, int z) {
				return SkyDig.thinForBlast(level, world, new BlockPos(x, y, z));
			}
		};
	}

	/** For an explosion about to go off: null unless Skyrim's geometry around it is known. */
	public static @Nullable SkyDigBlast begin(ServerExplosion explosion) {
		Vec3 c = explosion.center();
		SkyCollision store = SkyCollision.SERVER; // explosions happen on the server
		if (!SkyDig.digs() || !dev.gmodcraft.ServerHost.linked() || !store.active()
			|| !store.isKnown((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z))) {
			return null;
		}
		return new SkyDigBlast(explosion.level(), dev.gmodcraft.ServerHost.worldId(), c, explosion.radius());
	}

	/** What a blast needs to know about one cell besides the probe (stubbed in tests). */
	interface Host {
		boolean dug(int x, int y, int z);

		/** Rule digThinWalls off and the cell a thin wall ({@link ThinWall#thin}). */
		boolean thinRefused(int x, int y, int z);

		/** {@link SkyDig#underground} (a seam: SkyDig itself needs a running game to load). */
		default int underground(int surface, double depth) {
			return SkyDig.underground(surface, depth);
		}
	}

	/**
	 * What Skyrim geometry fills this cell as far as a blast cares: a Proto.DIG_* material if any of
	 * it is diggable ground or rock (even a little: the surface layer goes too), KEEP for a building
	 * or a thin wall the rules keep, 0 for nothing (or already dug). D4: a cell holding a Minecraft
	 * block counts only where a diggable map surface passes through it (the map floor sits in the
	 * cell of the top block under it): the blast that breaks the block carves the map there too,
	 * while the Minecraft ground under the floor stays Minecraft's.
	 */
	static int material(SkyDig.Probe probe, Host host, int x, int y, int z, boolean minecraftBlock) {
		if (host.dug(x, y, z) || (minecraftBlock && !surfaceIn(probe, x, y, z))) {
			return 0;
		}
		int result = 0;
		for (double sy : SAMPLE) {
			for (double sz : SAMPLE) {
				for (double sx : SAMPLE) {
					int r = probe.test(x + sx, y + sy, z + sz);
					if (r == SkyDig.KEEP) {
						return SkyDig.KEEP;
					}
					if (r > SkyDig.AIR && result == 0) {
						result = host.underground(r, Math.max(probe.depth, 0.5));
					}
				}
			}
		}
		return result > 0 && host.thinRefused(x, y, z) ? SkyDig.KEEP : result;
	}

	/**
	 * D5: a player broke the Minecraft block in cell (x, y, z): does the map go with it? Same rule as
	 * a blast breaking that block (D4): a diggable map surface passes through the cell, no building
	 * there, and not a thin wall the rules keep.
	 */
	static boolean carvesWithBlock(SkyDig.Probe probe, Host host, int x, int y, int z) {
		return carve(material(probe, host, x, y, z, true), true) == CARVE;
	}

	/**
	 * D5: after a player broke a Minecraft block by hand (server side). On flat_everywhere the map's
	 * floor sits in the top block's cell, so breaking that block digs the map there too, in the same
	 * break. No drops for the map part (the block's own drops are the break's); Minecraft ground under
	 * the floor (no map surface in its cell) stays Minecraft's.
	 */
	public static void handBroke(ServerLevel level, BlockPos pos) {
		SkyCollision store = SkyCollision.SERVER;
		if (!SkyDig.digs() || !dev.gmodcraft.ServerHost.linked() || !store.active() || !store.isKnown(pos.getX(), pos.getY(), pos.getZ())
			|| !level.isLoaded(pos) || dev.gmodcraft.slot.SlotJobs.lockedAt(pos.getX(), pos.getZ())) {
			return;
		}
		int world = dev.gmodcraft.ServerHost.worldId();
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		SkyDig.Probe probe = new SkyDig.Probe(store).around(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4);
		Host host = new Host() {
			@Override
			public boolean dug(int hx, int hy, int hz) {
				return SkyDig.isDug(level, world, new BlockPos(hx, hy, hz));
			}

			@Override
			public boolean thinRefused(int hx, int hy, int hz) {
				return SkyDig.thinForBlast(level, world, new BlockPos(hx, hy, hz)); // silent: the block did break
			}
		};
		if (carvesWithBlock(probe, host, x, y, z)) {
			SkyDig.markDug(level, world, pos);
		}
	}

	/** Does a diggable map surface pass through cell (x, y, z)? Its bounds and plane reach into the cell. */
	static boolean surfaceIn(SkyDig.Probe probe, int x, int y, int z) {
		return surfaceIn(probe.land, x, y, z) || surfaceIn(probe.tris, x, y, z);
	}

	private static boolean surfaceIn(List<SkyTri> tris, int x, int y, int z) {
		for (SkyTri t : tris) {
			if (!t.diggable || t.maxX < x || t.minX > x + 1 || t.maxY < y || t.minY > y + 1 || t.maxZ < z || t.minZ > z + 1) {
				continue;
			}
			// The plane crosses the cell: its corners aren't all on one side.
			double d = t.nx * t.ax + t.ny * t.ay + t.nz * t.az;
			double centre = t.nx * (x + 0.5) + t.ny * (y + 0.5) + t.nz * (z + 0.5) - d;
			double half = 0.5 * (Math.abs(t.nx) + Math.abs(t.ny) + Math.abs(t.nz));
			if (Math.abs(centre) <= half) {
				return true;
			}
		}
		return false;
	}

	/** What {@link #materialize} does with a cell the blast breaks: nothing, carve the map only, or carve it and put its block there. */
	static final int SKIP = 0, CARVE = 1, CARVE_AND_FILL = 2;

	/**
	 * D4: a cell the explosion breaks. Holding Skyrim geometry it's carved out of the map; empty in
	 * Minecraft it also becomes the block the geometry is made of (for the blast to break, drops and
	 * all). A Minecraft block already there is the blast's to break as usual (its own resistance
	 * already let it through), so the map and the block go in one blast.
	 */
	static int carve(int material, boolean minecraftBlock) {
		if (material <= 0) {
			return SKIP;
		}
		return minecraftBlock ? CARVE : CARVE_AND_FILL;
	}

	private int material(BlockPos pos) {
		long key = pos.asLong();
		int known = this.cells.get(key);
		if (known != Integer.MIN_VALUE) {
			return known;
		}
		int result = material(this.probe, this.host, pos.getX(), pos.getY(), pos.getZ(), !this.level.getBlockState(pos).isAir());
		this.cells.put(key, result);
		return result;
	}

	/** The explosion's ray reached this cell: how much Skyrim geometry there soaks up. */
	public Optional<Float> resistance(BlockPos pos, Optional<Float> vanilla) {
		if (vanilla.isPresent()) {
			return vanilla;
		}
		int m = material(pos);
		if (m == SkyDig.KEEP) {
			return Optional.of(BUILDING_RESISTANCE);
		}
		return m > 0 ? Optional.of(SkyDig.materialState(m).getBlock().getExplosionResistance()) : vanilla;
	}

	/**
	 * The cells the explosion will break: Skyrim geometry in them becomes the block it's made of
	 * (dug out of Skyrim), so the explosion breaks it like any block.
	 */
	public void materialize(List<BlockPos> targets, boolean breaksBlocks) {
		if (!breaksBlocks) {
			return;
		}
		for (BlockPos pos : targets) {
			int m = material(pos);
			int what = carve(m, !this.level.getBlockState(pos).isAir());
			if (what == SKIP || !SkyDig.markDug(this.level, this.world, pos)) {
				continue;
			}
			if (what == CARVE_AND_FILL) {
				this.level.setBlock(pos, SkyDig.blockFor(this.level, pos, m), 2 | 16);
			}
			this.opened.add(pos.immutable());
		}
	}

	/** After the explosion: the cells around the crater that are inside Skyrim's geometry. */
	public void finish() {
		if (this.opened.isEmpty()) {
			return;
		}
		LongOpenHashSet done = new LongOpenHashSet();
		for (BlockPos pos : this.opened) {
			done.add(pos.asLong());
		}
		for (BlockPos pos : this.opened) {
			for (Direction d : Direction.values()) {
				BlockPos n = pos.relative(d);
				if (!done.add(n.asLong()) || SkyDig.isDug(this.level, this.world, n)) {
					continue;
				}
				int material = SkyDig.classify(SkyCollision.SERVER, n.getX(), n.getY(), n.getZ());
				if (material > SkyDig.AIR && SkyDig.fillable(this.level, this.world, n)) { // D4: no block poking out of a wall
					SkyDig.digCell(this.level, this.world, n, material);
				}
			}
		}
	}
}
