package dev.gmodcraft.world;

import dev.gmodcraft.mixin.VegetationBlockInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Map surfaces act like the blocks they're made of (roadmap 0.5, M1).
 * <ul>
 * <li>Virtual answers, no block writes: {@link #groundState} stands in for the AIR Minecraft reads
 * under snow layers, under a walking entity (friction) and under a spawn spot.</li>
 * <li>Conversion on interaction: {@link #convertForUse} turns the clicked surface cell into the
 * real block of its material, but only when the item in hand will then work on it (hoe on grass or
 * dirt, saplings / flowers / bushes on grass, sugar cane on sand by water).</li>
 * </ul>
 */
public final class SurfaceBlocks {
	private SurfaceBlocks() {
	}

	/**
	 * What Minecraft should see at {@code pos} where it read {@code original}: the block of the map
	 * ground there when the cell is AIR to Minecraft and the map has ground over it, else original.
	 * Any thread; reads only (cached per cell in the side's SkyCollision).
	 */
	public static BlockState groundState(@Nullable BlockGetter getter, BlockPos pos, BlockState original) {
		if (!original.isAir() || !(getter instanceof Level level)) {
			return original;
		}
		SkyCollision store = SkyCollision.of(level);
		if (!store.active()) {
			return original;
		}
		SkyGround.Ground g = store.ground.at(pos.getX(), pos.getY(), pos.getZ());
		if (g == null) {
			return original;
		}
		// Dug out of the map there (a hole, or a real block now): that cell's surface is gone.
		if (SkyDig.isDug(level, worldOf(level), new BlockPos(pos.getX(), g.surfaceY(), pos.getZ()))) {
			return original;
		}
		return SkyDig.materialState(g.material());
	}

	private static int worldOf(Level level) {
		return level.isClientSide() ? SkyDig.clientWorld : dev.gmodcraft.ServerHost.worldId();
	}

	/** Items converted for: hoes, and block items for plants that need the right ground under them. */
	static boolean convertsFor(ItemStack stack) {
		if (stack.is(ItemTags.HOES)) {
			return true;
		}
		return stack.getItem() instanceof BlockItem item && (item.getBlock() instanceof VegetationBlock || item.getBlock() instanceof SugarCaneBlock);
	}

	/**
	 * Server, before vanilla's useItemOn: a right click on the map's ground with a hoe or a plant
	 * turns the clicked surface cell into the real block of its material (dug out of the map like a
	 * mined cell, the same gates as digging), and the hit is moved onto that block's top so vanilla
	 * tills it or plants on it. Returns the hit to use (the original when nothing was converted).
	 */
	public static BlockHitResult convertForUse(ServerPlayer player, ServerLevel level, ItemStack stack, BlockHitResult hit) {
		if (hit.getDirection() != Direction.UP || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || stack.isEmpty() || !convertsFor(stack)) {
			return hit;
		}
		if (!dev.gmodcraft.ServerHost.linked() || !SkyDig.digs() || player.isSpectator() || !player.mayBuild()) {
			return hit;
		}
		if (!level.getBlockState(hit.getBlockPos()).isAir()) {
			return hit; // a real block was clicked: vanilla as is
		}
		Vec3 loc = hit.getLocation();
		BlockPos cell = SkyGround.surfaceCell(loc.x, loc.y, loc.z);
		if (!level.isLoaded(cell) || dev.gmodcraft.slot.SlotJobs.lockedAt(cell.getX(), cell.getZ())
			|| player.getEyePosition().distanceToSqr(Vec3.atCenterOf(cell)) > 8.0 * 8.0) {
			return hit;
		}
		// The exact triangle clicked: a short vertical ray through the hit point.
		SkyRay.Hit tri = SkyClip.cast(SkyCollision.SERVER, loc.add(0.0, 0.25, 0.0), loc.subtract(0.0, 0.25, 0.0));
		if (tri == null || tri.tri() == null || tri.tri().dynamic || !tri.tri().diggable || Math.abs(tri.tri().ny) < SkyGround.UP_NY) {
			return hit; // a prop, a building, or a wall: stays the map's
		}
		int world = dev.gmodcraft.ServerHost.worldId();
		if (SkyDig.isDug(level, world, cell)) {
			return hit;
		}
		BlockState here = level.getBlockState(cell);
		if (!here.isAir() && !here.canBeReplaced()) {
			return hit;
		}
		int material = tri.tri().material == dev.gmodcraft.link.Proto.DIG_NONE ? dev.gmodcraft.link.Proto.DIG_STONE : tri.tri().material;
		BlockState ground = SkyDig.materialState(material);
		if (!worksOn(stack, ground, level, cell)) {
			return hit; // wheat seeds on raw grass, a hoe on stone...: nothing to convert
		}
		SkyDig.digCell(level, world, cell, material);
		if (!SkyDig.isDug(level, world, cell) || level.getBlockState(cell).isAir()) {
			return hit; // refused (a re-anchor locked it meanwhile)
		}
		return new BlockHitResult(new Vec3(loc.x, cell.getY() + 1.0, loc.z), Direction.UP, cell, false);
	}

	/** Will the item work on {@code ground} placed at {@code cell} (checked before anything's written)? */
	static boolean worksOn(ItemStack stack, BlockState ground, ServerLevel level, BlockPos cell) {
		BlockPos above = cell.above();
		if (stack.is(ItemTags.HOES)) {
			return ground.is(BlockTags.TURNS_INTO_FARMLAND) && level.getBlockState(above).isAir();
		}
		if (!(stack.getItem() instanceof BlockItem item) || !level.getBlockState(above).canBeReplaced()) {
			return false;
		}
		Block block = item.getBlock();
		if (block instanceof SugarCaneBlock) {
			if (!ground.is(BlockTags.SUPPORTS_SUGAR_CANE)) {
				return false;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = cell.relative(d);
				if (level.getFluidState(n).is(FluidTags.SUPPORTS_SUGAR_CANE_ADJACENTLY) || level.getBlockState(n).is(BlockTags.SUPPORTS_SUGAR_CANE_ADJACENTLY)) {
					return true;
				}
			}
			return false;
		}
		return block instanceof VegetationBlock plant && ((VegetationBlockInvoker) plant).gmodcraft$mayPlaceOn(ground, level, cell);
	}
}
