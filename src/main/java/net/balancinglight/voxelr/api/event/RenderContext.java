package net.balancinglight.voxelr.api.event;

/**
 * Context passed to per-frame render events.
 * Provides read-only statistics and GPU state info for the current frame.
 */
public final class RenderContext {

    private final long frameIndex;
    private final int visibleNodeCount;
    private final int allocatedNodeCount;
    private final int totalDrawCalls;
    private final double partialTick;
    private final float cameraX, cameraY, cameraZ;

    public RenderContext(long frameIndex, int visibleNodeCount, int allocatedNodeCount,
                         int totalDrawCalls, double partialTick,
                         float cameraX, float cameraY, float cameraZ) {
        this.frameIndex = frameIndex;
        this.visibleNodeCount = visibleNodeCount;
        this.allocatedNodeCount = allocatedNodeCount;
        this.totalDrawCalls = totalDrawCalls;
        this.partialTick = partialTick;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
    }

    /** Monotonically increasing frame counter since renderer initialization. */
    public long getFrameIndex() { return frameIndex; }

    /** Number of chunk nodes that passed frustum culling this frame. */
    public int getVisibleNodeCount() { return visibleNodeCount; }

    /** Total chunk nodes currently allocated in GPU VRAM. */
    public int getAllocatedNodeCount() { return allocatedNodeCount; }

    /** Number of indirect draw calls issued this frame (should be 1 or 2 for VoxelR). */
    public int getTotalDrawCalls() { return totalDrawCalls; }

    public double getPartialTick() { return partialTick; }
    public float getCameraX() { return cameraX; }
    public float getCameraY() { return cameraY; }
    public float getCameraZ() { return cameraZ; }
}
