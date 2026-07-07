package net.balancinglight.voxelr.api;

import net.balancinglight.voxelr.api.block.BlockRegistry;
import net.balancinglight.voxelr.api.event.VoxelREvents;
import net.balancinglight.voxelr.api.render.RenderAPI;

/**
 * VoxelR public API entry point.
 *
 * <p>Third-party mods should access VoxelR functionality exclusively through
 * this API. Internal implementation classes are not considered stable.
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * // In your mod's onInitialize():
 * if (FabricLoader.getInstance().isModLoaded("VoxelR")) {
 *     VoxelRAPI api = VoxelRAPI.get();
 *     api.events().onRendererReady().register(() -> {
 *         // safe to call render API here
 *     });
 * }
 * }</pre>
 */
public final class VoxelRAPI {

    private static VoxelRAPI instance;

    private final RenderAPI renderAPI;
    private final VoxelREvents events;
    private final BlockRegistry blockRegistry;
    private boolean initialized = false;

    private VoxelRAPI() {
        this.renderAPI = new RenderAPI();
        this.events = new VoxelREvents();
        this.blockRegistry = new BlockRegistry();
    }

    /**
     * Returns the shared VoxelRAPI instance.
     * Always check {@code FabricLoader.getInstance().isModLoaded("VoxelR")} first.
     */
    public static VoxelRAPI get() {
        if (instance == null) {
            throw new IllegalStateException("[VoxelR] VoxelRAPI accessed before initialization.");
        }
        return instance;
    }

    /** Internal — called by VoxelRMod.onInitialize(). Not part of the public API contract. */
    public static void initialize() {
        if (instance != null) return;
        instance = new VoxelRAPI();
        instance.initialized = true;
    }

    public static boolean isAvailable() {
        return instance != null && instance.initialized;
    }

    /** Access the render API for shader hooks, custom block renderers, and pass callbacks. */
    public RenderAPI render() {
        return renderAPI;
    }

    /** Access lifecycle and frame events. */
    public VoxelREvents events() {
        return events;
    }

    /** Register custom block rendering overrides. */
    public BlockRegistry blocks() {
        return blockRegistry;
    }
}
