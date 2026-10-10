package dev.gmodcraft.client.render;

import dev.gmodcraft.world.SkyDig;
import dev.gmodcraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import dev.gmodcraft.world.SkyCollision;
import org.jspecify.annotations.Nullable;

/**
 * The walls of holes dug into Skyrim's world. A cell only partly inside Skyrim's geometry (the
 * ground's surface runs through it) stays Skyrim's; where it borders a dug cell, the part of it
 * under the surface is drawn as Minecraft faces (dirt, stone, sand...), so a hole is closed all
 * round and nothing sticks up out of the ground.
 *
 * Under the land those faces are cut exactly along the land's surface (its triangles), so they
 * meet the cut edge of Skyrim's ground with no gap. Elsewhere (rocks, cliffs, cave walls) they're
 * found by sampling, in quarter-block pieces: wherever GMod collides there (any brush or model,
 * diggable or not, floors and ceilings too), so a hole is sealed on every side that isn't open.
 */
final class DigWalls {
	private DigWalls() {
	}

	interface Out {
		/** One quad: four corners (x, y, z, section-relative) and their atlas UVs, facing {@code normal}. */
		void quad(float[] xyz, float[] uv, int light, Direction normal);
	}

	interface Faces {
		/** Side, top and bottom atlas rects (u0, v0, u1, v1) of a full-cube block, or null. */
		float[] of(BlockState state);
	}

	private static final Direction[] WALLS = Direction.values();
	private static final double INTO = 0.02; // test points just inside the wall's cell

