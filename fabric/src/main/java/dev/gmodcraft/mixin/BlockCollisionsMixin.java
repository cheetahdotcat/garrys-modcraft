package dev.gmodcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.gmodcraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds Skyrim's geometry to every block-collision query. Vanilla movement, step-up, onGround
 * and fall-damage logic then run unchanged against it. A mirror block of the hull world type
 * (HullWorld) has no collision of its own: the host's geometry it stands for collides there (and a
 * dug hole next to it gets its wall as if it were air; where the host has none there, the hull fill
 * under terrain and in the map's solid, a full cube: SkyDig.mirrorWallShape).
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
	@WrapOperation(
		method = "computeNext",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
		)
	)
	private VoxelShape gmodcraft$addSkyrimShape(
		CollisionContext context, BlockState state, CollisionGetter level, BlockPos pos, Operation<VoxelShape> original
	) {
		boolean mirror = !state.isAir() && dev.gmodcraft.world.HullWorld.isMirror(level, pos);
		VoxelShape blockShape = mirror ? Shapes.empty() : original.call(context, state, level, pos);
		// The walls of holes dug into Skyrim's ground: solid for everyone.
		if (state.isAir() || mirror) {
			VoxelShape wall = mirror ? dev.gmodcraft.world.SkyDig.mirrorWallShape(level, pos) : dev.gmodcraft.world.SkyDig.wallShape(level, pos);
			if (wall != null) {
				blockShape = blockShape.isEmpty() ? wall : Shapes.or(blockShape, wall);
			}
		}
		if (context instanceof EntityCollisionContext entityContext && SkyCollision.usesSmoothCollider(entityContext.getEntity())) {
			return blockShape; // this entity collides with Skyrim's exact triangles instead (SkyCollider)
		}
		// The moving entity's side decides which store (client or server link); else the getter's.
		SkyCollision store = context instanceof EntityCollisionContext ec && ec.getEntity() != null ? SkyCollision.of(ec.getEntity().level()) : SkyCollision.of(level);
		VoxelShape sky = store.shapeAt(pos);
		if (sky == null) {
			return blockShape;
		}
		// C2: a rail laid on host ground shares its cell with that ground (a surface at y 10.3 puts the
		// rail in cell 10, its cart's box at 10.06..10.76): the cart rides over it as over a block top.
		if (context instanceof EntityCollisionContext cartContext && cartContext.getEntity() instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
			&& SkyCollision.cartRidesOverHost(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock, store.groundTop(pos))) {
			return blockShape;
		}
		return blockShape.isEmpty() ? sky : Shapes.or(blockShape, sky);
	}
}
