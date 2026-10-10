package dev.gmodcraft.micro;

import com.mojang.serialization.MapCodec;
import dev.gmodcraft.micro.MicroGeom.Shape;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The hand saw (ForgeMicroblocks style), two items in the grid, the saw stays:
 * <ul>
 * <li>saw above the piece: halve its thickness: full block → 2 slabs, slab → 2 panels, panel → 2 covers;</li>
 * <li>saw left of the piece: cut across: a face part → posts of its size (slab → 2, panel → 4, cover →
 * 8), a post → corners of its size (same counts). A full block cut across gives 2 slabs too.</li>
 * </ul>
 * Full blocks: any plain full cube that isn't a block entity (stone, planks, glass, wool, ...).
 */
public final class MicroSawRecipe extends CustomRecipe {
	public static final MicroSawRecipe INSTANCE = new MicroSawRecipe();
	public static final RecipeSerializer<MicroSawRecipe> SERIALIZER =
		new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

	private MicroSawRecipe() {
	}

	/** What the grid holds: the saw's index, and the result, or null. */
	private record Cut(int sawIndex, ItemStack result) {
	}

	private static Cut cut(CraftingInput input) {
		if (input.ingredientCount() != 2 || input.size() != 2) {
			return null; // two items, side by side or one above the other
		}
		List<ItemStack> items = input.items();
		int saw = items.get(0).is(Microblocks.SAW) ? 0 : -1;
		if (saw < 0) {
			return null; // the saw comes first: above (vertical) or left (horizontal)
		}
		boolean across = input.width() == 2;
		ItemStack piece = items.get(1);
		BlockState material;
		Shape shape;
		int size;
		MicroSpec spec = piece.get(Microblocks.SPEC);
		if (spec != null && piece.is(Microblocks.ITEM)) {
			material = spec.material();
			shape = spec.shape();
			size = spec.size();
		} else if (piece.getItem() instanceof BlockItem bi && cuttable(bi.getBlock())) {
			material = bi.getBlock().defaultBlockState();
			shape = Shape.FACE;
			size = MicroGeom.CELL;
		} else {
			return null;
		}
		int[] out = MicroGeom.saw(shape, size, across);
		if (out == null) {
			return null;
		}
		return new Cut(saw, Microblocks.stack(new MicroSpec(material, Shape.values()[out[0]], out[1]), out[2]));
	}

	/** A plain full cube: no block entity, a model, a full collision shape. */
	public static boolean cuttable(Block block) {
		if (block instanceof EntityBlock || block == Microblocks.BLOCK) {
			return false;
		}
		BlockState state = block.defaultBlockState();
		return state.getRenderShape() == RenderShape.MODEL && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		return cut(input) != null;
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		Cut c = cut(input);
		return c == null ? ItemStack.EMPTY : c.result();
	}

	@Override
	public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
		NonNullList<ItemStack> out = NonNullList.withSize(input.size(), ItemStack.EMPTY);
		Cut c = cut(input);
		if (c != null) {
			out.set(c.sawIndex(), input.getItem(c.sawIndex()).copyWithCount(1));
		}
		return out;
	}

	@Override
	public RecipeSerializer<MicroSawRecipe> getSerializer() {
		return SERIALIZER;
	}
}
