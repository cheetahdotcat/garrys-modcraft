package dev.gmodcraft.micro;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.micro.MicroGeom.Shape;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The {@code gmodcraft:microblock} item component: what a microblock item places. */
public record MicroSpec(BlockState material, Shape shape, int size) {
	private static final Codec<Shape> SHAPE_CODEC = Codec.STRING.comapFlatMap(
		s -> {
			Shape shape = Shape.byId(s);
			return shape != null ? DataResult.success(shape) : DataResult.error(() -> "unknown microblock shape " + s);
		}, Shape::id);
	private static final Codec<Integer> SIZE_CODEC = Codec.INT.validate(
		s -> MicroGeom.validSize(s) ? DataResult.success(s) : DataResult.error(() -> "microblock size must be 1, 2 or 4: " + s));

	public static final Codec<MicroSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockState.CODEC.fieldOf("material").forGetter(MicroSpec::material),
		SHAPE_CODEC.fieldOf("shape").forGetter(MicroSpec::shape),
		SIZE_CODEC.fieldOf("size").forGetter(MicroSpec::size)
	).apply(i, MicroSpec::new));

	public static final StreamCodec<ByteBuf, MicroSpec> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), MicroSpec::material,
		ByteBufCodecs.VAR_INT.map(i -> Shape.values()[Math.floorMod(i, Shape.values().length)], Shape::ordinal), MicroSpec::shape,
		ByteBufCodecs.VAR_INT.map(i -> MicroGeom.validSize(i) ? i : 1, i -> i), MicroSpec::size,
		MicroSpec::new);

	/** The spec a placed part came from, or null for a box none of ours makes. */
	public static MicroSpec of(MicroPart part) {
		Object[] s = MicroGeom.shapeOf(part.boxArray());
		return s == null ? null : new MicroSpec(part.material(), (Shape) s[0], (Integer) s[1]);
	}
}
