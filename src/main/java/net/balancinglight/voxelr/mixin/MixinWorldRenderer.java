package net.balancinglight.voxelr.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.balancinglight.voxelr.VoxelRClient;
import net.balancinglight.voxelr.config.VoxelRConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mojang name: LevelRenderer (was WorldRenderer in Yarn).
 * All injects are require=0 for cross-version safety.
 *
 * MC 26.1.2 signatures (verified via javap against minecraft-merged.jar):
 *   blockChanged(BlockGetter, BlockPos, BlockState, BlockState, int)
 *   onChunkReadyToRender(ChunkPos)
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldRenderer {

    // MC 26.1.2: blockChanged(BlockGetter level, BlockPos pos, BlockState old, BlockState new, int flags)
    @Inject(
        method = "blockChanged",
        at = @At("HEAD"),
        require = 0
    )
    private void VoxelR_onBlockChanged(BlockGetter level,
                                          BlockPos pos,
                                          BlockState oldState, BlockState newState,
                                          int flags,
                                          CallbackInfo ci) {
        if (!VoxelRConfig.get().enabled) return;
        if (VoxelRClient.getVoxelWorld() == null) return;
        VoxelRClient.getVoxelWorld().onBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), newState);
    }

    // MC 26.1.2: onChunkReadyToRender(ChunkPos) — replaces onChunkLoaded/onChunkAdded
    @Inject(method = "onChunkReadyToRender", at = @At("RETURN"), require = 0)
    private void VoxelR_onChunkReadyToRender(ChunkPos chunkPos, CallbackInfo ci) {
        if (!VoxelRConfig.get().enabled) return;
        if (VoxelRClient.getVoxelWorld() == null) return;
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null) return;
        LevelChunk chunk = lvl.getChunk(chunkPos.x(), chunkPos.z());
        if (chunk == null) return;
        VoxelRClient.getVoxelWorld().loadChunk(chunk);
    }

    // Legacy fallbacks — silently skipped by require=0 if method not present in this MC version
    @Inject(method = "onChunkLoaded", at = @At("RETURN"), require = 0)
    private void VoxelR_onChunkLoaded(int chunkX, int chunkZ, CallbackInfo ci) {
        if (!VoxelRConfig.get().enabled) return;
        if (VoxelRClient.getVoxelWorld() == null) return;
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null) return;
        LevelChunk chunk = lvl.getChunk(chunkX, chunkZ);
        if (chunk == null) return;
        VoxelRClient.getVoxelWorld().loadChunk(chunk);
    }

    @Inject(method = "onChunkAdded", at = @At("RETURN"), require = 0)
    private void VoxelR_onChunkAdded(int chunkX, int chunkZ, CallbackInfo ci) {
        if (!VoxelRConfig.get().enabled) return;
        if (VoxelRClient.getVoxelWorld() == null) return;
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null) return;
        LevelChunk chunk = lvl.getChunk(chunkX, chunkZ);
        if (chunk == null) return;
        VoxelRClient.getVoxelWorld().loadChunk(chunk);
    }

    @Inject(method = "onChunkUnloaded", at = @At("HEAD"), require = 0)
    private void VoxelR_onChunkUnloaded(int chunkX, int chunkZ, CallbackInfo ci) {
        if (VoxelRClient.getVoxelWorld() == null) return;
        // Don't unload the node — only clean up the request tracker.
        // Actual node unloads happen via unloadOutOfRangeChunks in ChunkRequestManager.
        VoxelRClient.getVoxelWorld().getChunkRequestManager().onChunkUnloaded(chunkX, chunkZ);
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"), require = 0)
    private void VoxelR_onChunkRemoved(int chunkX, int chunkZ, CallbackInfo ci) {
        if (VoxelRClient.getVoxelWorld() == null) return;
        VoxelRClient.getVoxelWorld().getChunkRequestManager().onChunkUnloaded(chunkX, chunkZ);
    }
}
