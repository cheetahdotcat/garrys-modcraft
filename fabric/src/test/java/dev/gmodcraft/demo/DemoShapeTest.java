package dev.gmodcraft.demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoShapeTest {
	/** The rotation table; module/test/demos_test.py asserts the same rows for shared/demos.lua. */
	static final int[][] ROTATIONS = {
		// x, z, q, x', z'
		{ 1, 0, 1, 0, 1 },    // east -> south
		{ 0, 1, 1, -1, 0 },   // south -> west
		{ 2, 3, 0, 2, 3 },
		{ 2, 3, 1, -3, 2 },
		{ 2, 3, 2, -2, -3 },
		{ 2, 3, 3, 3, -2 },
		{ 2, 3, 4, 2, 3 },
		{ 2, 3, -1, 3, -2 },
	};

	@Test
	void rotation() {
		for (int[] r : ROTATIONS) {
			assertArrayEquals(new int[] { r[3], r[4] }, DemoShape.rotate(r[0], r[1], r[2]), "rotate(" + r[0] + "," + r[1] + "," + r[2] + ")");
		}
	}

	@Test
	void yawQuarter() {
		assertEquals(0, DemoShape.quarter(0));
		assertEquals(1, DemoShape.quarter(90));
		assertEquals(2, DemoShape.quarter(180));
		assertEquals(3, DemoShape.quarter(270));
		assertEquals(3, DemoShape.quarter(-90));
		assertEquals(0, DemoShape.quarter(44));
		assertEquals(1, DemoShape.quarter(46));
		assertEquals(0, DemoShape.quarter(359));
		assertEquals(0, DemoShape.quarter(Double.NaN));
	}

	@Test
	void boxRotates() {
		DemoShape s = Demos.byName("ramp");
		assertNotNull(s);
		DemoShape.Box b0 = s.box(0), b1 = s.box(1);
		assertEquals(b0.sizeX(), b1.sizeZ());
		assertEquals(b0.sizeZ(), b1.sizeX());
		assertEquals(b0.volume(), s.box(2).volume());
		// every block lands inside the rotated box
		for (int q = 0; q < 4; q++) {
			DemoShape.Box b = s.box(q);
			for (DemoShape.Block blk : s.blocks) {
				int[] r = DemoShape.rotate(blk.x(), blk.z(), q);
				assertTrue(b.contains(r[0], blk.y(), r[1]), "q" + q + " " + blk);
			}
		}
	}

	@Test
	void everyDemoFitsTheLimitAndHasNoLiquid() {
		List<DemoShape> all = Demos.all();
		assertEquals(6, all.size());
		for (DemoShape s : all) {
			assertTrue(s.fitsLimit(), s.name + " " + s.box);
			assertNotNull(Demos.byKind(s.kind));
			for (DemoShape.Block b : s.blocks) {
				assertFalse(b.state().contains("lava") || b.state().contains("minecraft:water"), b.state());
			}
		}
		assertEquals("rails", Demos.byKind(Proto.DEMO_RAILS).name);
		assertEquals("digwall", Demos.byKind(Proto.DEMO_DIG_WALL).name);
	}

	@Test
	void sizeLimit() {
		DemoShape big = new DemoShape(99, "big", List.of(new DemoShape.Block(0, 0, 0, "minecraft:stone"), new DemoShape.Block(32, 0, 0, "minecraft:stone")),
			List.of(), List.of(), null);
		assertFalse(big.fitsLimit()); // 33 wide
		DemoShape tall = new DemoShape(98, "tall", List.of(new DemoShape.Block(0, 0, 0, "minecraft:stone"), new DemoShape.Block(0, 16, 0, "minecraft:stone")),
			List.of(), List.of(), null);
		assertFalse(tall.fitsLimit()); // 17 high
		DemoShape ok = new DemoShape(97, "ok", List.of(new DemoShape.Block(0, 0, 0, "minecraft:stone"), new DemoShape.Block(31, 15, 31, "minecraft:stone")),
			List.of(), List.of(), null);
		assertTrue(ok.fitsLimit());
	}

	/** A map-backed grid of strings ("air" where unset). */
	static final class MapGrid implements Snapshot.Grid<String> {
		final Map<String, String> cells = new HashMap<>();

		public String get(int x, int y, int z) {
			return this.cells.getOrDefault(x + "," + y + "," + z, "air");
		}

		public void set(int x, int y, int z, String v) {
			if ("air".equals(v)) {
				this.cells.remove(x + "," + y + "," + z);
			} else {
				this.cells.put(x + "," + y + "," + z, v);
			}
		}
	}

	@Test
	void snapshotRestoresExactly() {
		MapGrid g = new MapGrid();
		g.set(1, 0, 1, "dirt");
		g.set(2, 1, 2, "chest{Items:[diamond]}");
		g.set(9, 9, 9, "outside");
		DemoShape.Box box = new DemoShape.Box(0, 0, 0, 3, 2, 3);
		String before = Snapshot.hash(g, box, v -> v);
		Snapshot<String> snap = Snapshot.capture(g, box);
		assertEquals(box.volume(), snap.cells().size());
		for (int x = 0; x <= 3; x++) {
			for (int z = 0; z <= 3; z++) {
				g.set(x, 0, z, "smooth_stone");
				g.set(x, 1, z, "rail");
			}
		}
		g.set(2, 1, 2, "chest{Items:[]}");
		assertFalse(before.equals(Snapshot.hash(g, box, v -> v)));
		snap.restore(g);
		assertEquals(before, Snapshot.hash(g, box, v -> v));
		assertEquals("dirt", g.get(1, 0, 1));
		assertEquals("chest{Items:[diamond]}", g.get(2, 1, 2));
		assertEquals("outside", g.get(9, 9, 9));
		assertEquals(3, g.cells.size());
	}

	@Test
	void occupiedAreaIsRefused() {
		MapGrid g = new MapGrid();
		DemoShape s = Demos.byName("arena");
		DemoShape.Box box = s.worldBox(100, 64, -20, 1);
		assertNull(Snapshot.firstOccupied(g, box, "air"::equals));
		g.set(box.minX() + 2, box.minY() + 1, box.maxZ(), "stone");
		assertArrayEquals(new int[] { box.minX() + 2, box.minY() + 1, box.maxZ() }, Snapshot.firstOccupied(g, box, "air"::equals));
		g.set(box.maxX() + 1, box.minY(), box.minZ(), "stone"); // just outside: not this box's business
		g.set(box.minX() + 2, box.minY() + 1, box.maxZ(), "air");
		assertNull(Snapshot.firstOccupied(g, box, "air"::equals));
	}
}
