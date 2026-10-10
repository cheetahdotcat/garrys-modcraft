package dev.gmodcraft.world;

/**
 * v38: the server rule digThinWalls' gate. Pure (no Minecraft classes), so it's unit-tested
 * (ThinWallTest); SkyDig.refusesThin runs it over the server's collision store.
 */
public final class ThinWall {
	private ThinWall() {
	}

	/** A cell is thin when at most this many of its 512 sub-voxels are solid (half: a 16 u wall voxelizes to 3 or 4 layers). */
	public static final int THIN_MAX = 256;

	/** What {@link #thin} reads about the cells around one (the server's collision store and dig state; stubbed in tests). */
	public interface Lookup {
		/** Solid sub-voxels in the cell (0 .. 512). */
		int solidCount(int x, int y, int z);

		/** The cell's solid's extent along x, y and z in sub-voxel layers (0 .. 8). */
		int[] extents(int x, int y, int z);

		boolean isDug(int x, int y, int z);

		/** Does the map's land (terrain: displacements, a shell without volume) reach into the cell? */
		boolean terrain(int x, int y, int z);
	}

	/**
	 * Is this cell a thin wall or floor (rule digThinWalls)? Thin: at most THIN_MAX solid, no land in
	 * it, and neither neighbour along the axis the solid is thinnest in (every axis when it spans the
	 * cell) is solid past THIN_MAX or dug: a thick wall's edge cell has its thick part behind it.
	 * Measured on gm_construct (2026-10-07, tools/spikes/thinwall_measure.cpp): blocks 26.8K of the
	 * 376K partly solid diggable brush / prop cells, 13 of the 124K next to a full cell (a thick
	 * solid's edge), none of the 750K land cells (a fill-only gate would block 99% of those).
	 */
	public static boolean thin(Lookup l, int x, int y, int z) {
		int count = l.solidCount(x, y, z);
		if (count <= 0 || count > THIN_MAX || l.terrain(x, y, z)) {
			return false;  // nothing known (fail open), mostly solid, or the land
		}
		int[] ext = l.extents(x, y, z);
		int min = Math.min(ext[0], Math.min(ext[1], ext[2]));
		for (int a = 0; a < 3; a++) {
			if (min < 8 && ext[a] != min) {
				continue;
			}
			for (int s = -1; s <= 1; s += 2) {
				int nx = x + (a == 0 ? s : 0), ny = y + (a == 1 ? s : 0), nz = z + (a == 2 ? s : 0);
				if (l.solidCount(nx, ny, nz) > THIN_MAX || l.isDug(nx, ny, nz)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * D4: may a blast's reveal (or anything that fills a cell wholly inside the map) put a block here?
	 * Not in a cell of the map's brushes / props that is mostly empty (solid in at most THIN_MAX of its
	 * sub-voxels): the probe can take such a cell for inside, and the block then pokes out of the
	 * wall. The land (a shell without volume) and cells nothing is voxelized in (deep inside) may.
	 */
	public static boolean fillable(int solidCount, boolean terrain) {
		return terrain || solidCount <= 0 || solidCount > THIN_MAX;
	}
}
