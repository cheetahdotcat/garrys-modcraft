package dev.gmodcraft.micro;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.micro.MicroGeom.Shape;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Microblocks (0.5): the block, its block entity, the part item and its component, the hand saw and its recipe. */
public final class Microblocks {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GmodCraft.MOD_ID, path);
	}

	public static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, id("microblock"));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, id("microblock"));
	public static final ResourceKey<Item> SAW_KEY = ResourceKey.create(Registries.ITEM, id("hand_saw"));

	public static final MicroblockBlock BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
		new MicroblockBlock(BlockBehaviour.Properties.of()
			.setId(BLOCK_KEY)
			.mapColor(MapColor.STONE)
			.strength(1.0F, 3.0F)
			.sound(SoundType.STONE)
			.noOcclusion()
			.dynamicShape()
			.noLootTable() // drops: MicroblockBlock.getDrops (the parts)
			.pushReaction(PushReaction.POPPED) // pistons break it and drop the parts
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)));
	public static final BlockEntityType<MicroblockEntity> BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		id("microblock"), new BlockEntityType<>(MicroblockEntity::new, Set.of(BLOCK)));
	public static final DataComponentType<MicroSpec> SPEC = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("microblock"),
		DataComponentType.<MicroSpec>builder().persistent(MicroSpec.CODEC).networkSynchronized(MicroSpec.STREAM_CODEC).build());
	public static final MicroblockItem ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
		new MicroblockItem(new Item.Properties().setId(ITEM_KEY)));
	public static final Item SAW = Registry.register(BuiltInRegistries.ITEM, SAW_KEY,
		new Item(new Item.Properties().setId(SAW_KEY).stacksTo(1)));

	private static final ResourceKey<CreativeModeTab> BUILDING_TAB =
		ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("building_blocks"));
	private static final ResourceKey<CreativeModeTab> TOOLS_TAB =
		ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));

	private Microblocks() {
	}

	public static ItemStack stack(MicroSpec spec, int count) {
		ItemStack s = new ItemStack(ITEM, count);
		s.set(SPEC, spec);
		return s;
	}

	public static void init() {
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id("microblock_saw"), MicroSawRecipe.SERIALIZER);
		CreativeModeTabEvents.modifyOutputEvent(TOOLS_TAB).register(output -> output.accept(SAW));
		CreativeModeTabEvents.modifyOutputEvent(BUILDING_TAB).register(output -> {
			for (Block material : List.of(Blocks.STONE, Blocks.OAK_PLANKS, Blocks.GLASS)) {
				BlockState m = material.defaultBlockState();
				for (int size : new int[] { 1, 2, 4 }) {
					output.accept(stack(new MicroSpec(m, Shape.FACE, size), 1));
				}
				output.accept(stack(new MicroSpec(m, Shape.POST, 2), 1));
				output.accept(stack(new MicroSpec(m, Shape.CORNER, 2), 1));
			}
		});
		PlayerBlockBreakEvents.BEFORE.register(Microblocks::beforeBreak);
	}

	/**
	 * Mining a microblock with more than one part removes (and drops) only the part under the
	 * crosshair; the block stays. The last part breaks the block as usual (its drop: {@link MicroblockBlock#getDrops}).
	 */
	static boolean beforeBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!state.is(BLOCK) || !(level instanceof ServerLevel) || !(blockEntity instanceof MicroblockEntity be) || be.parts().size() <= 1) {
			return true;
		}
		int i = MicroblockBlock.pickPart(be, pos, player);
		if (i < 0) {
			i = be.parts().size() - 1;
		}
		MicroPart gone = be.remove(i);
		level.levelEvent(null, net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(gone.material()));
		MicroSpec spec = MicroSpec.of(gone);
		if (!player.isCreative() && spec != null) {
			Block.popResource(level, pos, stack(spec, 1));
		}
		return false;
	}
}
