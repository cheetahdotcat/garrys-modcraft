package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** v42: a moving piston cell goes to GMod with the moved block's final shape, not the moving one. */
class PistonCellShapeTest {
	private static final BlockPos POS = new BlockPos(8, 1, 6);

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** One cell holding a moving piston block (its block entity at {@code progress}), air elsewhere. */
	private static BlockGetter cell(BlockState moved, boolean source, float progress) throws Exception {
		BlockState mp = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, Direction.SOUTH);
		PistonMovingBlockEntity be = new PistonMovingBlockEntity(POS, mp, moved, Direction.SOUTH, true, source);
		for (String name : new String[] { "progress", "progressO" }) {
			Field f = PistonMovingBlockEntity.class.getDeclaredField(name);
			f.setAccessible(true);
			f.setFloat(be, progress);
		}
		return new BlockGetter() {
			@Override
			public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
				return pos.equals(POS) ? be : null;
			}

			@Override
			public BlockState getBlockState(BlockPos pos) {
				return pos.equals(POS) ? mp : Blocks.AIR.defaultBlockState();
			}

			@Override
			public FluidState getFluidState(BlockPos pos) {
				return getBlockState(pos).getFluidState();
			}

			@Override
			public int getHeight() {
				return 384;
			}

			@Override
			public int getMinY() {
				return -64;
			}
		};
	}

	@Test
	void pushedBlockIsItsFullShapeAtOnce() throws Exception {
		BlockGetter g = cell(Blocks.OAK_PLANKS.defaultBlockState(), false, 0.5F);
		BlockState mp = g.getBlockState(POS);
		// what Minecraft's own shape gives half way: half a block (the old, tick-dependent delta)
		int moving = BlockDeltas.octants(mp.getCollisionShape(g, POS));
		assertNotEquals(0, moving, "the moving shape half way isn't a full cube");
		// what goes to GMod: the planks' full cube (octants 0 = a full solid cell)
		assertSame(Shapes.block(), BlockDeltas.collisionShape(g, g, mp, POS));
		assertEquals(0, BlockDeltas.octants(BlockDeltas.collisionShape(g, g, mp, POS)));
	}

	@Test
	void sameAtEveryProgress() throws Exception {
		for (float p : new float[] { 0.0F, 0.5F, 1.0F }) {
			BlockGetter g = cell(Blocks.STONE.defaultBlockState(), false, p);
			assertSame(Shapes.block(), BlockDeltas.collisionShape(g, g, g.getBlockState(POS), POS), "progress " + p);
		}
	}

	@Test
	void extendingHeadIsTheHeadShape() throws Exception {
		BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.SOUTH);
		BlockGetter g = cell(head, true, 0.5F);
		// the head's plate is on the south (+z) side: the four z-high octants (bit 4 dz)
		assertEquals(0xF0, BlockDeltas.octants(BlockDeltas.collisionShape(g, g, g.getBlockState(POS), POS)));
	}

	@Test
	void otherBlocksKeepTheirShape() throws Exception {
		BlockGetter g = cell(Blocks.STONE.defaultBlockState(), false, 0.5F);
		BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
		assertSame(slab.getCollisionShape(g, POS), BlockDeltas.collisionShape(g, g, slab, POS));
	}
}
