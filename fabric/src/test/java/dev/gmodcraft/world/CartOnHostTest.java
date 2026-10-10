package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

/**
 * C2: a minecart on a rail laid on host ground at a non-aligned height (surface y 10.375, the rail in
 * cell 10) rides over that ground, and still stops at a host wall (SkyCollision.cartRidesOverHost, as
 * BlockCollisionsMixin applies it per cell).
 */
class CartOnHostTest {
	private static final double FLOOR = 0.375; // host voxels fill layers 0..2 of cell y 10
	private static final double FEET = 10.0625; // a cart on a flat rail in cell 10

	/** Per cell x = 0..3 (y 10, z 0): rails in 0..2, ground in 0..2, a full wall in 3. */
	private static List<VoxelShape> cells(boolean applyRule) {
		List<VoxelShape> out = new ArrayList<>();
		for (int x = 0; x <= 3; x++) {
			boolean rail = x <= 2;
			float top = x == 3 ? 1.0F : (float) FLOOR;
			VoxelShape host = Shapes.box(0, 0, 0, 1, top, 1).move(x, 10, 0);
			if (applyRule && SkyCollision.cartRidesOverHost(rail, top)) {
				continue; // the rail's own shape is empty
			}
			out.add(host);
		}
		return out;
	}

	private static AABB cart(double x) {
		return new AABB(x - 0.49, FEET, 0.01, x + 0.49, FEET + 0.7, 0.99);
	}

	@Test
	void rule() {
		assertTrue(SkyCollision.cartRidesOverHost(true, 0.375F), "ground in a rail's cell");
		assertTrue(SkyCollision.cartRidesOverHost(true, 0.875F), "sloped ground in a rail's cell");
		assertFalse(SkyCollision.cartRidesOverHost(true, 1.0F), "a wall through a rail's cell");
		assertFalse(SkyCollision.cartRidesOverHost(false, 0.375F), "ground without a rail");
	}

	@Test
	void withoutTheRuleTheCartIsHeld() {
		// the old behaviour (diagnosis): the cart is inside the ground's voxels and barely moves
		double moved = Shapes.collide(Direction.Axis.X, cart(0.5), cells(false), 0.4);
		assertTrue(moved < 0.2, "moved " + moved);
	}

	@Test
	void ridesOverGroundAndStopsAtTheWall() {
		assertEquals(0.4, Shapes.collide(Direction.Axis.X, cart(0.5), cells(true), 0.4), 1e-9);
		// from x 2.3 the wall face at x 3 is 0.21 ahead of the box (max x 2.79)
		assertEquals(0.21, Shapes.collide(Direction.Axis.X, cart(2.3), cells(true), 0.4), 1e-6);
		// gravity: it doesn't sink through the cell below (an ordinary floor there stays solid)
		List<VoxelShape> below = new ArrayList<>(cells(true));
		below.add(Shapes.block().move(0, 9, 0));
		assertEquals(0.0, Shapes.collide(Direction.Axis.Y, new AABB(0.01, 10.0, 0.01, 0.99, 10.7, 0.99), below, -0.1), 1e-9);
	}
}
