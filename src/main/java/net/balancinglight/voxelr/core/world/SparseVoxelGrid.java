package net.balancinglight.voxelr.core.world;

import net.balancinglight.voxelr.VoxelRMod;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-level sparse voxel grid — the central CPU-side world representation.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Level 1 — Chunk column lookup: (chunkX, chunkZ) → column of section nodes</li>
 *   <li>Level 2 — Section node: 16×16×16 block IDs + light data</li>
 * </ul>
 *
 * <p>Air columns and uniform-stone sections are stored as null pointers (no ChunkNode
 * allocated), saving both heap and GPU VRAM. Only non-uniform sections get a node.
 *
 * <p>Thread-safety: all node map operations use ConcurrentHashMap.
 * {@code allocatedNodeCount} is an {@link AtomicInteger} so concurrent chunk-load
 * threads don't corrupt the count.
 */
public final class SparseVoxelGrid {

    private final ConcurrentHashMap<Long, ChunkNode> nodes = new ConcurrentHashMap<>(8192);

    // Nodes removed from the grid that still have GPU allocations — drained by uploadDirtyNodes.
    private final Queue<ChunkNode> pendingFreeSlots = new ConcurrentLinkedQueue<>();

    // AtomicInteger — chunk loads happen on background threads, plain int was a data race.
    private final AtomicInteger allocatedNodeCount = new AtomicInteger(0);

    private final AtomicInteger dirtyNodeCount = new AtomicInteger(0);

    public SparseVoxelGrid() {}

    /**
     * Returns the ChunkNode for the given section coordinates,
     * allocating a new one if {@code createIfAbsent} is true.
     */
    public ChunkNode getNode(int cx, int cy, int cz, boolean createIfAbsent) {
        long key = pack(cx, cy, cz);
        if (createIfAbsent) {
            return nodes.computeIfAbsent(key, k -> {
                allocatedNodeCount.incrementAndGet();
                return new ChunkNode(cx, cy, cz);
            });
        }
        return nodes.get(key);
    }

    public ChunkNode getNode(int cx, int cy, int cz) {
        return nodes.get(pack(cx, cy, cz));
    }

    public void setBlock(int worldX, int worldY, int worldZ, short blockId) {
        int cx = worldX >> 4;
        int cy = worldY >> 4;
        int cz = worldZ >> 4;
        int lx = worldX & 15;
        int ly = worldY & 15;
        int lz = worldZ & 15;

        if (blockId == 0) {
            ChunkNode node = getNode(cx, cy, cz);
            if (node == null) return;
            node.setBlock(lx, ly, lz, (short) 0);
            dirtyNodeCount.incrementAndGet();
            if (isNodeNowUniformAir(node)) {
                node.markDirty();
            }
        } else {
            ChunkNode node = getNode(cx, cy, cz, true);
            node.setBlock(lx, ly, lz, blockId);
            node.markDirty();
            dirtyNodeCount.incrementAndGet();
        }
    }

    public short getBlock(int worldX, int worldY, int worldZ) {
        int cx = worldX >> 4;
        int cy = worldY >> 4;
        int cz = worldZ >> 4;
        ChunkNode node = getNode(cx, cy, cz);
        if (node == null) return 0;
        return node.getBlock(worldX & 15, worldY & 15, worldZ & 15);
    }

    public void removeNode(int cx, int cy, int cz) {
        long key = pack(cx, cy, cz);
        ChunkNode removed = nodes.remove(key);
        if (removed != null) {
            allocatedNodeCount.decrementAndGet();
            removed.freeBlockData();
            if (removed.isAllocatedOnGpu()) {
                pendingFreeSlots.add(removed);
            }
        }
    }

    public void notifyNodeModified() {
        dirtyNodeCount.incrementAndGet();
    }

    public void clear() {
        for (ChunkNode node : nodes.values()) {
            node.freeBlockData();
        }
        nodes.clear();
        pendingFreeSlots.clear();
        allocatedNodeCount.set(0);
        dirtyNodeCount.set(0);
        VoxelRMod.LOGGER.debug("[VoxelR] SparseVoxelGrid cleared.");
    }

    public ChunkNode[] drainPendingFreeSlots() {
        java.util.List<ChunkNode> list = new java.util.ArrayList<>();
        ChunkNode n;
        while ((n = pendingFreeSlots.poll()) != null) {
            list.add(n);
        }
        return list.toArray(new ChunkNode[0]);
    }

    public ChunkNode[] drainDirtyNodes() {
        if (dirtyNodeCount.get() == 0) {
            return new ChunkNode[0];
        }
        ChunkNode[] dirty = nodes.values().stream()
            .filter(ChunkNode::isDirty)
            .toArray(ChunkNode[]::new);
        for (ChunkNode n : dirty) n.clearDirty();
        dirtyNodeCount.set(0);
        return dirty;
    }

    public Collection<ChunkNode> getAllNodes() {
        return nodes.values();
    }

    public int getAllocatedNodeCount() { return allocatedNodeCount.get(); }

    public int getNodeCount() { return nodes.size(); }

    private static boolean isNodeNowUniformAir(ChunkNode node) {
        if (!node.isUniform()) {
            java.nio.ShortBuffer ids = node.getBlockDataAsShortBuffer();
            if (ids == null) return true;
            int len = ids.remaining();
            for (int i = 0; i < len; i++) {
                if (ids.get(i) != 0) return false;
            }
            return true;
        }
        return node.isAir();
    }

    static long pack(int cx, int cy, int cz) {
        return ((long)(cx & 0x3FFFFF) << 42)
             | ((long)(cy & 0xFFFFF)  << 22)
             |  (long)(cz & 0x3FFFFF);
    }
}
