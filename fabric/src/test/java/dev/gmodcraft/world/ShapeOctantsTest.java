package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gmodcraft.link.Proto;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShapeOctantsTest {
	private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
		return new double[] { x0, y0, z0, x1, y1, z1 };
	}

	@Test
	void slabs() {
		assertEquals(Proto.SHAPE_BOTTOM_SLAB, ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.5, 1))));
		assertEquals(Proto.SHAPE_TOP_SLAB, ShapeOctants.of(List.of(box(0, 0.5, 0, 1, 1, 1))));
	}

	@Test
	void fullAndThinAreFullCubes() {
		assertEquals(0, ShapeOctants.of(List.of(box(0, 0, 0, 1, 1, 1))));
		// a fence post (0.375..0.625 x 1.5 high): fills no octant -> full cube, as before
		assertEquals(0, ShapeOctants.of(List.of(box(0.375, 0, 0.375, 0.625, 1.5, 0.625))));
		// a door (3/16 thick, full height): fills no octant -> full cube
		assertEquals(0, ShapeOctants.of(List.of(box(0, 0, 0, 1, 1, 0.1875))));
	}

	@Test
	void lowShapes() {
		// carpet, pressure plate, snow layers 1-3 (up to 0.25 high): no GMod collision
		assertEquals(ShapeOctants.NONE, ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.0625, 1))));
		assertEquals(ShapeOctants.NONE, ShapeOctants.of(List.of(box(0.0625, 0, 0.0625, 0.9375, 0.0625, 0.9375))));
		assertEquals(ShapeOctants.NONE, ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.25, 1))));
		// snow layer 4 (0.375) or a daylight sensor: a bottom slab
		assertEquals(Proto.SHAPE_BOTTOM_SLAB, ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.375, 1))));
		assertEquals(Proto.SHAPE_BOTTOM_SLAB, ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.5, 1))));
	}

	@Test
	void stairs() {
		// bottom stair facing north (-z): the slab plus the north top quarter (z 0..0.5, y 0.5..1)
		int north = ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 1, 1, 0.5)));
		assertEquals(Proto.SHAPE_BOTTOM_SLAB | 0x0C, north);
		// facing east (+x): top quarter x 0.5..1 -> octants dx = 1, dy = 1: bits 3, 7
		int east = ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.5, 1), box(0.5, 0.5, 0, 1, 1, 1)));
		assertEquals(Proto.SHAPE_BOTTOM_SLAB | 0x88, east);
		// an outer corner stair: one top octant
		int corner = ShapeOctants.of(List.of(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 0.5, 1, 0.5)));
		assertEquals(Proto.SHAPE_BOTTOM_SLAB | 0x04, corner);
	}
}
