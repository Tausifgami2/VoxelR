package net.balancinglight.voxelr.api.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public render extension API. Allows third-party mods to hook into
 * VoxelR's render passes without touching internal implementation.
 */
public final class RenderAPI {

    private final List<CustomRenderPass> customPasses = new ArrayList<>();
    private final List<BlockShaderOverride> shaderOverrides = new ArrayList<>();

    public RenderAPI() {}

    /**
     * Register a custom render pass that executes after VoxelR's own passes.
     * Use this for particles, entities, or effects that must composite on top
     * of the voxel world.
     *
     * @param pass the pass to register
     */
    public void registerCustomPass(CustomRenderPass pass) {
        if (pass == null) throw new NullPointerException("pass must not be null");
        customPasses.add(pass);
    }

    public void unregisterCustomPass(CustomRenderPass pass) {
        customPasses.remove(pass);
    }

    /**
     * Override how a specific block ID is rendered in the voxel SSBO.
     * Use for emissive blocks, CTM, animated textures, etc.
     *
     * @param blockId    vanilla block ID (0–32767)
     * @param override   the shader override descriptor
     */
    public void registerShaderOverride(int blockId, BlockShaderOverride override) {
        if (blockId < 0 || blockId > 32767) {
            throw new IllegalArgumentException("blockId must be in [0, 32767], got " + blockId);
        }
        shaderOverrides.add(override);
    }

    /** Returns an unmodifiable view of all registered custom passes. */
    public List<CustomRenderPass> getCustomPasses() {
        return Collections.unmodifiableList(customPasses);
    }

    /** Returns an unmodifiable view of all block shader overrides. */
    public List<BlockShaderOverride> getShaderOverrides() {
        return Collections.unmodifiableList(shaderOverrides);
    }
}
