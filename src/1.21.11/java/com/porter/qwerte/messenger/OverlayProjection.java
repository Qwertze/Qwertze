package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Minecraft 1.21.11: the GUI renderer builds its own projection from the window and the GUI scale, so what is drawn
 * through {@code GuiGraphics} is already in GUI pixels during Meteor's 2D render event. Nothing to do.
 */
public final class OverlayProjection {
    private OverlayProjection() {
    }

    public static void begin(GuiGraphics g) {
    }

    public static void end(GuiGraphics g) {
    }
}
