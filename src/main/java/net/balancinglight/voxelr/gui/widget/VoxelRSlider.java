package net.balancinglight.voxelr.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/*
 * VoxelR-styled integer slider.
 *
 * MC 26.1: g.hLine() removed, replaced with g.fill() at 1px height.
 *          g.drawCenteredString() removed from GuiGraphicsExtractor; text omitted.
 */
public class VoxelRSlider extends AbstractSliderButton {

    private static final int TRACK  = 0xFF111827;
    private static final int FILL   = 0xFF1D4ED8;
    private static final int THUMB  = 0xFF4A9EFF;
    private static final int THOV   = 0xFF93C5FD;
    private static final int BORDER = 0xFF374151;

    private final int minValue, maxValue;
    private final String labelPrefix;
    private final Consumer<Integer> onChange;

    public VoxelRSlider(int x, int y, int width, int height,
                          String labelPrefix, int minValue, int maxValue,
                          int currentValue, Consumer<Integer> onChange) {
        super(x, y, width, height,
            Component.literal(labelPrefix + ": " + currentValue),
            (double)(currentValue - minValue) / (maxValue - minValue));
        this.minValue    = minValue;
        this.maxValue    = maxValue;
        this.labelPrefix = labelPrefix;
        this.onChange    = onChange;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(labelPrefix + ": " + getIntValue()));
    }

    @Override
    protected void applyValue() {
        if (onChange != null) onChange.accept(getIntValue());
    }

    public int getIntValue() {
        return minValue + (int) Math.round(value * (maxValue - minValue));
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int mid = y + h / 2;

        // Track background
        g.fill(x, mid - 2, x + w, mid + 2, TRACK);
        g.fill(x, mid - 2, x + w, mid - 1, BORDER); // top border
        g.fill(x, mid + 2, x + w, mid + 3, BORDER); // bottom border

        // Fill portion
        int fillEnd = x + (int)(value * w);
        g.fill(x, mid - 2, fillEnd, mid + 2, FILL);

        // Thumb
        int thumbX = fillEnd - 3;
        g.fill(thumbX, y + 2, thumbX + 6, y + h - 2, isHovered() ? THOV : THUMB);

        // Label text
        g.text(net.minecraft.client.Minecraft.getInstance().font,
            getMessage(), x + 4, y + (h - 8) / 2, 0xFFE0E8FF);
    }
}
