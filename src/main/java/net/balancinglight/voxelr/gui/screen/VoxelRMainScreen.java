package net.balancinglight.voxelr.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.balancinglight.voxelr.BuildInfo;
import net.balancinglight.voxelr.VoxelRClient;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.gui.widget.VoxelRButton;

/**
 * VoxelR main hub screen (V key).
 */
public class VoxelRMainScreen extends Screen {

    private static final int BG     = 0xE5080E1A;
    private static final int ACCENT = 0xFF4A9EFF;
    private static final int MUTED  = 0xFF6B7280;
    private static final int PANEL  = 0xFF111827;
    private static final int BORDER = 0xFF1F2937;

    private final Screen parent;

    public VoxelRMainScreen(Screen parent) {
        super(Component.literal("VoxelR"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2, panelX = cx - 120, startY = height / 2 - 55;
        int bW = 200, bH = 22, gap = 7;

        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal("Configure Renderer"),
            btn -> minecraft.setScreen(new VoxelRConfigScreen(this))
        ).pos(panelX + 20, startY).size(bW, bH).build());

        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal("Debug Overlay"),
            btn -> minecraft.setScreen(new VoxelRDebugScreen(this))
        ).pos(panelX + 20, startY + bH + gap).size(bW, bH).build());

        boolean en = VoxelRConfig.get().enabled;
        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal(en ? "Disable Renderer (F7)" : "Enable Renderer (F7)"),
            btn -> {
                VoxelRConfig cfg = VoxelRConfig.get();
                cfg.enabled = !cfg.enabled;
                VoxelRConfig.save();
                rebuildWidgets();
            }
        ).pos(panelX + 20, startY + (bH + gap) * 2).size(bW, bH).build());

        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal("Close"), btn -> onClose()
        ).pos(panelX + 20, startY + (bH + gap) * 3 + 10).size(bW, bH).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, BG);

        int cx = width / 2, pW = 240, pH = 220;
        int px = cx - pW / 2, py = height / 2 - 90;

        // Panel background
        g.fill(px, py, px + pW, py + pH, PANEL);

        // Border
        g.fill(px,          py,          px + pW, py + 1,      BORDER); // top
        g.fill(px,          py + pH - 1, px + pW, py + pH,     BORDER); // bottom
        g.fill(px,          py,          px + 1,  py + pH,     BORDER); // left
        g.fill(px + pW - 1, py,          px + pW, py + pH,     BORDER); // right

        // Accent top stripe
        g.fill(px, py, px + pW, py + 3, ACCENT);

        // Title text
        g.text(net.minecraft.client.Minecraft.getInstance().font,
            Component.literal("VoxelR"), px + pW / 2 - 30, py + 12, ACCENT);
        g.text(net.minecraft.client.Minecraft.getInstance().font,
            Component.literal(BuildInfo.VERSION), px + pW / 2 - 20, py + 24, MUTED);

        // Status indicator at bottom of panel
        var renderer = VoxelRClient.getRenderer();
        int statusColor = (renderer != null && renderer.isInitialized()) ? 0xFF34D399 : 0xFF6B7280;
        String statusText = (renderer != null && renderer.isInitialized()) ? "Renderer: Active" : "Renderer: Inactive";
        g.text(net.minecraft.client.Minecraft.getInstance().font,
            Component.literal(statusText), px + 8, py + pH - 20, statusColor);
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose()          { minecraft.setScreen(parent); }
}
