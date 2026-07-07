package net.balancinglight.voxelr.core.render.pipeline;

import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.core.world.ChunkNode;
import net.balancinglight.voxelr.util.GlUtil;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

public final class SSBOManager {

    public static final int BINDING_VOXEL_DATA  = 0;
    public static final int BINDING_VOXEL_DATA1 = 2;
    public static final int BINDING_NODE_META   = 1;
    public static final int ADDITIONAL_PAIR_BASE_BINDING = 10;
    public static final int MAX_SSBO_PAIRS = 4;

    public static final int BUF_TEX_UNIT_VD0_PAIR0_BUF0 = 1;
    public static final int BUF_TEX_UNIT_VD0_PAIR0_BUF1 = 2;

    private static final int NODE_META_STRIDE = 4 * Integer.BYTES;

    private int[][] voxelSsboIds;
    private int metaSsboId   = -1;
    private int numPairs;

    private int[][] bufTexVoxelIds;
    private int     bufTexOccupancyMasksId;

    private long capacityNodes;
    private long slotsPerBuffer;
    private long ssboSizeBytes;

    private final Queue<Long> freeSlots = new ArrayDeque<>(4096);
    private long nextSlot = 0L;

    private ByteBuffer metaBuf = null;

    private boolean initialized = false;
    private long lastFullWarningFrame = -1L;

    public void initialize() {
        if (initialized) return;

        long driverLimit = glGetInteger(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (driverLimit <= 0) {
            long texBufBytes = (long) glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE) * 4L;
            driverLimit = Math.min(texBufBytes,
                VoxelRConfig.get().ssboPreallocMb * 1024L * 1024L);
        }
        long configBytes = VoxelRConfig.get().ssboPreallocMb * 1024L * 1024L;

        int maxPairsFromBindings;
        if (GlUtil.SSBO_ENABLED) {
            int maxBindings = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
            maxPairsFromBindings = 1;
            if (maxBindings > ADDITIONAL_PAIR_BASE_BINDING) {
                maxPairsFromBindings += (maxBindings - ADDITIONAL_PAIR_BASE_BINDING) / 2;
            }
        } else {
            maxPairsFromBindings = Math.min(4, MAX_SSBO_PAIRS);
        }

        numPairs = Math.min(maxPairsFromBindings, MAX_SSBO_PAIRS);
        long perBufBytes = Math.min(driverLimit, configBytes / (numPairs * 2L));
        slotsPerBuffer = perBufBytes / ChunkNode.BYTES_PER_NODE;
        perBufBytes = slotsPerBuffer * ChunkNode.BYTES_PER_NODE; // round down

        capacityNodes = numPairs * slotsPerBuffer * 2L;
        ssboSizeBytes = capacityNodes * ChunkNode.BYTES_PER_NODE;

        VoxelRMod.LOGGER.info("[VoxelR] Allocating {} SSBO pair(s) — {} MB total / {} MB config ({} nodes, {} slots/buf, {} MB/buf, driver limit {} MB)",
                numPairs, ssboSizeBytes / (1024*1024), configBytes / (1024*1024),
                capacityNodes, slotsPerBuffer, perBufBytes / (1024*1024), driverLimit / (1024*1024));

        voxelSsboIds = new int[numPairs][2];
        for (int p = 0; p < numPairs; p++) {
            for (int b = 0; b < 2; b++) {
                int id = glGenBuffers();
                glBindBuffer(GL_COPY_WRITE_BUFFER, id);
                glBufferData(GL_COPY_WRITE_BUFFER, perBufBytes, GL_DYNAMIC_DRAW);
                if (GlUtil.SSBO_ENABLED) {
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, getBinding(p, b), id);
                }
                voxelSsboIds[p][b] = id;
            }
        }

        bufTexVoxelIds = new int[numPairs][2];
        bufTexOccupancyMasksId = 0;