	/** Adds the walls around the dug cells of one section (bits: x + 16z + 256y). */
	static void add(ClientLevel level, int sx, int sy, int sz, long[] dug, SkyDig.DugLookup lookup, Faces faces, Out out) {
		SkyDig.Probe probe = new SkyDig.Probe(dev.gmodcraft.world.SkyCollision.CLIENT);
		Emitter emit = new Emitter(sx, sy, sz, out);
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int word = 0; word < 64; word++) {
			long bits = dug[word];
			while (bits != 0) {
				int bit = word * 64 + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				int x = sx * 16 + (bit & 15), y = sy * 16 + (bit >> 8), z = sz * 16 + ((bit >> 4) & 15);
				cell.set(x, y, z);
				if (level.getBlockState(cell).canOcclude()) {
					continue; // a solid block fills the dug cell: nothing to see from in it
				}
				emit.light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, cell), level.getBrightness(LightLayer.SKY, cell));
				for (Direction dir : WALLS) {
					int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
					// a hull world's mirror block isn't drawn as a block (GMod draws the map): it gets its wall
					BlockPos n = new BlockPos(nx, ny, nz);
					BlockState nState = level.getBlockState(n);
					boolean mirror = !nState.isAir() && dev.gmodcraft.world.HullWorld.isMirror(level, n);
					if (!SkyDig.Probe.wallBetween(true, false, lookup.isDug(nx, ny, nz), nState.canOcclude(), mirror)) {
						continue;
					}
					probe.around(nx, ny, nz, nx + 1, ny + 1, nz + 1);
					emit.rects = null;
					emit.material = -1;
					emit.mirror = mirror ? nState : null;
					face(probe, voxels(probe, nx, ny, nz), dir, x, y, z, faces, emit);
				}
			}
		}
	}

	/**
	 * What GMod collides with in the neighbour cell (SkyCollision's voxels: brushes and models,
	 * diggable or not), or null for nothing there or a prop near (it moves; the section isn't
	 * re-meshed when it does).
	 */
	private static SkyDig.@Nullable Voxels voxels(SkyDig.Probe probe, int nx, int ny, int nz) {
		VoxelShape shape = SkyCollision.CLIENT.shapeAt(new BlockPos(nx, ny, nz));
		if (shape == null || shape.isEmpty() || probe.dynamicIn(nx, ny, nz, nx + 1, ny + 1, nz + 1)) {
			return null;
		}
		List<AABB> boxes = shape.toAabbs();
		return (px, py, pz) -> {
			double lx = px - nx, ly = py - ny, lz = pz - nz;
			for (AABB b : boxes) {
				if (lx >= b.minX && lx <= b.maxX && ly >= b.minY && ly <= b.maxY && lz >= b.minZ && lz <= b.maxZ) {
					return true;
				}
			}
			return false;
		};
	}

	/** Collects polygons for one face and emits them with the right texture. */
	private static final class Emitter {
		final int sx, sy, sz;
		final Out out;
		int light;
		float[] rects;
		int material;
		/** The neighbour's block when it's a hull world's mirror block, else null. */
		@Nullable BlockState mirror;
		final float[] xyz = new float[12];
		final float[] uv = new float[8];

		Emitter(int sx, int sy, int sz, Out out) {
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
			this.out = out;
		}

		boolean texture(Faces faces, int material, double depth) {
			if (this.rects == null) {
				this.material = material;
				this.rects = faces.of(SkyDig.materialState(SkyDig.underground(material, Math.max(depth, 0.5))));
			}
			return this.rects != null;
		}

		/** A piece showing the mirror neighbour's own block (the face keeps one texture: the first one set). */
		boolean mirrorTexture(Faces faces) {
			if (this.rects == null && this.mirror != null) {
				this.rects = faces.of(this.mirror);
			}
			return this.rects != null;
		}

		/**
		 * A convex polygon on the face, given in face coordinates (u across 0..1, v up 0..1) and as
		 * world points; emitted as a fan of quads (the last one doubled for a triangle).
		 */
		void polygon(List<double[]> corners, List<double[]> cornerUv, int rect, Direction normal) {
			// every caller's own corner order isn't the one that faces the normal (floors weren't): fixed here
			List<double[]> world = new ArrayList<>(corners);
			List<double[]> faceUv = new ArrayList<>(cornerUv);
			dev.gmodcraft.world.WallPieces.orient(world, faceUv, normal);
			for (int i = 1; i + 1 < world.size(); i += 2) {
				int a = 0, b = i, c = i + 1, d = Math.min(i + 2, world.size() - 1);
				int[] ids = { a, b, c, d };
				for (int k = 0; k < 4; k++) {
					double[] p = world.get(ids[k]);
					double[] t = faceUv.get(ids[k]);
					this.xyz[k * 3] = (float) (p[0] - this.sx * 16);
					this.xyz[k * 3 + 1] = (float) (p[1] - this.sy * 16);
					this.xyz[k * 3 + 2] = (float) (p[2] - this.sz * 16);
					this.uv[k * 2] = this.rects[rect] + (float) t[0] * (this.rects[rect + 2] - this.rects[rect]);
					this.uv[k * 2 + 1] = this.rects[rect + 1] + (float) t[1] * (this.rects[rect + 3] - this.rects[rect + 1]);
				}
				this.out.quad(this.xyz, this.uv, this.light, normal);
			}
		}
	}

	// ---- the face between dug cell (x, y, z) and its neighbour in dir -------------------------

	private static void face(SkyDig.Probe probe, SkyDig.@Nullable Voxels voxels, Direction dir, int x, int y, int z, Faces faces, Emitter emit) {
		boolean vertical = dir.getAxis() != Direction.Axis.Y;
		// Under the land: exact.
		if (!probe.land.isEmpty()) {
			if (vertical) {
				landSide(probe, dir, x, y, z, faces, emit);
			} else {
				landFlat(probe, dir == Direction.UP, x, y, z, faces, emit);
			}
		}
		// Rocks, cliffs, cave walls (and anywhere without land): sampled, above the land if any.
		if (probe.land.isEmpty() || !probe.tris.isEmpty()) {
			sampled(probe, voxels, dir, x, y, z, faces, emit);
		}
	}

	/** A point on the face: (u across, v up) for side faces; (u = x, v = z) for floors and ceilings. */
	private static double[] onFace(Direction dir, int x, int y, int z, double u, double v) {
		return dev.gmodcraft.world.WallPieces.onFace(dir, x, y, z, u, v);
	}

	private static int rectFor(Direction dir) {
		return dir == Direction.UP ? 8 : dir == Direction.DOWN ? 4 : 0; // a ceiling shows the block's bottom
	}

	// A side face: the part below the land's surface, whose outline along the face comes from the
	// land's triangles crossing the face's plane.
	private static void landSide(SkyDig.Probe probe, Direction dir, int x, int y, int z, Faces faces, Emitter emit) {
		// The land's height along the face, as segments (u0, h0, u1, h1).
		List<double[]> segments = new ArrayList<>();
		for (SkyTri t : probe.land) {
			double[] seg = cross(t, dir, x, z);
			if (seg != null) {
				segments.add(seg);
			}
		}
		if (segments.isEmpty()) {
			return;
		}
		List<Double> cuts = new ArrayList<>();
		cuts.add(0.0);
		cuts.add(1.0);
		for (double[] s : segments) {
			if (s[0] > 0 && s[0] < 1) {
				cuts.add(s[0]);
			}
			if (s[2] > 0 && s[2] < 1) {
				cuts.add(s[2]);
			}
		}
		cuts.sort(Double::compare);
		Direction normal = dir.getOpposite();
		for (int i = 0; i + 1 < cuts.size(); i++) {
			double u0 = cuts.get(i), u1 = cuts.get(i + 1);
			if (u1 - u0 < 1e-6) {
				continue;
			}
			double[] seg = covering(segments, (u0 + u1) * 0.5);
			if (seg == null) {
				continue;
			}
			double h0 = at(seg, u0) - y, h1 = at(seg, u1) - y;
			// The region under h between u0 and u1, clamped to the cell: split where h crosses 0 and 1.
			List<Double> us = new ArrayList<>(List.of(u0, u1));
			for (double level : new double[] { 0.0, 1.0 }) {
				if ((h0 - level) * (h1 - level) < 0) {
					us.add(u0 + (u1 - u0) * (level - h0) / (h1 - h0));
				}
			}
			us.sort(Double::compare);
			for (int k = 0; k + 1 < us.size(); k++) {
				double a = us.get(k), b = us.get(k + 1);
				double ha = Math.min(1.0, h0 + (h1 - h0) * (a - u0) / (u1 - u0));
				double hb = Math.min(1.0, h0 + (h1 - h0) * (b - u0) / (u1 - u0));
				if (ha <= 1e-4 && hb <= 1e-4) {
					continue;
				}
				ha = Math.max(ha, 0.0);
				hb = Math.max(hb, 0.0);
				if (!emit.texture(faces, surfaceMaterial(seg), 0.5)) {
					return;
				}
				List<double[]> world = List.of(onFace(dir, x, y, z, a, 0), onFace(dir, x, y, z, b, 0), onFace(dir, x, y, z, b, hb), onFace(dir, x, y, z, a, ha));
				List<double[]> uvs = List.of(new double[] { a, 1 }, new double[] { b, 1 }, new double[] { b, 1 - hb }, new double[] { a, 1 - ha });
				emit.polygon(world, uvs, rectFor(dir), normal);
			}
		}
	}

	private static int surfaceMaterial(double[] seg) {
		return (int) seg[4];
	}

	/**
	 * Where a land triangle crosses the side face's plane, as (u0, height0, u1, height1, material),
	 * u across the face (may reach past 0..1); null if it doesn't.
	 */
	private static double[] cross(SkyTri t, Direction dir, int x, int z) {
		boolean alongX = dir == Direction.NORTH || dir == Direction.SOUTH; // the face spans x
		double plane = switch (dir) {
			case NORTH -> z;
			case SOUTH -> z + 1;
			case WEST -> x;
			default -> x + 1;
		};
		double[][] v = { { t.ax, t.ay, t.az }, { t.bx, t.by, t.bz }, { t.cx, t.cy, t.cz } };
		List<double[]> hits = new ArrayList<>(2);
		for (int i = 0; i < 3; i++) {
			double[] p = v[i], q = v[(i + 1) % 3];
			double dp = (alongX ? p[2] : p[0]) - plane, dq = (alongX ? q[2] : q[0]) - plane;
			if ((dp < 0 && dq >= 0) || (dp >= 0 && dq < 0)) {
				double f = dp / (dp - dq);
				double along = alongX ? p[0] + (q[0] - p[0]) * f : p[2] + (q[2] - p[2]) * f;
				double h = p[1] + (q[1] - p[1]) * f;
				hits.add(new double[] { along, h });
			}
		}
		if (hits.size() < 2) {
			return null;
		}
		// Face u runs with x or z depending on the side (see onFace).
		double[] a = hits.get(0), b = hits.get(1);
		double ua = toU(dir, x, z, a[0]), ub = toU(dir, x, z, b[0]);
		if (ua > ub) {
			return new double[] { ub, b[1], ua, a[1], t.material };
		}
		return new double[] { ua, a[1], ub, b[1], t.material };
	}

	private static double toU(Direction dir, int x, int z, double along) {
		return switch (dir) {
			case NORTH -> along - x;
			case SOUTH -> x + 1 - along;
			case WEST -> z + 1 - along;
			default -> along - z;
		};
	}

	private static double[] covering(List<double[]> segments, double u) {
		double[] best = null;
		double bestH = Double.NEGATIVE_INFINITY;
		for (double[] s : segments) {
			if (u >= s[0] - 1e-9 && u <= s[2] + 1e-9 && s[2] - s[0] > 1e-9) {
				double h = at(s, u);
				if (h > bestH) {
					bestH = h;
					best = s;
				}
			}
		}
		return best;
	}

	private static double at(double[] s, double u) {
		return s[1] + (s[3] - s[1]) * (u - s[0]) / (s[2] - s[0]);
	}

	// A floor (the cell below the dug one) or ceiling (the cell above): the part of that face under
	// the land's surface, cut out of each land triangle's footprint.
	private static void landFlat(SkyDig.Probe probe, boolean ceiling, int x, int y, int z, Faces faces, Emitter emit) {
		double level = ceiling ? y + 1 : y;
		Direction dir = ceiling ? Direction.UP : Direction.DOWN;
		Direction normal = ceiling ? Direction.DOWN : Direction.UP;
		for (SkyTri t : probe.land) {
			if (t.maxX < x || t.minX > x + 1 || t.maxZ < z || t.minZ > z + 1 || t.maxY <= level) {
				continue;
			}
			if (Math.abs(t.ny) < 1e-6) {
				continue;
			}
			// The triangle's footprint (x, z), clipped to the cell, then to where its height > level.
			List<double[]> poly = new ArrayList<>(List.of(new double[] { t.ax, t.az, t.ay }, new double[] { t.bx, t.bz, t.by }, new double[] { t.cx, t.cz, t.cy }));
			poly = clip(poly, p -> p[0] - x);
			poly = clip(poly, p -> x + 1 - p[0]);
			poly = clip(poly, p -> p[1] - z);
			poly = clip(poly, p -> z + 1 - p[1]);
			poly = clip(poly, p -> p[2] - level);
			if (poly.size() < 3) {
				continue;
			}
			if (!emit.texture(faces, t.material, 0.5)) {
				return;
			}
			List<double[]> world = new ArrayList<>(poly.size());
			List<double[]> uvs = new ArrayList<>(poly.size());
			for (double[] p : poly) {
				world.add(new double[] { p[0], level, p[1] });
				uvs.add(new double[] { p[0] - x, p[1] - z });
			}
			emit.polygon(world, uvs, rectFor(dir), normal);
		}
	}

	/** Keeps the part of a convex polygon (x, z, height) where f >= 0 (f linear). */
	private static List<double[]> clip(List<double[]> in, java.util.function.ToDoubleFunction<double[]> f) {
		List<double[]> out = new ArrayList<>(in.size() + 2);
		for (int i = 0; i < in.size(); i++) {
			double[] a = in.get(i), b = in.get((i + 1) % in.size());
			double fa = f.applyAsDouble(a), fb = f.applyAsDouble(b);
			if (fa >= 0) {
				out.add(a);
			}
			if ((fa >= 0) != (fb >= 0)) {
				double s = fa / (fa - fb);
				out.add(new double[] { a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s, a[2] + (b[2] - a[2]) * s });
			}
		}
		return out;
	}

	// Rocks, cliffs, cave walls, buildings: the face in pieces (WallPieces: 4 x 4, split where a brush's
	// edge runs through one), each drawn where it is inside (and above the land, which is drawn exactly).
	// Inside: SkyDig.wallMaterial. Toward a hull world's mirror block also where the map has nothing but
	// no floor is under it (the void under a thin floor slab): there the piece shows the mirror block itself.
	private static void sampled(SkyDig.Probe probe, SkyDig.@Nullable Voxels voxels, Direction dir, int x, int y, int z, Faces faces, Emitter emit) {
		double ix = dir.getStepX() * INTO, iy = dir.getStepY() * INTO, iz = dir.getStepZ() * INTO;
		Direction normal = dir.getOpposite();
		boolean flat = dir.getAxis() == Direction.Axis.Y;
		double[] p = new double[3];
		double[] depth = { 0.5 };
		boolean mirror = emit.mirror != null;
		dev.gmodcraft.world.WallPieces.sample(new dev.gmodcraft.world.WallPieces.Decide() {
			@Override
			public int at(double u, double v) {
				dev.gmodcraft.world.WallPieces.onFace(dir, x, y, z, u, v, p);
				double px = p[0] + ix, py = p[1] + iy, pz = p[2] + iz;
				depth[0] = 0.5;
				double ground = probe.landHeight(px, pz);
				if (!Double.isNaN(ground) && py < ground) {
					return SkyDig.AIR; // under the land: done exactly
				}
				int m = probe.wallMaterial(voxels, mirror, px, py, pz);
				if (m > SkyDig.AIR) {
					depth[0] = probe.depth; // set by wallMaterial for this point
				}
				return m;
			}

			@Override
			public double depth() {
				return depth[0];
			}
		}, (u0, v0, u1, v1, material, pieceDepth) -> {
			if (material == SkyDig.Probe.MIRROR_FACE ? !emit.mirrorTexture(faces) : !emit.texture(faces, material, pieceDepth)) {
				return;
			}
			List<double[]> world = List.of(onFace(dir, x, y, z, u0, v0), onFace(dir, x, y, z, u1, v0), onFace(dir, x, y, z, u1, v1), onFace(dir, x, y, z, u0, v1));
			// a piece reaches a little past the face (WallPieces.GROW): its texture stays inside the sprite
			double a = clamp01(u0), b = clamp01(u1), c = clamp01(v0), d = clamp01(v1);
			List<double[]> uvs = flat
				? List.of(new double[] { a, c }, new double[] { b, c }, new double[] { b, d }, new double[] { a, d })
				: List.of(new double[] { a, 1 - c }, new double[] { b, 1 - c }, new double[] { b, 1 - d }, new double[] { a, 1 - d });
			emit.polygon(world, uvs, rectFor(dir), normal);
		});
	}

	private static double clamp01(double t) {
		return Math.max(0.0, Math.min(1.0, t));
	}
}
