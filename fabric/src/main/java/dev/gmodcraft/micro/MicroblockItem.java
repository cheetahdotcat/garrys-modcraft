package dev.gmodcraft.micro;

import dev.gmodcraft.micro.MicroGeom.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * A microblock part (its {@link MicroSpec} component says material, shape and size). Using it on a
 * face places the part where {@link MicroGeom#snap} puts it: into the clicked microblock when there's
 * room, else into the cell in front of the face (a new microblock, or an existing one with room).
 */
public final class MicroblockItem extends Item {
	public MicroblockItem(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		MicroSpec spec = stack.get(Microblocks.SPEC);
		if (spec == null) {
			return super.getName(stack);
		}
		Component material = spec.material().getBlock().getName();
		return switch (spec.shape()) {
			case FACE -> Component.translatable("item.gmodcraft.microblock.face." + spec.size(), material);
			case POST -> Component.translatable("item.gmodcraft.microblock.post", material, spec.size());
			case CORNER -> Component.translatable("item.gmodcraft.microblock.corner", material, spec.size());
		};
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		ItemStack stack = ctx.getItemInHand();
		MicroSpec spec = stack.get(Microblocks.SPEC);
		if (spec == null) {
			return InteractionResult.FAIL;
		}
		Level level = ctx.getLevel();
		BlockPos clicked = ctx.getClickedPos();
		Direction face = ctx.getClickedFace();
		int axis = face.getAxis().ordinal();
		boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		Vec3 hit = ctx.getClickLocation();
		double[] local = {
			clamp01(hit.x - clicked.getX()), clamp01(hit.y - clicked.getY()), clamp01(hit.z - clicked.getZ())
		};
		Placement p = MicroGeom.snap(spec.shape(), spec.size(), axis, positive, local);
		BlockPos target = p.neighbour() ? clicked.relative(face) : clicked;
		int[] box = p.box();
		if (!p.neighbour() && !level.getBlockState(clicked).is(Microblocks.BLOCK)) {
			// inside some other block (a vanilla slab's top, ...): flush against the face in front instead
			local[axis] = positive ? 1.0 : 0.0;
			box = MicroGeom.snap(spec.shape(), spec.size(), axis, positive, local).box();
			target = clicked.relative(face);
		}
		BlockState there = level.getBlockState(target);
		boolean merge = there.is(Microblocks.BLOCK);
		MicroblockEntity existing = merge && level.getBlockEntity(target) instanceof MicroblockEntity be ? be : null;
		if (merge ? existing == null || !existing.fits(box)
			: !there.canBeReplaced(new BlockPlaceContext(ctx))) {
			return InteractionResult.FAIL;
		}
		MicroPart part = new MicroPart(spec.material(), MicroGeom.pack(box));
		if (!level.isUnobstructed(null, Shapes.create(part.aabb().move(target)))) {
			return InteractionResult.FAIL; // an entity stands there
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (existing == null) {
			if (!level.setBlock(target, Microblocks.BLOCK.defaultBlockState(), Block.UPDATE_ALL)
				|| !(level.getBlockEntity(target) instanceof MicroblockEntity created)) {
				return InteractionResult.FAIL;
			}
			existing = created;
		}
		existing.add(part);
		Player player = ctx.getPlayer();
		SoundType sound = spec.material().getSoundType();
		level.playSound(null, target, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
