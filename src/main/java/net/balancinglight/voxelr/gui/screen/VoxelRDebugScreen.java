package net.balancinglight.voxelr.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.balancinglight.voxelr.BuildInfo;
import net.balancinglight.voxelr.VoxelRClient;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.core.render.VoxelRenderer;
import net.balancinglight.voxelr.core.render.pipeline.SSBOManager;
import net.balancinglight.voxelr.core.world.ChunkRequestManager;
import net.balancinglight.voxelr.core.world.VoxelWorld;
import net.balancinglight.voxelr.gui.widget.VoxelRButton;
import net.balancinglight.voxelr.gui.widget.VoxelRToggle;

/**
 * VoxelR debug overlay screen.
 */
public class VoxelRDebugScreen extends Screen {

    private static final int PANEL = 0xFF0D1421, ACCENT = 0xFF4A9EFF;
    private static final int OK = 0xFF34D399, WARN = 0xFFFBBF24, ERR = 0xFFF87171;
    private static final int MUTED = 0xFF6B7280;

    private final Screen parent;

    public VoxelRDebugScreen(Screen parent) {
        super(Component.literal("VoxelR Debug"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(new VoxelRToggle(10, height - 50, 180, 20,
            Component.literal("Show Debug HUD"), VoxelRConfig.get().showDebugOverlay,
            v -> { VoxelRConfig.get().showDebugOverlay = v; VoxelRConfig.save(); }));

        addRenderableWidget(new VoxelRToggle(200, height - 50, 180, 20,
            Component.literal("GPU Timing"), VoxelRConfig.get().showGpuTiming,
            v -> { VoxelRConfig.get().showGpuTiming = v; VoxelRConfig.save(); }));

        addRenderableWidget(VoxelRButton.VoxelRBuilder(Component.literal("Close"), btn -> onClose())
            .pos(width - 80, height - 50).size(70, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xCC060B14);

        int x = 10, y = 10, panelW = 340, lh = 11;
        g.fill(x, y, x + panelW, y + 380, PANEL);

        var font = net.minecraft.client.Minecraft.getInstance().font;

        y += 6;
        g.text(font, Component.literal("VoxelR Debug " + BuildInfo.VERSION),
            x + 8, y, ACCENT); y += lh + 4;
        g.fill(x + 8, y, x + panelW - 8, y + 1, ACCENT); y += 6;

        VoxelRenderer renderer = VoxelRClient.getRenderer();
        VoxelWorld world          = VoxelRClient.getVoxelWorld();
        VoxelRConfig cfg        = VoxelRConfig.get();

        // Renderer status
        boolean rInit = renderer != null && renderer.isInitialized();
        boolean rFail = renderer != null && renderer.isInitFailed();
        g.text(font, Component.literal("Renderer: " + (rInit ? "Active" : rFail ? "FAILED" : "Pending")),
            x + 8, y, rInit ? OK : rFail ? ERR : MUTED); y += lh;
        g.text(font, Component.literal("Enabled: " + cfg.enabled),
            x + 8, y, cfg.enabled ? OK : ERR); y += lh;

        if (renderer != null && rInit) {
            g.text(font, Component.literal("Frame: " + renderer.getFrameIndex()),
                x + 8, y, ACCENT); y += lh;
            g.text(font, Component.literal("Atlas UV: " + (renderer.getAtlasUVBuffer().isBuilt() ? "Built" : "Pending")),
                x + 8, y, renderer.getAtlasUVBuffer().isBuilt() ? OK : WARN); y += lh + 4;
        } else {
            y += lh * 2 + 4;
        }

        if (renderer != null && rInit && renderer.getSsboManager().isInitialized()) {
            SSBOManager ssbo = renderer.getSsboManager();
            long a = ssbo.getAllocatedNodeCount(), c = ssbo.getCapacityNodes();
            float p = c > 0 ? a * 100f / c : 0;
            g.text(font, Component.literal("SSBO: " + a + "/" + c + " (" + String.format("%.1f", p) + "%)"),
                x + 8, y, p > 85 ? ERR : p > 65 ? WARN : OK); y += lh;
            g.text(font, Component.literal("Visible Nodes: " + renderer.getVisibleNodeCount()),
                x + 8, y, ACCENT); y += lh;
            y += lh + 4;
        }

        if (world != null) {
            ChunkRequestManager crm = world.getChunkRequestManager();
            g.text(font, Component.literal("ChunkReqMgr: " + (crm.isActive() ? "Active" : "Idle")),
                x + 8, y, crm.isActive() ? OK : MUTED); y += lh;
            g.text(font, Component.literal("Pending Diffs: " + world.getPendingDiffCount()),
                x + 8, y, ACCENT); y += lh;
            g.text(font, Component.literal("Grid Nodes: " + world.getGrid().getNodeCount()),
                x + 8, y, ACCENT); y += lh + 4;
        }

        g.text(font, Component.literal("Render Dist: " + cfg.renderDistance + " chunks"),
            x + 8, y, ACCENT); y += lh;
        g.text(font, Component.literal("Sim Dist: " + cfg.simulationDistance + " chunks"),
            x + 8, y, ACCENT); y += lh;
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose()          { minecraft.setScreen(parent); }
}
