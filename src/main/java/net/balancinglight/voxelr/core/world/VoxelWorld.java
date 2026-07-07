package net.balancinglight.voxelr.core.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.config.VoxelRConfig;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//
public final class VoxelWorld {

    private final SparseVoxelGrid grid = new SparseVoxelGrid();

    private final Queue<BlockDiff> pendingDiffs = new ArrayDeque<>(16384);


    private final ExecutorService chunkLoadExecutor;
    private final ChunkRequestManager chunkRequestManager;
    private final Set<Long> loadingChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> waterColumns = ConcurrentHashMap.newKeySet();

    public VoxelWorld() {
        int threads = VoxelRConfig.get().diffWorkerThreads;
        if (threads <= 0) threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        this.chunkLoadExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "VoxelR-chunk-load");
            t.setDaemon(true);
            return t;
        });

        this.chunkRequestManager = new ChunkRequestManager(this);
        VoxelRMod.LOGGER.debug("[VoxelR] VoxelWorld init — {} chunk-load thread(s).", threads);
    }

    private static final int MAX_PENDING_DIFFS = 262144;

    public void onBlockUpdate(int worldX, int worldY, int worldZ,
                               net.minecraft.world.level.block.state.BlockState newState) {
        short id = !newState.isAir() ? BlockStateToId.toId(newState) : 0;
        synchronized (pendingDiffs) {
            if (pendingDiffs.size() >= MAX_PENDING_DIFFS) {
                pendingDiffs.poll(); // drop oldest to prevent OOM
            }
            pendingDiffs.add(new BlockDiff(worldX, worldY, worldZ, id));
        }
    }

    public int flushPendingDiffs() {
        int budget  = VoxelRConfig.get().maxDiffWritesPerFrame;
        int applied = 0;
        synchronized (pendingDiffs) {
            while (!pendingDiffs.isEmpty() && applied < budget) {
                BlockDiff d = pendingDiffs.poll();
                grid.setBlock(d.x, d.y, d.z, d.blockId);
                applied++;
            }
        }
        return applied;
    }

    public void loadChunk(LevelChunk chunk) {
        if (VoxelRConfig.get().freezeChunkLoading) return;
        ChunkPos pos = chunk.getPos();
        long key = ChunkPos.pack((int)pos.x(), (int)pos.z());
        if (!loadingChunks.add(key)) return;
        if (hasChunkLoaded((int)pos.x(), (int)pos.z())) { loadingChunks.remove(key); return; }
        chunkLoadExecutor.submit(() -> {
            try { ingestChunk(chunk, pos); }
            catch (Exception e) { VoxelRMod.LOGGER.error("[VoxelR] Error ingesting chunk {}", pos, e); }
            finally { loadingChunks.remove(key); }
        });
    }

    public void loadChunkFromServer(LevelChunk chunk) {
        if (VoxelRConfig.get().freezeChunkLoading) return;
        ChunkPos pos = chunk.getPos();
        long key = ChunkPos.pack((int)pos.x(), (int)pos.z());
        if (!loadingChunks.add(key)) return;
        if (hasChunkLoaded((int)pos.x(), (int)pos.z())) { loadingChunks.remove(key); return; }
        chunkLoadExecutor.submit(() -> {
            try {
                ingestChunk(chunk, pos);
                VoxelRMod.LOGGER.debug("[VoxelR] Server-loaded chunk ingested: {}", pos);
            } catch (Exception e) {
                VoxelRMod.LOGGER.error("[VoxelR] Error ingesting server chunk {}", pos, e);
            } finally { loadingChunks.remove(key); }
        });
    }

    void ingestChunk(LevelChunk chunk, ChunkPos pos) {
        long colKey = ChunkPos.pack((int)pos.x(), (int)pos.z());
        if (!loadingChunks.add(colKey)) return;
        try {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;

        int minSec = clientLevel.dimensionType().minY() >> 4;
        int maxSec = (clientLevel.dimensionType().minY() + clientLevel.dimensionType().height()) >> 4;

        int chunkBaseX = (int)pos.x() * 16;
        int chunkBaseZ = (int)pos.z() * 16;

        for (int cy = minSec; cy < maxSec; cy++) {
            var section = chunk.getSection(chunk.getSectionIndexFromSectionY(cy));
            if (section == null || section.hasOnlyAir()) continue;

            ChunkNode node = grid.getNode((int)pos.x(), cy, (int)pos.z(), true);

            boolean sectionHasWater = false;
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        var state = section.getBlockState(lx, ly, lz);
                        if (!state.isAir()) {
                            short id = BlockStateToId.toId(state);
                            node.setBlock(lx, ly, lz, id);
                            if (!sectionHasWater) {
                                var block = state.getBlock();
                                sectionHasWater = (block == Blocks.WATER);
                            }
                        }
                    }
                }
            }
            if (sectionHasWater) {
                waterColumns.add(ChunkPos.pack((int)pos.x(), (int)pos.z()));
            }

            // Compact uniform sections to save memory and enable fast-path skips
            node.compactUniform();

            // Compute biome colors for this section using the section center position
            try {
                var biomePos = new net.minecraft.core.BlockPos(chunkBaseX + 8, cy * 16 + 8, chunkBaseZ + 8);
                var biome = clientLevel.getBiome(biomePos).value();
                node.setBiomeColors(
                    biome.getFoliageColor(),
                    biome.getGrassColor(chunkBaseX + 8.0, chunkBaseZ + 8.0),
                    biome.getWaterColor()
                );
            } catch (Exception e) {
                // Keep defaults if biome lookup fails
            }

            node.markDirty();
            grid.notifyNodeModified();
        }
        } finally { loadingChunks.remove(colKey); }
    }

    public void unloadChunk(ChunkPos pos) {
        if (VoxelRConfig.get().freezeChunkLoading) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        waterColumns.remove(ChunkPos.pack((int)pos.x(), (int)pos.z()));
        int minY = level.dimensionType().minY() >> 4;
        int maxY = (level.dimensionType().minY() + level.dimensionType().height()) >> 4;
        for (int cy = minY; cy < maxY; cy++) {
            grid.removeNode((int)pos.x(), cy, (int)pos.z());
        }
        chunkRequestManager.onChunkUnloaded((int)pos.x(), (int)pos.z());
    }

    public void onWorldUnload() {
        synchronized (pendingDiffs) { pendingDiffs.clear(); }
        grid.clear();
        chunkRequestManager.reset();
        loadingChunks.clear();
        waterColumns.clear();
        VoxelRMod.LOGGER.info("[VoxelR] World unloaded — grid cleared.");
    }

    public void shutdown() {
        chunkLoadExecutor.shutdownNow();
        chunkRequestManager.shutdown();
        VoxelRMod.LOGGER.info("[VoxelR] VoxelWorld shut down.");
    }

    public boolean hasChunkLoaded(int cx, int cz) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        int minY = level.dimensionType().minY() >> 4;
        int maxY = (level.dimensionType().minY() + level.dimensionType().height()) >> 4;
        for (int cy = minY; cy < maxY; cy++) {
            if (grid.getNode(cx, cy, cz) != null) return true;
        }
        return false;
    }

    public ChunkRequestManager getChunkRequestManager() { return chunkRequestManager; }
    public SparseVoxelGrid     getGrid()                { return grid; }
    public int  getPendingDiffCount() {
        synchronized (pendingDiffs) { return pendingDiffs.size(); }
    }

    public boolean hasWaterColumn(int cx, int cz) {
        return waterColumns.contains(ChunkPos.pack(cx, cz));
    }

    // Water check
    public boolean hasWaterInView(int originCx, int originCz, int sizeX, int sizeZ) {
        for (long packed : waterColumns) {
            int cx = (int)(packed >> 32);
            int cz = (int)(packed);
            int dx = cx - originCx;
            int dz = cz - originCz;
            if (dx >= 0 && dx < sizeX && dz >= 0 && dz < sizeZ) return true;
        }
        return false;
    }

    private record BlockDiff(int x, int y, int z, short blockId) {}
}
