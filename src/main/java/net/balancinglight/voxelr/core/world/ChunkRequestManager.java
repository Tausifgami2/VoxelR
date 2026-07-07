package net.balancinglight.voxelr.core.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.config.VoxelRConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages chunk loading beyond vanilla's 32-chunk view-distance limit.
 *
 * MC 26.1.2 fixes:
 * - ChunkPos is a Java record. Fields x/z are private; use pos.x() / pos.z() (return int).
 * - ChunkPos.asLong(int,int) removed. Use ChunkPos.pack(int, int) instead.
 * - client.world renamed to client.level.
 * - client.getServer() → client.getSingleplayerServer().
 */
public final class ChunkRequestManager {

    private static final int REQUESTS_PER_TICK      = 128;
    private static final int REBUILD_INTERVAL_TICKS = 20;
    private static final double MEMORY_LOW_THRESHOLD = 0.15;
    // Minimum ticks to stay in shrink mode before restore is allowed (300 = ~15 sec)
    private static final int MEMORY_SHRINK_COOLDOWN = 300;
    private boolean   memShrunk = false;
    private int       memShrinkRd = 32;
    private int       memShrinkCooldown = 0;

    private final VoxelWorld world;
    private final ExecutorService loadExecutor;

    private final Queue<ChunkPos> requestQueue       = new ArrayDeque<>(4096);
    private final Set<Long>       submittedPositions = ConcurrentHashMap.newKeySet(4096);

    private int  lastPlayerCx = Integer.MIN_VALUE;
    private int  lastPlayerCz = Integer.MIN_VALUE;
    private int  lastRenderDistance = -1;
    private int  tickCounter  = 0;
    private boolean active    = false;

