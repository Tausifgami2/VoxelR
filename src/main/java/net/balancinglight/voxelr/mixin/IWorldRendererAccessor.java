package net.balancinglight.voxelr.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for LevelRenderer (Mojang name; was WorldRenderer in Yarn).
 * Exposes the private level field for chunk lookups.
 *
 * FIX: Added required @Mixin(LevelRenderer.class) — without it Mixin throws
 * InvalidMixinException: "missing @Mixin annotation" and the game crashes.
 */
@Mixin(LevelRenderer.class)
public interface IWorldRendererAccessor {

    @Accessor("level")
    ClientLevel VoxelR_getLevel();
}
