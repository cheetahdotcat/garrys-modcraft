package dev.gmodcraft.micro;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.micro.MicroGeom.Placement;
import dev.gmodcraft.micro.MicroGeom.Shape;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MicroGeomTest {
	private static final int X = 0, Y = 1, Z = 2;

	@Test
	void slabOnTopOfFullBlockGoesIntoTheCellAbove() {
		Placement p = MicroGeom.snap(Shape.FACE, 4, Y, true, new double[] { 0.3, 1.0, 0.7 });
		assertTrue(p.neighbour());
		assertArrayEquals(new int[] { 0, 0, 0, 8, 4, 8 }, p.box());
	}

	@Test
	void slabOnTopOfBottomSlabFillsTheSameCell() {
		Placement p = MicroGeom.snap(Shape.FACE, 4, Y, true, new double[] { 0.5, 0.5, 0.5 });
		assertFalse(p.neighbour());
		assertArrayEquals(new int[] { 0, 4, 0, 8, 8, 8 }, p.box());
	}

	@Test
	void coverOnTheSideOfABlockLandsInTheNeighbourAgainstTheSharedFace() {
		// clicked the west face (-x) of a full block: x = 0 in the clicked cell
		Placement west = MicroGeom.snap(Shape.FACE, 1, X, false, new double[] { 0.0, 0.4, 0.6 });
		assertTrue(west.neighbour());
		assertArrayEquals(new int[] { 7, 0, 0, 8, 8, 8 }, west.box());
		// the north face (-z) of a cover at z 7..8 inside a microblock: z = 7/8, room left -> same cell
		Placement inside = MicroGeom.snap(Shape.FACE, 2, Z, false, new double[] { 0.5, 0.5, 0.875 });
		assertFalse(inside.neighbour());
		assertArrayEquals(new int[] { 0, 0, 5, 8, 8, 7 }, inside.box());
	}

	@Test
	void ceilingCoverHangsUnderTheBlock() {
		Placement p = MicroGeom.snap(Shape.FACE, 1, Y, false, new double[] { 0.5, 0.0, 0.5 });
		assertTrue(p.neighbour());
		assertArrayEquals(new int[] { 0, 7, 0, 8, 8, 8 }, p.box());
	}

	@Test
	void hitRoundsToTheNearestEighth() {
		Placement p = MicroGeom.snap(Shape.FACE, 1, Y, true, new double[] { 0.5, 0.26, 0.5 }); // 2.08 -> 2
		assertArrayEquals(new int[] { 0, 2, 0, 8, 3, 8 }, p.box());
	}

	@Test
	void postsAndCornersSnapToTheirGridUnderTheHit() {
		Placement post = MicroGeom.snap(Shape.POST, 2, Y, true, new double[] { 0.8, 1.0, 0.1 });
		assertTrue(post.neighbour());
		assertArrayEquals(new int[] { 6, 0, 0, 8, 8, 2 }, post.box());
		Placement corner = MicroGeom.snap(Shape.CORNER, 4, X, true, new double[] { 1.0, 0.9, 0.2 });
		assertTrue(corner.neighbour());
		assertArrayEquals(new int[] { 0, 4, 0, 4, 8, 4 }, corner.box());
		// a hit exactly on the far edge stays inside the cell
		Placement edge = MicroGeom.snap(Shape.CORNER, 1, Y, true, new double[] { 1.0, 1.0, 1.0 });
		assertArrayEquals(new int[] { 7, 0, 7, 8, 1, 8 }, edge.box());
	}

	@Test
	void mergingChecksOverlap() {
		List<int[]> parts = new ArrayList<>();
		parts.add(new int[] { 0, 0, 0, 8, 4, 8 }); // bottom slab
		assertFalse(MicroGeom.fits(parts, new int[] { 0, 3, 0, 8, 4, 8 }));
		assertTrue(MicroGeom.fits(parts, new int[] { 0, 4, 0, 8, 8, 8 }));     // top slab touches, doesn't overlap
		parts.add(new int[] { 0, 4, 0, 8, 8, 1 });                               // a cover on the north side, top half
		assertFalse(MicroGeom.fits(parts, new int[] { 0, 4, 0, 1, 8, 8 }));      // west cover would cut it
		assertTrue(MicroGeom.fits(parts, new int[] { 3, 4, 3, 4, 5, 4 }));       // a small corner in the middle
	}

	@Test
	void innerFacesBetweenPartsAreHidden() {
		int[] bottom = { 0, 0, 0, 8, 4, 8 };
		int[] top = { 0, 4, 0, 8, 8, 8 };
		int[] corner = { 0, 4, 0, 2, 6, 2 };
		List<int[]> both = List.of(bottom, top);
		assertTrue(MicroGeom.faceCovered(bottom, Y, true, both));   // bottom slab's top, under the top slab
		assertTrue(MicroGeom.faceCovered(top, Y, false, both));
		assertFalse(MicroGeom.faceCovered(bottom, Y, false, both)); // on the cell boundary: left to culling
		assertFalse(MicroGeom.faceCovered(bottom, Y, true, List.of(bottom, corner))); // only partly covered
		assertTrue(MicroGeom.faceCovered(corner, Y, false, List.of(bottom, corner)));
	}

	@Test
	void packRoundTripsAndValidates() {
		int[] b = { 1, 2, 3, 8, 7, 4 };
		assertArrayEquals(b, MicroGeom.unpack(MicroGeom.pack(b)));
		assertTrue(MicroGeom.valid(b));
		assertFalse(MicroGeom.valid(new int[] { 0, 0, 0, 9, 1, 1 }));
		assertFalse(MicroGeom.valid(new int[] { 2, 0, 0, 2, 1, 1 }));
	}

	@Test
	void shapeOfMatchesWhatSnapMakes() {
		for (Shape s : Shape.values()) {
			for (int size : new int[] { 1, 2, 4 }) {
				for (int axis = 0; axis < 3; axis++) {
					int[] box = MicroGeom.snap(s, size, axis, true, new double[] { 0.5, 0.5, 0.5 }).box();
					Object[] got = MicroGeom.shapeOf(box);
					assertEquals(s, got[0], s + " " + size + " axis " + axis);
					assertEquals(size, got[1]);
				}
			}
		}
		assertNull(MicroGeom.shapeOf(new int[] { 0, 0, 0, 3, 8, 8 }));
	}

	@Test
	void sawKeepsVolume() {
		int slab = 8 * 8 * 4;
		assertArrayEquals(new int[] { Shape.FACE.ordinal(), 4, 2 }, MicroGeom.saw(Shape.FACE, 8, false));
		assertArrayEquals(new int[] { Shape.FACE.ordinal(), 2, 2 }, MicroGeom.saw(Shape.FACE, 4, false));
		assertArrayEquals(new int[] { Shape.FACE.ordinal(), 1, 2 }, MicroGeom.saw(Shape.FACE, 2, false));
		assertNull(MicroGeom.saw(Shape.FACE, 1, false));
		int[] posts = MicroGeom.saw(Shape.FACE, 4, true);
		assertEquals(slab, posts[2] * 4 * 4 * 8);
		int[] corners = MicroGeom.saw(Shape.POST, 2, true);
		assertEquals(2 * 2 * 8, corners[2] * 2 * 2 * 2);
		assertNull(MicroGeom.saw(Shape.CORNER, 2, true));
	}
}
