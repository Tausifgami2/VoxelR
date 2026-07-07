package net.balancinglight.voxelr.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

/**
 * Animated toggle widget — VoxelR dark theme.
 *
 * MC 26.1.2 fixes:
 * - renderWidget(GuiGraphics,...) → extractWidgetRenderState(GuiGraphicsExtractor,...).
 * - g.hLine() / g.vLine() removed; replaced with 1-pixel g.fill() strips.
 * - g.drawString() → g.text(Font, Component, x, y, color).
 * - @Override removed from onClick / keyPressed (parent signatures changed).
 */
public class VoxelRToggle extends AbstractWidget {

    private static final int TRACK_ON  = 0xFF1D4ED8;
    private static final int TRACK_OFF = 0xFF374151;
    private static final int THUMB     = 0xFFE0E8FF;
    private static final int LABEL_ON  = 0xFFE0E8FF;
    private static final int LABEL_OFF = 0xFF9CA3AF;
    private static final int BORDER    = 0xFF6B7280;

    private boolean value;
    private float   animProgress;
    private final Consumer<Boolean> onChange;

    public VoxelRToggle(int x, int y, int width, int height,
                          Component label, boolean initialValue, Consumer<Boolean> onChange) {
        super(x, y, width, height, label);
        this.value        = initialValue;
        this.animProgress = initialValue ? 1.0f : 0.0f;
        this.onChange     = onChange;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        float target = value ? 1.0f : 0.0f;
        animProgress = Mth.lerp(0.25f, animProgress, target);

        int x = getX(), y = getY();
        int trackW = 32, trackH = 14;
        int tx = x + getWidth() - trackW - 2;
        int ty = y + (getHeight() - trackH) / 2;

        // Track fill
        int trackColor = lerpColor(TRACK_OFF, TRACK_ON, animProgress);
        g.fill(tx, ty, tx + trackW, ty + trackH, trackColor);

        // FIX: hLine/vLine removed — use 1-pixel fill strips for border.
        g.fill(tx,              ty,              tx + trackW, ty + 1,          BORDER); // top
        g.fill(tx,              ty + trackH - 1, tx + trackW, ty + trackH,     BORDER); // bottom
        g.fill(tx,              ty,              tx + 1,      ty + trackH,     BORDER); // left
        g.fill(tx + trackW - 1, ty,              tx + trackW, ty + trackH,     BORDER); // right

        // Animated thumb
        int thumbX = tx + 2 + (int)(animProgress * (trackW - trackH));
        g.fill(thumbX, ty + 2, thumbX + trackH - 4, ty + trackH - 2, THUMB);

        // FIX: drawString removed → g.text(Font, Component, x, y, color).
        g.text(Minecraft.getInstance().font,
            getMessage(), x, y + (getHeight() - 8) / 2,
            value ? LABEL_ON : LABEL_OFF);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubled) {
        toggle();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 32 || keyCode == 257 || keyCode == 335) { toggle(); return true; }
        return false;
    }

    private void toggle() {
        value = !value;
        if (onChange != null) onChange.accept(value);
        playDownSound(Minecraft.getInstance().getSoundManager());
    }

    public boolean getValue()           { return value; }
    public void    setValue(boolean v)  { this.value = v; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int)(aa + (ba - aa) * t) << 24)
             | ((int)(ar + (br - ar) * t) << 16)
             | ((int)(ag + (bg - ag) * t) <<  8)
             |  (int)(ab + (bb - ab) * t);
    }
}
