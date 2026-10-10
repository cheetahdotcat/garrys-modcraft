package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.GLink;
import dev.gmodcraft.link.Proto;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Server-side host water (protocol v14): the union of the per-player grids. */
class SkyWaterTest {
	private static GLink.WaterGrid grid(int ox, int oz, float surface) {
		GLink.WaterGrid g = new GLink.WaterGrid();
		g.originX = ox;
		g.originZ = oz;
		Arrays.fill(g.surface, surface);
		return g;
	}

	@AfterEach
	void clear() {
		SkyWater.SERVER.clear();
	}

	@Test
	void unionOfGrids() {
		GLink.WaterGrid a = grid(0, 0, 70.5F);
		GLink.WaterGrid b = grid(100, -16, 64.0F);
		SkyWater.SERVER.setGrids(List.of(a, b));
		assertTrue(SkyWater.SERVER.active());
		assertEquals(2, SkyWater.SERVER.gridCount());
		assertEquals(70.5, SkyWater.SERVER.surfaceAt(3, 15), 1e-6);
		assertEquals(64.0, SkyWater.SERVER.surfaceAt(115, -1), 1e-6);
		assertTrue(Double.isNaN(SkyWater.SERVER.surfaceAt(16, 0)), "outside every grid");
		assertTrue(Double.isNaN(SkyWater.SERVER.surfaceAt(99, -16)));
	}

	@Test
	void noWaterInOneGridFallsThroughToAnOverlappingOne() {
		GLink.WaterGrid dry = grid(0, 0, Proto.NO_WATER);
		GLink.WaterGrid wet = grid(8, 8, 50.0F);
		SkyWater.SERVER.setGrids(List.of(dry, wet));
		assertEquals(50.0, SkyWater.SERVER.surfaceAt(10, 10), 1e-6, "dry grid first, the wet one still counts");
		assertTrue(Double.isNaN(SkyWater.SERVER.surfaceAt(2, 2)));
	}

	@Test
	void emptyListIsNoWater() {
		SkyWater.SERVER.setGrids(List.of(grid(0, 0, 10.0F)));
		SkyWater.SERVER.setGrids(List.of());
		assertFalse(SkyWater.SERVER.active());
		assertTrue(Double.isNaN(SkyWater.SERVER.surfaceAt(1, 1)));
	}
}
