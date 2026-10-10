package dev.gmodcraft.world;

import static dev.gmodcraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** v42 kEvPistonMove: which cells a move reports, and the event bytes. */
class PistonPushTest {
	@Test
	void extendReportsBlocksAndHead() {
		BlockPos piston = new BlockPos(8, 1, 4);
		PistonPush.Cell[] c = PistonPush.cells(piston, Direction.SOUTH, true, Direction.SOUTH, List.of(new BlockPos(8, 1, 5), new BlockPos(8, 1, 6)));
		assertEquals(3, c.length);
		int south = Direction.SOUTH.get3DDataValue();
		assertEquals(new PistonPush.Cell(8, 1, 5, south | PISTON_EXTENDING), c[0]);
		assertEquals(new PistonPush.Cell(8, 1, 6, south | PISTON_EXTENDING), c[1]);
		assertEquals(new PistonPush.Cell(8, 1, 4, south | PISTON_EXTENDING | PISTON_HEAD), c[2]); // the head starts in the base's cell
	}

	@Test
	void stickyPullMovesTowardsThePiston() {
		// a sticky piston facing south pulls the block two cells in front of it back north; no head cell
		PistonPush.Cell[] c = PistonPush.cells(new BlockPos(8, 1, 4), Direction.SOUTH, false, Direction.NORTH, List.of(new BlockPos(8, 1, 6)));
		assertEquals(1, c.length);
		assertEquals(new PistonPush.Cell(8, 1, 6, Direction.NORTH.get3DDataValue()), c[0]);
		assertEquals(0, c[0].flags() & (PISTON_EXTENDING | PISTON_HEAD));
	}

	@Test
	void extendWithNothingInFrontIsJustTheHead() {
		PistonPush.Cell[] c = PistonPush.cells(new BlockPos(0, 64, 0), Direction.UP, true, Direction.UP, List.of());
		assertEquals(1, c.length);
		assertEquals(Direction.UP.get3DDataValue() | PISTON_EXTENDING | PISTON_HEAD, c[0].flags());
	}

	@Test
	void directionsAreBridgeFaces() {
		// the flags' direction is a BridgeFace (Minecraft's Direction order)
		assertEquals(FACE_DOWN, Direction.DOWN.get3DDataValue());
		assertEquals(FACE_UP, Direction.UP.get3DDataValue());
		assertEquals(FACE_NORTH, Direction.NORTH.get3DDataValue());
		assertEquals(FACE_SOUTH, Direction.SOUTH.get3DDataValue());
		assertEquals(FACE_WEST, Direction.WEST.get3DDataValue());
		assertEquals(FACE_EAST, Direction.EAST.get3DDataValue());
		assertTrue(FACE_EAST <= PISTON_DIR_MASK && (PISTON_DIR_MASK & (PISTON_EXTENDING | PISTON_HEAD)) == 0);
	}

	@Test
	void eventBytes() {
		ByteBuffer slot = ByteBuffer.allocate((int) MC_EVENT_BYTES);
		for (int i = 0; i < slot.capacity(); i++) {
			slot.put(i, (byte) 0x5A); // a reused slot: everything not set must come out zero
		}
		int x = 2048 * 3 + 1000; // slot coordinates stay exact as floats
		PistonPush.encode(slot, new PistonPush.Cell(x, -60, -2048 * 5 - 17, FACE_WEST | PISTON_EXTENDING), (int) PISTON_TRAVEL_TICKS, 0x7EADBEEF, 42);
		ByteBuffer b = slot.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(EV_PISTON_MOVE, b.getInt((int) ME_TYPE));
		assertEquals(28, EV_PISTON_MOVE);
		assertEquals(0, b.getInt((int) ME_ENT_ID));
		assertEquals(0L, b.getLong((int) ME_STEAM_ID));
		assertEquals((float) x, b.getFloat((int) ME_A));
		assertEquals(x, (int) b.getFloat((int) ME_A));
		assertEquals(-60.0F, b.getFloat((int) ME_B));
		assertEquals(-2048 * 5 - 17, (int) b.getFloat((int) ME_C));
		assertEquals(0.0F, b.getFloat((int) ME_D));
		assertEquals(FACE_WEST | PISTON_EXTENDING, b.getInt((int) ME_FLAGS));
		assertEquals(2, b.getInt((int) ME_WEAPON));
		assertEquals(0x7EADBEEF, b.getInt((int) ME_REQUEST_ID));
		assertEquals(42, b.getInt((int) ME_RESULT));
	}

	@Test
	void moveIdsAreNonZero() {
		for (int i = 0; i < 5; i++) {
			assertTrue(PistonPush.nextMoveId() > 0);
		}
	}
}
