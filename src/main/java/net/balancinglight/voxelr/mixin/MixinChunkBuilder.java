package net.balancinglight.voxelr.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No-op — chunk building always proceeds.
 *
 * VoxelR cannot generate chunks on its own; it reads vanilla's built chunk
 * data. Cancelling SectionRenderDispatcher.schedule() or rebuildSectionSync()
 * means no terrain exists for VoxelR to mirror, resulting in an invisible
 * world (850 FPS) when enabled from world start.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher")
public abstract class MixinChunkBuilder {

    @Inject(method = "schedule", at = @At("HEAD"), require = 0)
    private void VoxelR_noop(CallbackInfo ci) {
        // No-op: vanilla must build chunks first; VoxelR reads the results
    }

    @Inject(method = "rebuildSectionSync", at = @At("HEAD"), require = 0)
    private void VoxelR_noopSync(CallbackInfo ci) {
        // No-op: vanilla must build chunks first
    }
}
