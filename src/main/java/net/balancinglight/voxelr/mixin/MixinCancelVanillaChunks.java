package net.balancinglight.voxelr.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.balancinglight.voxelr.config.VoxelRConfig;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class MixinCancelVanillaChunks {

    @Shadow @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true, require = 0)
    private void VoxelR_cancelPrepare(Matrix4fc matrix, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (VoxelRConfig.get().hideVanillaTerrain) {
            visibleSections.clear();
            nearbyVisibleSections.clear();
        }
    }
}
