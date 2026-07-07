package net.balancinglight.voxelr;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.core.render.VoxelRenderer;
import net.balancinglight.voxelr.core.world.VoxelWorld;
import net.balancinglight.voxelr.gui.screen.VoxelRMainScreen;
import org.lwjgl.glfw.GLFW;

public class VoxelRClient implements ClientModInitializer {

    private static VoxelRClient instance;
    private static VoxelRenderer renderer;
    private static VoxelWorld voxelWorld;

    private Object currentLevel = null; // tracks world changes for SSBO reset

    public static KeyMapping KEY_OPEN_GUI;
    public static KeyMapping KEY_TOGGLE;
    public static KeyMapping KEY_DEBUG_OVERLAY;
    public static KeyMapping KEY_FREEZE_CHUNKS;
    public static KeyMapping KEY_TOGGLE_FOG;

    // FIX: KeyMapping.Category is now a record in MC 26.1.2.
    // Category.create(String, int) was removed. Use Category.register(Identifier).
    private static final KeyMapping.Category VoxelR_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("voxelr", "categories.voxelr"));

    @Override
    public void onInitializeClient() {
        instance = this;
        VoxelRMod.LOGGER.info("[VoxelR] Client initialization starting...");

        registerKeyMappings();

        voxelWorld = new VoxelWorld();
        renderer   = new VoxelRenderer(voxelWorld);

        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register((LevelTerrainRenderContext ctx) -> {
            if (VoxelRConfig.get().enabled) {
                renderer.onBeforeChunks(ctx);
            }
        });

        // FIX: AFTER_TRANSLUCENT_TERRAIN fires after translucent terrain — maps to v2's AFTER_TRANSLUCENT.
        // Callback provides LevelRenderContext (not LevelTerrainRenderContext).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register((LevelRenderContext ctx) -> {
            if (VoxelRConfig.get().enabled) renderer.onAfterTranslucent(ctx);
        });

        LevelRenderEvents.END_MAIN.register((LevelRenderContext ctx) -> {
            renderer.onFrameEnd(ctx);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Detect world change and reset SSBO to prevent data mixing
            if (mc.level != currentLevel) {
                if (currentLevel != null && renderer != null) {
                    renderer.onWorldUnload();
                }
                currentLevel = mc.level;
            }

            if (mc.level != null && mc.player != null && !VoxelRConfig.get().freezeChunkLoading) {
                voxelWorld.getChunkRequestManager().tick();
            }

            // Debug overlay — show action bar every 20 ticks
            if (VoxelRConfig.get().showDebugOverlay
                && mc.level != null && mc.player != null
                && renderer != null && renderer.isInitialized()) {
                if (mc.level.getGameTime() % 20 == 0) {
                    mc.player.sendOverlayMessage(
                        Component.literal("§a[VoxelR] " + renderer.getDebugInfo()));
                }
            }

            while (KEY_OPEN_GUI.consumeClick()) {
                mc.setScreen(new VoxelRMainScreen(null));
            }
            while (KEY_TOGGLE.consumeClick()) {
                VoxelRConfig cfg = VoxelRConfig.get();
                if (cfg.enabled) {
                    cfg.enabled = false;
                    cfg.hideVanillaTerrain = false;
                    if (mc.player != null)
                        mc.player.sendOverlayMessage(Component.literal("§c[VoxelR] OFF — Vanilla ON"));
                } else {
                    cfg.enabled = true;
                    cfg.hideVanillaTerrain = true;
                    if (mc.player != null)
                        mc.player.sendOverlayMessage(Component.literal("§a[VoxelR] ON — Vanilla OFF"));
                }
                VoxelRConfig.save();
            }
            while (KEY_DEBUG_OVERLAY.consumeClick()) {
                VoxelRConfig cfg = VoxelRConfig.get();
                cfg.showDebugOverlay = !cfg.showDebugOverlay;
                VoxelRConfig.save();
                if (mc.player != null) {
                    mc.player.sendOverlayMessage(
                        Component.literal(cfg.showDebugOverlay
                            ? "§a[VoxelR] Debug overlay ON"
                            : "§c[VoxelR] Debug overlay OFF"));
                }
            }
            while (KEY_FREEZE_CHUNKS.consumeClick()) {
                VoxelRConfig cfg = VoxelRConfig.get();
                cfg.freezeChunkLoading = !cfg.freezeChunkLoading;
                VoxelRConfig.save();
                String state = cfg.freezeChunkLoading ? "§efrozen" : "§aunfrozen";
                if (mc.player != null) {
                    mc.player.sendOverlayMessage(
                        Component.literal("[VoxelR] Chunk loading " + state + "§r"));
                }
            }
            while (KEY_TOGGLE_FOG.consumeClick()) {
                VoxelRConfig cfg = VoxelRConfig.get();
                cfg.hideFog = !cfg.hideFog;
                VoxelRConfig.save();
                String state = cfg.hideFog ? "§ehidden" : "§avisible";
                if (mc.player != null) {
                    mc.player.sendOverlayMessage(
                        Component.literal("[VoxelR] Fog " + state + "§r"));
                }
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            voxelWorld.shutdown();
            if (renderer != null) renderer.destroy();
        });

        VoxelRMod.LOGGER.info("[VoxelR] Client initialization complete.");
    }

    private void registerKeyMappings() {
        KEY_OPEN_GUI = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.voxelr.open_gui",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
            VoxelR_CATEGORY
        ));
        KEY_TOGGLE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.voxelr.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6,
            VoxelR_CATEGORY
        ));
        KEY_DEBUG_OVERLAY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.voxelr.debug_overlay",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8,
            VoxelR_CATEGORY
        ));
        KEY_FREEZE_CHUNKS = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.voxelr.freeze_chunks",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9,
            VoxelR_CATEGORY
        ));
        KEY_TOGGLE_FOG = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.voxelr.toggle_fog",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10,
            VoxelR_CATEGORY
        ));
    }

    public static VoxelRClient getInstance()   { return instance; }
    public static VoxelRenderer getRenderer() { return renderer; }
    public static VoxelWorld getVoxelWorld()     { return voxelWorld; }
}
