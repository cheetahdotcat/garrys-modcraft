package dev.gmodcraft.world;

import static dev.gmodcraft.link.Proto.*;

import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.wire.Bridges;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * v42 kEvPistonMove: a piston starting to move blocks tells the host which cells move where, so GMod
 * pushes its props, NPCs and GMod-mode players out of the way and carries the ones standing on a
 * moving block (Minecraft moves its own entities, Minecraft-mode players included). Called from
 * PistonBaseMixin when moveBlocks' structure resolved, before any block moved: one event per moving
 * cell (the pushed or pulled blocks, plus the head when extending), all of one move published
 * together. Server thread only. Only inside the current map's slot, at most kPistonEventsPerTick per
 * tick (a flying machine can't flood the event ring).
 */
public final class PistonPush {
	private static long tickOf = Long.MIN_VALUE;
	private static int sentThisTick;
	private static int moveIds;
	private static long sentTotal, droppedTotal;

	private PistonPush() {
	}

	/** A moving cell: where it starts (it ends one block along the move direction) and its kEvPistonMove flags. */
	public record Cell(int x, int y, int z, int flags) {
	}

	/**
	 * The cells of one move (pure: tests run without a game). {@code toPush}: the resolver's blocks
	 * (positions before the move), {@code moveDir}: the resolver's push direction (the facing when
	 * extending, its opposite when a sticky piston pulls), {@code piston}/{@code facing}: the base and
	 * where it faces. Extending adds the head, which starts in the base's cell.
	 */
	public static Cell[] cells(BlockPos piston, Direction facing, boolean extending, Direction moveDir, List<BlockPos> toPush) {
		int base = (moveDir.get3DDataValue() & PISTON_DIR_MASK) | (extending ? PISTON_EXTENDING : 0);
		Cell[] out = new Cell[toPush.size() + (extending ? 1 : 0)];
		int n = 0;
		for (BlockPos p : toPush) {
			out[n++] = new Cell(p.getX(), p.getY(), p.getZ(), base);
		}
		if (extending) {
			out[n] = new Cell(piston.getX(), piston.getY(), piston.getZ(), (facing.get3DDataValue() & PISTON_DIR_MASK) | PISTON_EXTENDING | PISTON_HEAD);
		}
		return out;
	}

	/** One kEvPistonMove McEvent (kMcEventBytes, little-endian) into {@code slot} from its position 0. */
	public static void encode(ByteBuffer slot, Cell c, int travelTicks, int worldId, int moveId) {
		ByteBuffer b = slot.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < MC_EVENT_BYTES; i++) {
			b.put(i, (byte) 0);
		}
		b.putInt((int) ME_TYPE, EV_PISTON_MOVE);
		b.putFloat((int) ME_A, c.x());
		b.putFloat((int) ME_B, c.y());
		b.putFloat((int) ME_C, c.z());
		b.putInt((int) ME_FLAGS, c.flags());
		b.putInt((int) ME_WEAPON, travelTicks);
		b.putInt((int) ME_REQUEST_ID, worldId);
		b.putInt((int) ME_RESULT, moveId);
	}

	/** The next move id: non-zero, wraps. */
	static int nextMoveId() {
		moveIds = moveIds == Integer.MAX_VALUE ? 1 : moveIds + 1;
		return moveIds;
	}

	/** From PistonBaseMixin, moveBlocks with a resolved structure (any side; only the server's overworld counts). */
	public static void moved(Level level, BlockPos piston, Direction facing, boolean extending, Direction moveDir, List<BlockPos> toPush) {
		if (!(level instanceof ServerLevel) || !ServerHost.linked() || !Bridges.inSlot(level, piston)) {
			return;
		}
		long tick = level.getGameTime();
		if (tick != tickOf) {
			tickOf = tick;
			sentThisTick = 0;
		}
		Cell[] cells = cells(piston, facing, extending, moveDir, toPush);
		if (cells.length == 0) {
			return; // a sticky pull with nothing (air) in front: nothing moves
		}
		if (sentThisTick + cells.length > PISTON_EVENTS_PER_TICK) {
			droppedTotal++;
			return;
		}
		if (ServerLink.INSTANCE.pushPistonMove(cells, (int) PISTON_TRAVEL_TICKS, ServerHost.worldId(), nextMoveId())) {
			sentThisTick += cells.length;
			sentTotal++;
		} else {
			droppedTotal++;
		}
	}

	/** Moves sent / dropped (ring full or the per-tick cap), for the debug output. */
	public static String stats() {
		return "piston moves sent " + sentTotal + ", dropped " + droppedTotal;
	}
}
