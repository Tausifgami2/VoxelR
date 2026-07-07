package net.balancinglight.voxelr.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * VoxelR lifecycle and render-phase events for third-party mod integration.
 */
public final class VoxelREvents {

    /** Fired once the GPU renderer has been fully initialized and is ready to render. */
    public final Event<RendererReady> RENDERER_READY = EventFactory.createArrayBacked(
        RendererReady.class,
        listeners -> () -> {
            for (RendererReady l : listeners) l.onRendererReady();
        }
    );

    /** Fired each frame before the opaque voxel pass begins. */
    public final Event<BeforeOpaquePass> BEFORE_OPAQUE_PASS = EventFactory.createArrayBacked(
        BeforeOpaquePass.class,
        listeners -> (ctx) -> {
            for (BeforeOpaquePass l : listeners) l.onBeforeOpaquePass(ctx);
        }
    );

    /** Fired each frame after the opaque pass completes, before the transparent pass. */
    public final Event<AfterOpaquePass> AFTER_OPAQUE_PASS = EventFactory.createArrayBacked(
        AfterOpaquePass.class,
        listeners -> (ctx) -> {
            for (AfterOpaquePass l : listeners) l.onAfterOpaquePass(ctx);
        }
    );

    /** Fired after both render passes and MDI draw calls complete. */
    public final Event<AfterFrame> AFTER_FRAME = EventFactory.createArrayBacked(
        AfterFrame.class,
        listeners -> (ctx) -> {
            for (AfterFrame l : listeners) l.onAfterFrame(ctx);
        }
    );

    /** Fired when a chunk node is first allocated on the GPU. */
    public final Event<ChunkNodeAllocated> CHUNK_NODE_ALLOCATED = EventFactory.createArrayBacked(
        ChunkNodeAllocated.class,
        listeners -> (chunkX, chunkY, chunkZ) -> {
            for (ChunkNodeAllocated l : listeners) l.onChunkNodeAllocated(chunkX, chunkY, chunkZ);
        }
    );

    /** Fired when a chunk node is freed from GPU memory. */
    public final Event<ChunkNodeFreed> CHUNK_NODE_FREED = EventFactory.createArrayBacked(
        ChunkNodeFreed.class,
        listeners -> (chunkX, chunkY, chunkZ) -> {
            for (ChunkNodeFreed l : listeners) l.onChunkNodeFreed(chunkX, chunkY, chunkZ);
        }
    );

    /** Fired when the renderer is shut down (world unload / game exit). */
    public final Event<RendererShutdown> RENDERER_SHUTDOWN = EventFactory.createArrayBacked(
        RendererShutdown.class,
        listeners -> () -> {
            for (RendererShutdown l : listeners) l.onRendererShutdown();
        }
    );

    @FunctionalInterface public interface RendererReady { void onRendererReady(); }
    @FunctionalInterface public interface BeforeOpaquePass { void onBeforeOpaquePass(RenderContext ctx); }
    @FunctionalInterface public interface AfterOpaquePass { void onAfterOpaquePass(RenderContext ctx); }
    @FunctionalInterface public interface AfterFrame { void onAfterFrame(RenderContext ctx); }
    @FunctionalInterface public interface ChunkNodeAllocated { void onChunkNodeAllocated(int chunkX, int chunkY, int chunkZ); }
    @FunctionalInterface public interface ChunkNodeFreed { void onChunkNodeFreed(int chunkX, int chunkY, int chunkZ); }
    @FunctionalInterface public interface RendererShutdown { void onRendererShutdown(); }

    public VoxelREvents() {}
}
