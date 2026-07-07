package net.balancinglight.voxelr.api.block;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Allows mods to register custom block rendering behavior within VoxelR's pipeline.
 */
public final class BlockRegistry {

    private final Map<Integer, BlockRenderProfile> profiles = new HashMap<>();

    public BlockRegistry() {}

    /**
     * Register a rendering profile for a block ID.
     * If a profile already exists for this ID, it is replaced.
     *
     * @param blockId vanilla numeric block ID (0–32767)
     * @param profile the render profile to apply
     */
    public void registerProfile(int blockId, BlockRenderProfile profile) {
        if (blockId < 0 || blockId > 32767) {
            throw new IllegalArgumentException("blockId must be in [0, 32767], got " + blockId);
        }
        profiles.put(blockId, profile);
    }

    public Optional<BlockRenderProfile> getProfile(int blockId) {
        return Optional.ofNullable(profiles.get(blockId));
    }

    public boolean hasProfile(int blockId) {
        return profiles.containsKey(blockId);
    }

    public Map<Integer, BlockRenderProfile> getAllProfiles() {
        return Map.copyOf(profiles);
    }
}
