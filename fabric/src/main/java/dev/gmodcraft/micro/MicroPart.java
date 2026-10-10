package dev.gmodcraft.micro;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** One piece of a microblock: a material and a box in eighths ({@link MicroGeom}, packed). Immutable. */
public record MicroPart(BlockState material, int box) {
	private static final Codec<Integer> BOX_CODEC = Codec.INT.listOf().comapFlatMap(
		list -> {
			if (list.size() != 6) {
				return DataResult.error(() -> "microblock box needs 6 values, got " + list.size());
			}
			int[] b = list.stream().mapToInt(Integer::intValue).toArray();
			return MicroGeom.valid(b) ? DataResult.success(MicroGeom.pack(b)) : DataResult.error(() -> "bad microblock box " + list);
		},
		packed -> java.util.Arrays.stream(MicroGeom.unpack(packed)).boxed().toList());

	public static final Codec<MicroPart> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockState.CODEC.fieldOf("material").forGetter(MicroPart::material),
		BOX_CODEC.fieldOf("box").forGetter(MicroPart::box)
	).apply(i, MicroPart::new));
	public static final Codec<List<MicroPart>> LIST_CODEC = CODEC.listOf();

	public int[] boxArray() {
		return MicroGeom.unpack(this.box);
	}

	/** The box in block units (0..1). */
	public AABB aabb() {
		int[] b = boxArray();
		return new AABB(b[0] / 8.0, b[1] / 8.0, b[2] / 8.0, b[3] / 8.0, b[4] / 8.0, b[5] / 8.0);
	}
}
