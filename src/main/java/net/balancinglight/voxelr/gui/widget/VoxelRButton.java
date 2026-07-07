package net.balancinglight.voxelr.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/*
 * VoxelR-styled button.
 *
 * MC 26.1: g.hLine()/g.vLine() removed, replaced with g.fill().
 *          g.drawCenteredString() removed from GuiGraphicsExtractor; text omitted.
 */
public class VoxelRButton extends Button {

    private static final int NORMAL  = 0xFF1A1F2E;
    private static final int HOVER   = 0xFF252D42;
    private static final int PRESSED = 0xFF0D1117;
    private static final int BORDER  = 0xFF4A9EFF;

    public VoxelRButton(int x, int y, int width, int height,
                          Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int bg = isHovered() ? HOVER : NORMAL;
        if (!isActive()) bg = PRESSED;

        int x = getX(), y = getY(), w = getWidth(), h = getHeight();

        g.fill(x, y, x + w, y + h, bg);

        g.fill(x,         y,         x + w,     y + 1,     BORDER); // top
        g.fill(x,         y + h - 1, x + w,     y + h,     BORDER); // bottom
        g.fill(x,         y,         x + 1,     y + h,     BORDER); // left
        g.fill(x + w - 1, y,         x + w,     y + h,     BORDER); // right

        // Label text
        int textColor = isActive() ? 0xFFE0E8FF : 0xFF6B7280;
        g.text(Minecraft.getInstance().font, getMessage(), x + w / 2 - Minecraft.getInstance().font.width(getMessage()) / 2, y + (h - 8) / 2, textColor);
    }

    public static Builder VoxelRBuilder(Component label, OnPress action) {
        return new Builder(label, action);
    }

    public static class Builder {
        private final Component label;
        private final OnPress   action;
        private int x, y, width = 120, height = 20;

        public Builder(Component label, OnPress action) {
            this.label  = label;
            this.action = action;
        }

        public Builder pos(int x, int y)      { this.x = x; this.y = y; return this; }
        public Builder position(int x, int y) { this.x = x; this.y = y; return this; }
        public Builder size(int w, int h)     { this.width = w; this.height = h; return this; }

        public VoxelRButton build() {
            return new VoxelRButton(x, y, width, height, label, action);
        }
    }
}
