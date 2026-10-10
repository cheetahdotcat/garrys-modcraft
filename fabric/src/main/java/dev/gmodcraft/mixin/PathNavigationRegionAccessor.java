package dev.gmodcraft.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Level a path-finding region was cut from, so SkyCollision.of(BlockGetter) can tell its side. */
@Mixin(PathNavigationRegion.class)
public interface PathNavigationRegionAccessor {
	@Accessor("level")
	Level gmodcraft$level();
}