        long metaSize = capacityNodes * NODE_META_STRIDE;
        metaSsboId = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, metaSsboId);
        glBufferData(GL_COPY_WRITE_BUFFER, metaSize, GL_DYNAMIC_DRAW);
        if (GlUtil.SSBO_ENABLED) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_NODE_META, metaSsboId);
        }

        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        metaBuf = MemoryUtil.memAlloc(NODE_META_STRIDE);
        initialized = true;
        VoxelRMod.LOGGER.info("[VoxelR] SSBOs ready: {} pairs, {} total nodes", numPairs, capacityNodes);
    }

    private int getBinding(int pairIdx, int bufIdx) {
        if (pairIdx == 0) {
            return bufIdx == 0 ? BINDING_VOXEL_DATA : BINDING_VOXEL_DATA1;
        }
        return ADDITIONAL_PAIR_BASE_BINDING + (pairIdx - 1) * 2 + bufIdx;
    }

    public long allocateSlot(ChunkNode node) {
        if (!initialized) throw new IllegalStateException("SSBOManager not initialized");

        Long recycled = freeSlots.poll();
        long slot = (recycled != null) ? recycled : nextSlot++;

        if (slot >= capacityNodes) {
            if (!grow()) {
                nextSlot--;
                long now = System.nanoTime();
                if (now - lastFullWarningFrame > 1_000_000_000L) {
                    lastFullWarningFrame = now;
                    VoxelRMod.LOGGER.warn("[VoxelR] SSBO full! Cannot allocate node ({},{},{})",
                            node.cx, node.cy, node.cz);
                }
                return -1L;
            }
        }

        long byteOffset = slot * ChunkNode.BYTES_PER_NODE;
        node.setSsboOffset(byteOffset);
        int flags = 1 | (node.getSolidBlockCount() << 16);
        writeNodeMeta(slot, node.cx, node.cy, node.cz, flags);
        return byteOffset;
    }

    private boolean grow() {
        if (numPairs >= MAX_SSBO_PAIRS) return false;
        long perBufBytes = slotsPerBuffer * ChunkNode.BYTES_PER_NODE;
        long usedBytes = capacityNodes * ChunkNode.BYTES_PER_NODE;
        long configBytes = VoxelRConfig.get().ssboPreallocMb * 1024L * 1024L;
        if (usedBytes + perBufBytes * 2 > configBytes) return false;

        int newPairIdx = numPairs;
        int nextBind0 = getBinding(newPairIdx, 0);
        int nextBind1 = getBinding(newPairIdx, 1);
        if (GlUtil.SSBO_ENABLED && nextBind1 >= glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)) return false;

        int[][] newIds = new int[numPairs + 1][2];
        for (int p = 0; p < numPairs; p++) {
            newIds[p][0] = voxelSsboIds[p][0];
            newIds[p][1] = voxelSsboIds[p][1];
        }
        for (int b = 0; b < 2; b++) {
            int id = glGenBuffers();
            glBindBuffer(GL_COPY_WRITE_BUFFER, id);
            glBufferData(GL_COPY_WRITE_BUFFER, perBufBytes, GL_DYNAMIC_DRAW);
            if (GlUtil.SSBO_ENABLED) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, b == 0 ? nextBind0 : nextBind1, id);
            }
            newIds[newPairIdx][b] = id;
        }
        voxelSsboIds = newIds;
        numPairs++;

        long oldMetaSize = capacityNodes * NODE_META_STRIDE;
        long newCapacity = capacityNodes + slotsPerBuffer * 2;
        long newMetaSize = newCapacity * NODE_META_STRIDE;
        int newMetaBuf = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, newMetaBuf);
        glBufferData(GL_COPY_WRITE_BUFFER, newMetaSize, GL_DYNAMIC_DRAW);
        if (metaSsboId >= 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, metaSsboId);
            glBindBuffer(GL_COPY_WRITE_BUFFER, newMetaBuf);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, oldMetaSize);
            glDeleteBuffers(metaSsboId);
        }
        metaSsboId = newMetaBuf;
        if (GlUtil.SSBO_ENABLED) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_NODE_META, metaSsboId);
        }

        capacityNodes = newCapacity;
        ssboSizeBytes = capacityNodes * ChunkNode.BYTES_PER_NODE;

        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);

        VoxelRMod.LOGGER.info("[VoxelR] SSBO grew: {} pairs, {} total nodes", numPairs, capacityNodes);
        return true;
    }

    public void freeSlot(ChunkNode node) {
        if (!node.isAllocatedOnGpu()) return;
        long slot = node.getSsboOffset() / ChunkNode.BYTES_PER_NODE;
        freeSlots.add(slot);
        node.setSsboOffset(-1L);
    }

    public void updateNodeMeta(ChunkNode node) {
        if (!node.isAllocatedOnGpu()) return;
        long slot = node.getSsboOffset() / ChunkNode.BYTES_PER_NODE;
        int flags = 1 | (node.getSolidBlockCount() << 16);
        writeNodeMeta(slot, node.cx, node.cy, node.cz, flags);
    }

    public void uploadNode(ChunkNode node, ByteBuffer reusableBuf, ByteBuffer filteredData) {
        if (!node.isAllocatedOnGpu()) {
            long off = allocateSlot(node);
            if (off < 0) return;
        }

        long byteOffset = node.getSsboOffset();
        long slot = byteOffset / ChunkNode.BYTES_PER_NODE;
        long pairIdx = slot / (slotsPerBuffer * 2);
        long slotInPair = slot % (slotsPerBuffer * 2);
        int bufIdx = slotInPair < slotsPerBuffer ? 0 : 1;
        long localSlot = bufIdx == 0 ? slotInPair : slotInPair - slotsPerBuffer;
        int bufId = voxelSsboIds[(int)pairIdx][bufIdx];
        long bufOffset = localSlot * ChunkNode.BYTES_PER_NODE;

        glBindBuffer(GL_COPY_WRITE_BUFFER, bufId);

        ByteBuffer src = filteredData != null ? filteredData : node.getBlockData();
        if (src != null) {
            ByteBuffer buf = reusableBuf != null ? reusableBuf : MemoryUtil.memAlloc(8192);
            buf.clear();
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(buf), ChunkNode.BYTES_PER_NODE);
            buf.limit(8192);
            glBufferSubData(GL_COPY_WRITE_BUFFER, bufOffset, buf);
            if (reusableBuf == null) MemoryUtil.memFree(buf);
        } else if (node.isUniform()) {
            short id = filteredData != null ? 0 : node.getUniformBlockId();
            ByteBuffer buf = reusableBuf != null ? reusableBuf : MemoryUtil.memAlloc(8192);
            buf.clear();
            ShortBuffer sb = buf.asShortBuffer();
            for (int i = 0; i < ChunkNode.BLOCKS_PER_NODE; i++) sb.put(id);
            buf.limit(8192);
            glBufferSubData(GL_COPY_WRITE_BUFFER, bufOffset, buf);
            if (reusableBuf == null) MemoryUtil.memFree(buf);
        }

        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        updateNodeMeta(node);
    }

    public void bindForRender() {
        if (!GlUtil.SSBO_ENABLED) return;
        for (int p = 0; p < numPairs; p++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, getBinding(p, 0), voxelSsboIds[p][0]);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, getBinding(p, 1), voxelSsboIds[p][1]);
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_NODE_META, metaSsboId);
    }

    public void reset() {
        freeSlots.clear();
        nextSlot = 0L;
        VoxelRMod.LOGGER.info("[VoxelR] SSBOManager reset for new world.");
    }

    public void createBufferTextures(int occupancySsboId) {
        if (bufTexVoxelIds == null) return;
        for (int p = 0; p < numPairs; p++) {
            for (int b = 0; b < 2; b++) {
                int bufId = voxelSsboIds[p][b];
                if (bufId < 0) continue;
                int texId = glGenTextures();
                glActiveTexture(GL_TEXTURE1 + unitForPair(p, b));
                glBindTexture(GL_TEXTURE_BUFFER, texId);
                glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, bufId);
                bufTexVoxelIds[p][b] = texId;
            }
        }

        if (occupancySsboId >= 0) {
            bufTexOccupancyMasksId = glGenTextures();
            glActiveTexture(GL_TEXTURE15);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexOccupancyMasksId);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, occupancySsboId);
        }
    }

    public static int unitForPair(int pair, int buf) {
        if (pair == 0) {
            return buf == 0 ? BUF_TEX_UNIT_VD0_PAIR0_BUF0 : BUF_TEX_UNIT_VD0_PAIR0_BUF1;
        }
        return BUF_TEX_UNIT_VD0_PAIR0_BUF1 + 1 + (pair - 1) * 2 + buf;
    }

    public void bindBufferTextures() {
        if (bufTexVoxelIds != null) {
            for (int p = 0; p < numPairs; p++) {
                for (int b = 0; b < 2; b++) {
                    int texId = bufTexVoxelIds[p][b];
                    if (texId > 0) {
                        int unit = unitForPair(p, b);
                        glActiveTexture(GL_TEXTURE0 + unit);
                        glBindTexture(GL_TEXTURE_BUFFER, texId);
                    }
                }
            }
        }
        if (bufTexOccupancyMasksId > 0) {
            glActiveTexture(GL_TEXTURE15);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexOccupancyMasksId);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    public void destroy() {
        if (bufTexVoxelIds != null) {
            for (int p = 0; p < numPairs; p++) {
                for (int b = 0; b < 2; b++) {
                    if (bufTexVoxelIds[p][b] > 0) glDeleteTextures(bufTexVoxelIds[p][b]);
                }
            }
            bufTexVoxelIds = null;
        }
        if (bufTexOccupancyMasksId > 0) { glDeleteTextures(bufTexOccupancyMasksId); bufTexOccupancyMasksId = 0; }

        if (voxelSsboIds != null) {
            for (int p = 0; p < numPairs; p++) {
                if (voxelSsboIds[p][0] >= 0) glDeleteBuffers(voxelSsboIds[p][0]);
                if (voxelSsboIds[p][1] >= 0) glDeleteBuffers(voxelSsboIds[p][1]);
            }
            voxelSsboIds = null;
        }
        if (metaSsboId >= 0) { glDeleteBuffers(metaSsboId); metaSsboId = -1; }
        if (metaBuf != null) { MemoryUtil.memFree(metaBuf); metaBuf = null; }
        freeSlots.clear();
        nextSlot = 0L;
        initialized = false;
        VoxelRMod.LOGGER.info("[VoxelR] SSBOManager destroyed.");
    }

    public long getCapacityNodes()       { return capacityNodes; }
    public long getSlotsPerBuffer()      { return slotsPerBuffer; }
    public long getAllocatedNodeCount()  { return nextSlot - freeSlots.size(); }
    public int  getNumPairs()            { return numPairs; }
    public boolean isInitialized()       { return initialized; }

    private void writeNodeMeta(long slot, int cx, int cy, int cz, int flags) {
        metaBuf.clear();
        metaBuf.putInt(cx).putInt(cy).putInt(cz).putInt(flags).flip();
        glBindBuffer(GL_COPY_WRITE_BUFFER, metaSsboId);
        glBufferSubData(GL_COPY_WRITE_BUFFER, slot * NODE_META_STRIDE, metaBuf);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    }

    public void dumpMetaDiagnostic() {
        try {
            long count = getAllocatedNodeCount();
            if (count <= 0) return;
            int entries = (int) Math.min(count, 5L);
            ByteBuffer buf = MemoryUtil.memAlloc(entries * NODE_META_STRIDE);
            glBindBuffer(GL_COPY_READ_BUFFER, metaSsboId);
            glGetBufferSubData(GL_COPY_READ_BUFFER, 0L, buf);
            buf.rewind();
            for (int i = 0; i < entries; i++) {
                int cx = buf.getInt();
                int cy = buf.getInt();
                int cz = buf.getInt();
                int flags = buf.getInt();
                VoxelRMod.LOGGER.info("[VoxelR] Meta slot {}: cx={}, cy={}, cz={}, flags={}",
                    i, cx, cy, cz, flags);
            }
            MemoryUtil.memFree(buf);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
        } catch (Exception e) {
            VoxelRMod.LOGGER.warn("[VoxelR] dumpMetaDiagnostic failed: {}", e.getMessage());
        }
    }
}
