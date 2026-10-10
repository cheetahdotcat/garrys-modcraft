package dev.gmodcraft.world;

import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.ServerLink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The host's water (map water volumes) as Minecraft water: the host sends the water surface over
 * the block columns around the player (see WaterGrid in the protocol), and wherever Minecraft has
 * air below that surface, entities treat it as water, so the player swims, floats, sinks slowly and
 * drowns there as in Minecraft water. Only entity physics sees it; no blocks change.
 *
 * <p>One per link, like SkyCollision: {@link #CLIENT} (client link, refreshed every frame, one
 * grid around this player) and {@link #SERVER} (server link, set every server tick by ServerHost:
 * protocol v14 has one grid per player slot, and the water is the union of them).
 */
public final class SkyWater {
	public static final SkyWater CLIENT = new SkyWater(ClientLink.INSTANCE);
	public static final SkyWater SERVER = new SkyWater(ServerLink.INSTANCE);

	private record Grid(int originX, int originZ, int size, float[] surface) {
	}

	private static final Grid[] NONE = new Grid[0];

	private final GLink link;
	// Every live grid; the first one covering a column (with water there) wins.
	private volatile Grid[] grids = NONE;

	private SkyWater(GLink link) {
		this.link = link;
	}

	public static SkyWater of(boolean clientSide) {
		return clientSide ? CLIENT : SERVER;
	}

	/** The water for a getter's side (non-Level getters: as SkyCollision.of). */
	public static SkyWater of(@Nullable BlockGetter getter) {
		if (getter instanceof net.minecraft.world.level.Level level) {
			return of(level.isClientSide());
		}
		return SERVER.active() || !CLIENT.active() ? SERVER : CLIENT;
	}

	/** Once a frame (client link): pick up the host's latest grid (a torn read keeps the last one). */
	public void refresh() {
		GLink.WaterGrid read = this.link instanceof ClientLink c ? c.readWaterGrid() : ((ServerLink) this.link).readWaterGrid();
		if (read != null) {
			grids = new Grid[] { toGrid(read) };
		}
	}

	/** Replaces every grid (server link: the live per-player grids, see ServerHost). Empty: no host water. */
	public void setGrids(java.util.List<GLink.WaterGrid> live) {
		Grid[] next = new Grid[live.size()];
		for (int i = 0; i < next.length; i++) {
			next[i] = toGrid(live.get(i));
		}
		grids = next.length == 0 ? NONE : next;
	}

	private static Grid toGrid(GLink.WaterGrid g) {
		return new Grid(g.originX, g.originZ, g.size, g.surface);
	}

	public void clear() {
		grids = NONE;
	}

	public boolean active() {
		return grids.length != 0;
	}

	/** How many grids are live (debug). */
	public int gridCount() {
		return grids.length;
	}

	/** Minecraft y of the host's water surface over this column, or NaN where there is none. */
	public double surfaceAt(int x, int z) {
		for (Grid g : grids) {
			int dx = x - g.originX(), dz = z - g.originZ();
			if (dx < 0 || dz < 0 || dx >= g.size() || dz >= g.size()) {
				continue;
			}
			float s = g.surface()[dz * g.size() + dx];
			if (s >= -1.0e20F) {
				return s;
			}
		}
		return Double.NaN;
	}

	/** How much of this block (0..1) is under Skyrim's water; 0 above the surface. */
	public float depthIn(BlockPos pos) {
		double s = surfaceAt(pos.getX(), pos.getZ());
		if (Double.isNaN(s)) {
			return 0.0F;
		}
		double h = s - pos.getY();
		return h < 0.02 ? 0.0F : (float) Math.min(1.0, h);
	}

	/** True if Skyrim water reaches up into the box of block cells (inclusive). */
	public boolean anyIn(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (grids.length == 0) {
			return false;
		}
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double s = surfaceAt(x, z);
				if (!Double.isNaN(s) && s > y0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Skyrim water in an otherwise empty (air) Minecraft cell, as a Minecraft fluid; null if none. */
	public @Nullable FluidState fluidAt(BlockGetter level, BlockPos pos) {
		if (depthIn(pos) <= 0.0F || !level.getBlockState(pos).isAir()) {
			return null;
		}
		return Fluids.WATER.getSource(false);
	}

	/** The exact water height in a cell only Skyrim fills (so floating matches its surface); -1 otherwise. */
	public float substitutedHeight(BlockGetter level, BlockPos pos) {
		float depth = depthIn(pos);
		if (depth <= 0.0F || !level.getFluidState(pos).isEmpty() || !level.getBlockState(pos).isAir()) {
			return -1.0F;
		}
		return depth;
	}
}
