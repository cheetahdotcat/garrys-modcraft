package dev.gmodcraft.demo;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One demo build (P7b, protocol v19), as data: block placements relative to its origin, defined in
 * MC axes for yaw quarter 0 ("forward", away from the placing player, is +z = south) and rotated as
 * a whole. y = 0 is the layer just above the ground the admin aimed at. Block states
 * are strings ({@code minecraft:rail[shape=north_south]}) parsed at place time, so this class (and
 * its tests) need no Minecraft bootstrap.
 *
 * <p>Rotation: quarter q turns the shape q times clockwise seen from above (MC's
 * Rotation.CLOCKWISE_90 per quarter): (x, z) -> (-z, x). Yaw quarter = round(MC yaw / 90) mod 4, so
 * a player facing south (yaw 0) gets the shape as defined, facing west (90) gets +z turned to -x.
 * The GMod side (shared/demos.lua) uses the same table for its parts.
 */
public final class DemoShape {
	/** A block at (x, y, z) relative to the origin, unrotated. */
	public record Block(int x, int y, int z, String state) {
	}

	/** An entity Minecraft spawns with the demo (tagged; removed on clear): position + start velocity (x/z, unrotated). */
	public record Spawn(String type, double x, double y, double z, double vx, double vz) {
	}

	/** Items put into the container at (x, y, z) after placing. */
	public record Fill(int x, int y, int z, String item, int count) {
	}

	/** An axis-aligned box of block positions, inclusive. */
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public int sizeX() {
			return this.maxX - this.minX + 1;
		}

		public int sizeY() {
			return this.maxY - this.minY + 1;
		}

		public int sizeZ() {
			return this.maxZ - this.minZ + 1;
		}

		public Box offset(int x, int y, int z) {
			return new Box(this.minX + x, this.minY + y, this.minZ + z, this.maxX + x, this.maxY + y, this.maxZ + z);
		}

		public boolean contains(int x, int y, int z) {
			return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
		}

		public int volume() {
			return sizeX() * sizeY() * sizeZ();
		}
	}

	public final int kind;
	public final String name;
	public final List<Block> blocks;
	public final List<Spawn> spawns;
	public final List<Fill> fills;
	/** The unrotated box: every block plus the air the demo needs (a jump's gap), all must be empty to place. */
	public final Box box;

	DemoShape(int kind, String name, List<Block> blocks, List<Spawn> spawns, List<Fill> fills, Box extra) {
		this.kind = kind;
		this.name = name;
		this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
		this.spawns = Collections.unmodifiableList(new ArrayList<>(spawns));
		this.fills = Collections.unmodifiableList(new ArrayList<>(fills));
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (Block b : blocks) {
			x0 = Math.min(x0, b.x());
			y0 = Math.min(y0, b.y());
			z0 = Math.min(z0, b.z());
			x1 = Math.max(x1, b.x());
			y1 = Math.max(y1, b.y());
			z1 = Math.max(z1, b.z());
		}
		if (extra != null) {
			x0 = Math.min(x0, extra.minX());
			y0 = Math.min(y0, extra.minY());
			z0 = Math.min(z0, extra.minZ());
			x1 = Math.max(x1, extra.maxX());
			y1 = Math.max(y1, extra.maxY());
			z1 = Math.max(z1, extra.maxZ());
		}
		this.box = new Box(x0, y0, z0, x1, y1, z1);
	}

	/** Whether the shape fits the hard size limit (kDemoMaxX x kDemoMaxY x kDemoMaxZ, in any rotation). */
	public boolean fitsLimit() {
		int horiz = Math.max(this.box.sizeX(), this.box.sizeZ());
		return horiz <= Math.min(Proto.DEMO_MAX_X, Proto.DEMO_MAX_Z) && this.box.sizeY() <= Proto.DEMO_MAX_Y;
	}

	/** (x, z) turned q quarters clockwise seen from above: (x, z) -> (-z, x) per quarter. */
	public static int[] rotate(int x, int z, int q) {
		int rx = x, rz = z;
		for (int i = 0; i < Math.floorMod(q, 4); i++) {
			int t = rx;
			rx = -rz;
			rz = t;
		}
		return new int[] { rx, rz };
	}

	public static double[] rotate(double x, double z, int q) {
		double rx = x, rz = z;
		for (int i = 0; i < Math.floorMod(q, 4); i++) {
			double t = rx;
			rx = -rz;
			rz = t;
		}
		return new double[] { rx, rz };
	}

	/** MC yaw (degrees, 0 = south, 90 = west) -> yaw quarter 0..3. */
	public static int quarter(double yaw) {
		if (!Double.isFinite(yaw)) {
			return 0;
		}
		return Math.floorMod((int) Math.round(yaw / 90.0), 4);
	}

	/** The box turned q quarters (relative to the origin). */
	public Box box(int q) {
		int[] a = rotate(this.box.minX(), this.box.minZ(), q);
		int[] b = rotate(this.box.maxX(), this.box.maxZ(), q);
		return new Box(Math.min(a[0], b[0]), this.box.minY(), Math.min(a[1], b[1]), Math.max(a[0], b[0]), this.box.maxY(), Math.max(a[1], b[1]));
	}

	/** The world box for an origin (x, y, z) and quarter q. */
	public Box worldBox(int ox, int oy, int oz, int q) {
		return box(q).offset(ox, oy, oz);
	}
}
