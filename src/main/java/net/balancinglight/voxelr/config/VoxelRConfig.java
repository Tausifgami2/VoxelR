package net.balancinglight.voxelr.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.balancinglight.voxelr.VoxelRMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VoxelRConfig {

    private static final Gson   GSON        = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "VoxelR.json";
    private static VoxelRConfig instance;

    // ---- Renderer ----
    public boolean enabled           = true;

    /** Render distance in chunks. ChunkRequestManager loads up to this distance. */
    public int renderDistance        = 32;

    /** Vanilla simulation distance — keep small. VoxelR extends render beyond this. */
    public int simulationDistance    = 12;

    public boolean gpuFrustumCulling = true;
    public boolean sparseGrid        = true;

    // ---- Debug ----
    public boolean showDebugOverlay  = false;
    public boolean showGpuTiming     = false;
    public boolean logSsboEvents     = false;

    // When true, cancels vanilla chunk rendering so only VoxelR blocks are visible.
    public boolean hideVanillaTerrain = false;

    // ---- Memory / Chunk Management ----
    public boolean memoryShrinkEnabled   = true;
    public double  memoryShrinkThreshold = 0.88;
    public double  memoryRestoreThreshold = 0.60;
    public double  memoryShrinkFactor    = 0.70;
    /** When true, chunks outside render distance are unloaded from the sparse grid. */
    public boolean chunkUnloadEnabled    = true;

    /** When true, freezes chunk loading/unloading to prevent boundary stutter. */
    public boolean freezeChunkLoading    = false;

    /** When true, disables fog so all loaded chunks are visible at full opacity. */
    public boolean hideFog               = false;

    // ---- Performance ----
    public int maxDiffWritesPerFrame = 65536;
    public int ssboPreallocMb        = 4096;
    public int diffWorkerThreads     = 0; // 0 = auto

    // -------------------------------------------------------------------------

    private VoxelRConfig() {}

    public static VoxelRConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                VoxelRConfig loaded = GSON.fromJson(json, VoxelRConfig.class);
                // Merge with defaults so new fields always get their default value
                instance = new VoxelRConfig();
                instance.enabled = loaded.enabled;
                instance.renderDistance = loaded.renderDistance;
                instance.simulationDistance = loaded.simulationDistance;
                instance.gpuFrustumCulling = loaded.gpuFrustumCulling;
                instance.showDebugOverlay = loaded.showDebugOverlay;
                instance.showGpuTiming = loaded.showGpuTiming;
                instance.logSsboEvents = loaded.logSsboEvents;
                instance.hideVanillaTerrain = loaded.hideVanillaTerrain;
                instance.memoryShrinkEnabled = loaded.memoryShrinkEnabled;
                instance.memoryShrinkThreshold = loaded.memoryShrinkThreshold;
                instance.memoryRestoreThreshold = loaded.memoryRestoreThreshold;
                instance.memoryShrinkFactor = loaded.memoryShrinkFactor;
                instance.chunkUnloadEnabled = loaded.chunkUnloadEnabled;
                instance.freezeChunkLoading = loaded.freezeChunkLoading;
                instance.hideFog = loaded.hideFog;
                instance.maxDiffWritesPerFrame = loaded.maxDiffWritesPerFrame;
                instance.ssboPreallocMb = loaded.ssboPreallocMb;
                instance.diffWorkerThreads = loaded.diffWorkerThreads;
            } catch (IOException e) {
                VoxelRMod.LOGGER.warn("[VoxelR] Failed to load config: {}", e.getMessage());
                instance = new VoxelRConfig();
            }
        } else {
            instance = new VoxelRConfig();
            save();
        }
    }

    public static void save() {
        if (instance == null) return;
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try {
            Files.writeString(path, GSON.toJson(instance));
        } catch (IOException e) {
            VoxelRMod.LOGGER.warn("[VoxelR] Failed to save config: {}", e.getMessage());
        }
    }
}