    public ChunkRequestManager(VoxelWorld world) {
        this.world = world;
        this.loadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 2),
            r -> {
                Thread t = new Thread(r, "VoxelR-chunk-req");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        );
    }

    public void tick() {
        Minecraft client = Minecraft.getInstance();
        // FIX: client.getServer() → client.getSingleplayerServer()
        MinecraftServer server = client.getSingleplayerServer();
        // FIX: client.world → client.level
        if (server == null || client.level == null || client.player == null) {
            active = false;
            return;
        }

        VoxelRConfig cfg = VoxelRConfig.get();
        if (!cfg.enabled) return;

        active = true;
        tickCounter++;

        int playerCx = (int) Math.floor(client.player.getX()) >> 4;
        int playerCz = (int) Math.floor(client.player.getZ()) >> 4;

        // ---- Memory-pressure adaptive render distance ----
        Runtime rt = Runtime.getRuntime();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        long maxMem  = rt.maxMemory();
        double heapUsed = (double) usedMem / maxMem;

        if (heapUsed > cfg.memoryShrinkThreshold && !memShrunk && cfg.memoryShrinkEnabled) {
            memShrunk = true;
            memShrinkCooldown = MEMORY_SHRINK_COOLDOWN;
            int baseRd = Math.max(cfg.renderDistance, client.options.getEffectiveRenderDistance());
            memShrinkRd = Math.max((int)(baseRd * cfg.memoryShrinkFactor), 16);
            VoxelRMod.LOGGER.warn("[VoxelR] Heap {}% — shrinking render distance to {} (cooldown {} ticks)",
                (int)(heapUsed * 100), memShrinkRd, MEMORY_SHRINK_COOLDOWN);
        } else if (memShrunk) {
            if (memShrinkCooldown > 0) {
                memShrinkCooldown--;
            } else if (heapUsed < cfg.memoryRestoreThreshold) {
                memShrunk = false;
                memShrinkCooldown = 0;
                VoxelRMod.LOGGER.info("[VoxelR] Heap {}% — restoring render distance",
                    (int)(heapUsed * 100));
            }
        }

        int effectiveRd = memShrunk
            ? memShrinkRd
            : Math.max(cfg.renderDistance, client.options.getEffectiveRenderDistance());

        // Unload chunks that fell out of range when render distance shrinks
        ServerLevel serverWorld = server.getLevel(client.level.dimension());
        if (effectiveRd < lastRenderDistance && cfg.chunkUnloadEnabled) {
            unloadOutOfRangeChunks(playerCx, playerCz, effectiveRd, serverWorld);
        }
        lastRenderDistance = effectiveRd;

        boolean playerMoved = playerCx != lastPlayerCx || playerCz != lastPlayerCz;
        if (playerMoved || tickCounter % REBUILD_INTERVAL_TICKS == 0) {
            rebuildQueue(playerCx, playerCz, effectiveRd);
            lastPlayerCx = playerCx;
            lastPlayerCz = playerCz;
        }

        // Also unload when player moves far
        if (playerMoved && cfg.chunkUnloadEnabled) {
            unloadOutOfRangeChunks(playerCx, playerCz, effectiveRd, serverWorld);
        }

        // Pause requests when heap is near full
        boolean memLow = heapUsed > (1.0 - MEMORY_LOW_THRESHOLD);
        if (memLow && !requestQueue.isEmpty()) {
            VoxelRMod.LOGGER.warn("[VoxelR] Heap at {}% — pausing chunk requests",
                (int)(heapUsed * 100));
        }

        int submitted = 0;
        while (!requestQueue.isEmpty() && submitted < REQUESTS_PER_TICK && !memLow) {
            ChunkPos pos = requestQueue.poll();
            if (pos == null) break;
            // FIX: pos.x / pos.z fields are private in record; use pos.x() / pos.z().
            // FIX: ChunkPos.asLong removed; use ChunkPos.pack(int, int).
            long key = ChunkPos.pack(pos.x(), pos.z());
            if (!submittedPositions.contains(key)) {
                submittedPositions.add(key);
                submitRequest(server, client.level, pos);
                submitted++;
            }
        }
    }

    private void rebuildQueue(int cx, int cz, int renderDistance) {
        requestQueue.clear();

        for (int ring = 0; ring <= renderDistance; ring++) {
            if (ring == 0) {
                ChunkPos pos = new ChunkPos(cx, cz);
                if (!world.hasChunkLoaded(pos.x(), pos.z())) requestQueue.add(pos);
                continue;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                addIfMissing(requestQueue, cx + dx, cz - ring, renderDistance, cx, cz);
                addIfMissing(requestQueue, cx + dx, cz + ring, renderDistance, cx, cz);
            }
            for (int dz = -ring + 1; dz <= ring - 1; dz++) {
                addIfMissing(requestQueue, cx - ring, cz + dz, renderDistance, cx, cz);
                addIfMissing(requestQueue, cx + ring, cz + dz, renderDistance, cx, cz);
            }
        }
    }

    private void addIfMissing(Queue<ChunkPos> queue, int cx, int cz,
                               int maxRd, int playerCx, int playerCz) {
        int dx = cx - playerCx, dz = cz - playerCz;
        if (dx * dx + dz * dz > maxRd * maxRd) return;
        if (world.hasChunkLoaded(cx, cz)) return;
        queue.add(new ChunkPos(cx, cz));
    }

    /**
     * Unload all loaded chunk columns that are now beyond the render distance.
     * Collects positions first to avoid concurrent modification of the grid.
     * Also releases the server's LevelChunk tickets so RAM can be reclaimed.
     */
    private void unloadOutOfRangeChunks(int cx, int cz, int maxRd, ServerLevel serverWorld) {
        int maxRdSq = maxRd * maxRd;
        Set<Long> seen = new HashSet<>();
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkNode node : world.getGrid().getAllNodes()) {
            long colKey = ChunkPos.pack(node.cx, node.cz);
            if (seen.add(colKey)) {
                int dx = node.cx - cx;
                int dz = node.cz - cz;
                if (dx * dx + dz * dz > maxRdSq) {
                    toUnload.add(new ChunkPos(node.cx, node.cz));
                }
            }
        }
        if (serverWorld != null) {
            for (ChunkPos pos : toUnload) {
                // Release the server's LevelChunk ticket so it can be garbage collected
                serverWorld.getChunkSource().removeTicketWithRadius(TicketType.UNKNOWN, pos, 0);
            }
        }
        for (ChunkPos pos : toUnload) {
            world.unloadChunk(pos);
        }
        if (!toUnload.isEmpty()) {
            VoxelRMod.LOGGER.info("[VoxelR] Unloaded {} chunk column(s) out of render distance",
                toUnload.size());
        }
    }

    private void submitRequest(MinecraftServer server, ClientLevel clientWorld, ChunkPos pos) {
        loadExecutor.submit(() -> {
            try {
                ServerLevel serverWorld = server.getLevel(clientWorld.dimension());
                if (serverWorld == null) return;

                // Load chunk — this adds an UNKNOWN ticket internally
                ChunkAccess chunk = serverWorld.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);

                if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk worldChunk) {
                    // Ingest synchronously (not via chunkLoadExecutor) so the LevelChunk
                    // is guaranteed alive during ingestion. Then release the ticket.
                    if (!world.hasChunkLoaded((int)pos.x(), (int)pos.z())) {
                        world.ingestChunk(worldChunk, pos);
                    }
                    VoxelRMod.LOGGER.debug("[VoxelR] ChunkReq loaded: ({},{})", pos.x(), pos.z());
                }

                // Release the UNKNOWN ticket: LevelChunk can now be unloaded.
                // VoxelR keeps its own copy of the block data in the SSBO.
                server.execute(() ->
                    serverWorld.getChunkSource().removeTicketWithRadius(TicketType.UNKNOWN, pos, 0));

            } catch (Exception e) {
                VoxelRMod.LOGGER.warn("[VoxelR] ChunkReq failed ({},{}): {}",
                    pos.x(), pos.z(), e.getMessage());
                submittedPositions.remove(ChunkPos.pack(pos.x(), pos.z()));
            }
        });
    }

    public void onChunkUnloaded(int cx, int cz) {
        submittedPositions.remove(ChunkPos.pack(cx, cz));
    }

    public void reset() {
        requestQueue.clear();
        submittedPositions.clear();
        lastPlayerCx = Integer.MIN_VALUE;
        lastPlayerCz = Integer.MIN_VALUE;
        lastRenderDistance = -1;
        tickCounter  = 0;
        active       = false;
    }

    public void shutdown() { loadExecutor.shutdownNow(); }

    public boolean isActive()          { return active; }
    public int     getQueueSize()      { return requestQueue.size(); }
    public int     getSubmittedCount() { return submittedPositions.size(); }
}
