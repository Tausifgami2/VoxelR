package net.balancinglight.voxelr.api.render;

/**
 * A custom render pass that executes after VoxelR's built-in passes.
 */
@FunctionalInterface
public interface CustomRenderPass {
    /**
     * Execute this render pass.
     *
     * @param partialTick interpolation factor for the current frame
     */
    void execute(float partialTick);
}
