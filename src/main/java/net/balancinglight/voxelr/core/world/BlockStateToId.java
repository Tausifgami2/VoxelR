package net.balancinglight.voxelr.core.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps BlockStates to 16-bit block IDs for the voxel SSBO.
 *
 * Mojang API: Block.getId(state) for raw state ID (was Block.getRawIdFromState in Yarn).
 * Block.stateById(id) for reverse lookup (was Block.getStateFromRawId in Yarn).
 * state.getLightEmission() for luminance (was state.getLuminance() in Yarn).
 * state.canOcclude() for opacity (was state.isOpaque() in Yarn — note: different semantics).
 */
public final class BlockStateToId {

    private BlockStateToId() {}

    public static short toId(BlockState state) {
        if (state == null) return 0;
        if (state.isAir()) return 0;
        int raw = Block.getId(state);
        if (raw <= 0 || raw > 65535) return 0;
        return (short) raw;
    }

}
