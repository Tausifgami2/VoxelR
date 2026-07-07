package net.balancinglight.voxelr.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.gui.widget.VoxelRButton;
import net.balancinglight.voxelr.gui.widget.VoxelRSlider;
import net.balancinglight.voxelr.gui.widget.VoxelRToggle;

import java.util.ArrayList;
import java.util.List;

/**
 * VoxelR configuration screen.
 */
public class VoxelRConfigScreen extends Screen {

    private static final int BG_COLOR    = 0xE5080E1A;
    private static final int ACCENT      = 0xFF4A9EFF;
    private static final int HEADER_H    = 48;
    private static final int FOOTER_H    = 40;
    private static final int SCROLL_SPEED = 12;

    private final Screen parent;
    private VoxelRConfig cfg;

    private final List<AbstractWidget> scrollWidgets   = new ArrayList<>();
    private final List<Integer>        originalYValues = new ArrayList<>();
    private int scrollOffset  = 0;
    private int contentHeight = 0;

    public VoxelRConfigScreen(Screen parent) {
        super(Component.literal("VoxelR — Configuration"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cfg = VoxelRConfig.get();
        scrollWidgets.clear();
        originalYValues.clear();

        int cx = width / 2, lx = cx - 180, y = HEADER_H + 8;
        int gap = 26, w = 360;

        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Enable VoxelR Renderer"),
            cfg.enabled, v -> cfg.enabled = v), y); y += gap;
        addS(new VoxelRSlider(lx, y, w, 20, "Render Distance", 2, 256,
            cfg.renderDistance, v -> cfg.renderDistance = v), y); y += gap;
        addS(new VoxelRSlider(lx, y, w, 20, "Simulation Distance (vanilla)", 2, 32,
            cfg.simulationDistance, v -> cfg.simulationDistance = v), y); y += gap;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Hide Fog (F10)"),
            cfg.hideFog, v -> { cfg.hideFog = v; VoxelRConfig.save(); }), y); y += gap + 8;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Debug Overlay"),
            cfg.showDebugOverlay, v -> cfg.showDebugOverlay = v), y); y += gap;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("GPU Timing in Overlay"),
            cfg.showGpuTiming, v -> cfg.showGpuTiming = v), y); y += gap + 8;
        addS(new VoxelRSlider(lx, y, w, 20, "SSBO Prealloc (MB)", 64, 12288,
            cfg.ssboPreallocMb, v -> cfg.ssboPreallocMb = v), y); y += gap;
        addS(new VoxelRSlider(lx, y, w, 20, "Max Diffs/Frame", 256, 65536,
            cfg.maxDiffWritesPerFrame, v -> cfg.maxDiffWritesPerFrame = v), y); y += gap + 8;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Auto Shrink Render Distance (Memory)"),
            cfg.memoryShrinkEnabled, v -> cfg.memoryShrinkEnabled = v), y); y += gap;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Unload Far Chunks"),
            cfg.chunkUnloadEnabled, v -> cfg.chunkUnloadEnabled = v), y); y += gap;
        addS(new VoxelRToggle(lx, y, w, 20, Component.literal("Freeze Chunk Loading (F9)"),
            cfg.freezeChunkLoading, v -> { cfg.freezeChunkLoading = v; VoxelRConfig.save(); }), y); y += gap;
        contentHeight = y - HEADER_H;

        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal("Save & Close"), btn -> { VoxelRConfig.save(); onClose(); }
        ).pos(cx - 105, height - FOOTER_H + 10).size(100, 20).build());

        addRenderableWidget(VoxelRButton.VoxelRBuilder(
            Component.literal("Cancel"), btn -> onClose()
        ).pos(cx + 5, height - FOOTER_H + 10).size(100, 20).build());

        clampScroll();
        applyScroll();
    }

    private void addS(AbstractWidget w, int origY) {
        originalYValues.add(origY);
        scrollWidgets.add(w);
        addRenderableWidget(w);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scrollOffset -= (int)(dy * SCROLL_SPEED);
        clampScroll(); applyScroll(); return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 264) { scrollOffset += SCROLL_SPEED; clampScroll(); applyScroll(); return true; }
        if (key == 265) { scrollOffset -= SCROLL_SPEED; clampScroll(); applyScroll(); return true; }
        return false;
    }

    private void clampScroll() {
        int vis = height - HEADER_H - FOOTER_H;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - vis)));
    }

    private void applyScroll() {
        int bot = height - FOOTER_H;
        for (int i = 0; i < scrollWidgets.size(); i++) {
            AbstractWidget w = scrollWidgets.get(i);
            int vy = originalYValues.get(i) - scrollOffset;
            w.setY(vy);
            boolean visible = vy + w.getHeight() > HEADER_H && vy < bot;
            w.visible = visible;
            w.active  = visible;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, BG_COLOR);

        // Header bar
        g.fill(0, 0, width, HEADER_H, 0xFF0D1421);
        g.fill(0, HEADER_H - 1, width, HEADER_H, ACCENT);
        g.text(net.minecraft.client.Minecraft.getInstance().font,
            Component.literal("VoxelR — Configuration"), width / 2 - 80, HEADER_H / 2 - 4, ACCENT);

        // Scroll bar
        int vis = height - HEADER_H - FOOTER_H;
        if (contentHeight > vis) {
            int max   = Math.max(1, contentHeight - vis);
            int thumbH = Math.max(20, vis * vis / contentHeight);
            int thumbY = HEADER_H + (int)((long) scrollOffset * (vis - thumbH) / max);
            g.fill(width - 6, HEADER_H,      width - 4, height - FOOTER_H, 0xFF1F2937);
            g.fill(width - 6, thumbY,         width - 4, thumbY + thumbH,  ACCENT);
        }

        // Render widgets on top of the background
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public void onClose()           { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen()  { return false; }
}
