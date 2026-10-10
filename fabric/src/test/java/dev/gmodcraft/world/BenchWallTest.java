package dev.gmodcraft.world;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Manual bench of the wall pieces against the old 16-middle scheme (review of b716538), in cluttered
 * geometry: GMODCRAFT_BENCH=1 [GMODCRAFT_BENCH_SLACK=0.125] ./gradlew test --tests dev.gmodcraft.world.BenchWallTest -i | grep BENCH
 */
class BenchWallTest {
	record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

	@Test
	void bench() {
		org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("GMODCRAFT_BENCH") != null, "manual bench (GMODCRAFT_BENCH=1)");
		List<Box> boxes = new ArrayList<>();
		boxes.add(new Box(-17.6, 63.6, 25.6, 12.4, 64.0, 47.6));
		boxes.add(new Box(-17.6, 63.275, 25.6, 12.4, 63.8, 47.6)); boxes.add(new Box(-30, 55, 10, 30, 60.0, 60));
		java.util.Random r = new java.util.Random(1);
		for (int i = 0; i < 30; i++) { // clutter: small brushes around the cell
			double x = 8.6 + r.nextDouble() * 1.2, y = 60 + r.nextDouble() * 6, z = 24 + r.nextDouble() * 8;
			boxes.add(new Box(x, y, z, x + 0.3 + r.nextDouble(), y + 0.3 + r.nextDouble(), z + 0.3 + r.nextDouble()));
		}
		List<SkyTri> tris = new ArrayList<>();
		for (Box b : boxes) DigWallMaterialTest.box(tris, b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, 0);
		SkyDig.Probe probe = new SkyDig.Probe((box, out) -> {
			for (SkyTri t : tris) if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) out.add(t);
		});
		// GMODCRAFT_BENCH_SLACK=0.125: the host voxels' slack (each box reaches that much further), as in game
		double sl = Double.parseDouble(System.getenv().getOrDefault("GMODCRAFT_BENCH_SLACK", "0"));
		SkyDig.Voxels v = (x, y, z) -> { for (Box b : boxes) if (x >= b.x0 - sl && x <= b.x1 + sl && z >= b.z0 - sl && z <= b.z1 + sl && y >= b.y0 - sl && y <= b.y1 + sl) return true; return false; };
		probe.around(9, 63, 27, 10, 64, 28); StringBuilder sb = new StringBuilder(); for (int j = 0; j < 16; j++) sb.append(probe.wallMaterial(v, false, 9.02, 63 + (j + .5) / 16, 27.5)).append(' '); System.out.println("BENCH col63 " + sb);
		for (int round = 0; round < 6; round++) {
			int[] calls = {0}, pieces = {0};
			long t0 = System.nanoTime();
			int faces = 0;
			for (int y = 62; y <= 65; y++) {
				for (int zc = 25; zc < 31; zc++) {
					final int yy = y, zz = zc;
					probe.around(9, y, zc, 10, y + 1, zc + 1);
					WallPieces.sample((u, w) -> { calls[0]++; return probe.wallMaterial(v, false, 9.02, yy + w, zz + u); }, (a, b, c, d, m, dep) -> pieces[0]++);
					faces++;
				}
			}
			long dt = System.nanoTime() - t0;
			// old scheme: 16 centre probes
			int[] old = {0};
			long t1 = System.nanoTime();
			for (int y = 62; y <= 65; y++) for (int zc = 25; zc < 31; zc++) {
				probe.around(9, y, zc, 10, y + 1, zc + 1);
				for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { old[0]++; probe.wallMaterial(v, false, 9.02, y + (j + .5) / 4, zc + (i + .5) / 4); }
			}
			long dt1 = System.nanoTime() - t1;
			System.out.printf("BENCH round %d: %d faces, new %d probes %d pieces %.1f us/face (%.2f us/probe); old %d probes %.1f us/face, tris %d%n", round, faces, calls[0], pieces[0], dt / 1e3 / faces, dt / 1e3 / calls[0], old[0], dt1 / 1e3 / faces, tris.size());
		}
	}
}
