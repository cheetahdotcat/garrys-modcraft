package dev.gmodcraft.client.micro;

import dev.gmodcraft.micro.MicroPart;
import dev.gmodcraft.micro.MicroblockEntity;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * GMod export of a microblock (WorldExporter.meshSection). Vanilla's block renderer there only takes
 * a model's static parts, so each material's quads go through it as a one-part model, rendered as the
 * material block: tint, ambient occlusion and face culling come out as for that block.
 */
public final class MicroblockExport {
	private MicroblockExport() {
	}

	/** One material's quads as a static model. */
	private record OnePart(BlockStateModelPart part) implements BlockStateModel {
		@Override
		public void collectParts(RandomSource random, List<BlockStateModelPart> out) {
			out.add(this.part);
		}

		@Override
		public Material.Baked particleMaterial() {
			return this.part.particleMaterial();
		}

		@Override
		public int materialFlags() {
			return this.part.materialFlags();
		}
	}

	public static void tesselate(ModelBlockRenderer renderer, BlockQuadOutput out, float x, float y, float z, ClientLevel level, BlockPos pos, long seed) {
		if (!(level.getBlockEntity(pos) instanceof MicroblockEntity be)) {
			return;
		}
		List<MicroPart> parts = be.parts();
		if (parts.isEmpty()) {
			return;
		}
		for (MicroQuads.Group g : MicroQuads.groups(parts)) {
			renderer.tesselateBlock(out, x, y, z, level, pos, g.material(), new OnePart(g.part()), seed);
		}
	}
}
