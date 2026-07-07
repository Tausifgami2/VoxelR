package net.balancinglight.voxelr.gui.screen;

import net.minecraft.client.gui.screens.Screen;

/**
 * ModMenu integration — exposes the VoxelR config screen.
 * ModMenu discovers this class via fabric.mod.json custom.modmenu entry.
 * Decoupled from ModMenu API version to avoid compile-time dependency issues.
 */
public class VoxelRConfigScreenFactory {
    public Screen create(Screen parent) {
        return new VoxelRConfigScreen(parent);
    }
}
