package net.balancinglight.voxelr;

import net.fabricmc.api.ModInitializer;
import net.balancinglight.voxelr.api.VoxelRAPI;
import net.balancinglight.voxelr.config.VoxelRConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxelRMod implements ModInitializer {

    public static final String MOD_ID   = "voxelr";
    public static final String MOD_NAME = "VoxelR";
    public static final Logger LOGGER   = LoggerFactory.getLogger(MOD_NAME);

    private static VoxelRMod instance;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[VoxelR] Initializing VoxelR v{}", BuildInfo.VERSION);
        LOGGER.info("[VoxelR] GPU-accelerated voxel terrain renderer.");

        VoxelRConfig.load();
        VoxelRAPI.initialize();

        LOGGER.info("[VoxelR] Common initialization complete.");
    }

    public static VoxelRMod getInstance() { return instance; }
}
