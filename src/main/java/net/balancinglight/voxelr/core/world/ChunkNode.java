package net.balancinglight.voxelr.core.world;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A single 16x16x16 chunk node in the sparse two-level voxel grid.
 *
 * <p>Each node represents one sub-chunk section. Block data is stored
 * off-heap (native memory) via LWJGL ByteBuffer to avoid GC pressure
 * from 260k+ short[4096] arrays on the Java heap.
 */
public final class ChunkNode {

public static final int BLOCKS_PER_NODE = 16 * 16 * 16;
public static final int BYTES_PER_NODE = BLOCKS_PER_NODE * 2;

    public final int cx, cy, cz;

    private volatile long ssboOffset = -1L;

    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private volatile boolean uniform = true;
    private volatile short uniformBlockId = 0;
    private volatile int solidBlockCount = 0;

    /** Off-heap block data (4096 shorts = 8192 bytes). Null if uniform. */
    private ByteBuffer blockData = null;

    private int foliageColor = 0xFF6AAF35;
    private int grassColor = 0xFF7CBD49;
    private int waterColor = 0xFF2E6EB5;

    public ChunkNode(int cx, int cy, int cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    public void setBlock(int lx, int ly, int lz, short blockId) {
        if (uniform && blockId == uniformBlockId) return;

        ensureBlockArray();
        int idx = localIndex(lx, ly, lz);
        short old = blockData.getShort(idx * 2);
        if (old == blockId) return;

        blockData.putShort(idx * 2, blockId);
        dirty.set(true);

        if (old == 0 && blockId != 0) solidBlockCount++;
        else if (old != 0 && blockId == 0) solidBlockCount--;

        if (uniform) {
            uniform = false;
        }

        if (solidBlockCount == 0) {
            uniform = true;
            uniformBlockId = 0;
            if (blockData != null) {
                memFree(blockData);
                blockData = null;
            }
        }
    }

    public short getBlock(int lx, int ly, int lz) {
        if (uniform) return uniformBlockId;
        if (blockData == null) return 0;
        return blockData.getShort(localIndex(lx, ly, lz) * 2);
    }

    public boolean isUniform() { return uniform; }
    public short getUniformBlockId() { return uniformBlockId; }

    public boolean isAir() { return uniform && uniformBlockId == 0; }
    public int getSolidBlockCount() { return solidBlockCount; }

    public long getSsboOffset() { return ssboOffset; }
    public void setSsboOffset(long offset) { this.ssboOffset = offset; }
    public boolean isAllocatedOnGpu() { return ssboOffset >= 0; }

    public boolean isDirty() { return dirty.get(); }
    public void clearDirty() { dirty.set(false); }
    public void markDirty() { dirty.set(true); }

    public ByteBuffer getBlockData() { return blockData; }

    public ShortBuffer getBlockDataAsShortBuffer() {
        return blockData != null ? blockData.asShortBuffer() : null;
    }

    public void compactUniform() {
        if (blockData == null) return; // already uniform
        if (uniform) return;           // race: already done
        int cnt = solidBlockCount;
        if (cnt == 0) {
            uniform = true;
            uniformBlockId = 0;
            memFree(blockData);
            blockData = null;
            return;
        }
        if (cnt != BLOCKS_PER_NODE) return;
        short first = blockData.getShort(0);
        for (int i = 1; i < BLOCKS_PER_NODE; i++) {
            if (blockData.getShort(i * 2) != first) return;
        }
        uniform = true;
        uniformBlockId = first;
        memFree(blockData);
        blockData = null;
    }

    public void freeBlockData() {
        if (blockData != null) {
            memFree(blockData);
            blockData = null;
        }
    }

    public int getFoliageColor() { return foliageColor; }
    public int getGrassColor()   { return grassColor; }
    public int getWaterColor()   { return waterColor; }

    public void setBiomeColors(int foliage, int grass, int water) {
        this.foliageColor = foliage;
        this.grassColor = grass;
        this.waterColor = water;
    }

    private void ensureBlockArray() {
        if (blockData == null) {
            blockData = memAlloc(BLOCKS_PER_NODE * 2);
            if (uniform) {
                short fill = uniformBlockId;
                for (int i = 0; i < BLOCKS_PER_NODE; i++) {
                    blockData.putShort(i * 2, fill);
                }
                solidBlockCount = (uniformBlockId != 0) ? BLOCKS_PER_NODE : 0;
            }
        }
    }

    /** Returns a 64-bit occupancy mask: 1 bit per 4x4x4 sub-block (64 sub-blocks total).
     *  Bit index = (ly/4)*16 + (lz/4)*4 + (lx/4). 1 = at least one non-air block. */
    public long computeOccupancyMask() {
        if (uniform) {
            return uniformBlockId != 0 ? 0xFFFFFFFFFFFFFFFFL : 0L;
        }
        if (blockData == null) return 0L;
        ShortBuffer sb = blockData.asShortBuffer();
        long mask = 0L;
        for (int sy = 0; sy < 4; sy++) {
            int ly = sy * 4;
            for (int sz = 0; sz < 4; sz++) {
                int lz = sz * 4;
                for (int sx = 0; sx < 4; sx++) {
                    int lx = sx * 4;
                    int subIdx = sy * 16 + sz * 4 + sx;
                    boolean hasBlock = false;
                    outer:
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dz = 0; dz < 4; dz++) {
                            for (int dx = 0; dx < 4; dx++) {
                                int idx = ((ly + dy) << 8) | ((lz + dz) << 4) | (lx + dx);
                                if (sb.get(idx) != 0) { hasBlock = true; break outer; }
                            }
                        }
                    }
                    if (hasBlock) mask |= (1L << subIdx);
                }
            }
        }
        return mask;
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    @Override
    public String toString() {
        return String.format("ChunkNode[%d,%d,%d, uniform=%s, gpu=%s, dirty=%s]",
            cx, cy, cz, uniform, isAllocatedOnGpu(), isDirty());
    }
}
