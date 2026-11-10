package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws what follows scaled about a point (Minecraft 1.21.11: a Matrix3x2fStack). Scissors set while it is on are
 * scaled with it, because {@code GuiGraphics.enableScissor} applies the current pose.
 */
final class PoseScale {
    private PoseScale() {
    }

    static void push(GuiGraphics g, float originX, float originY, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(originX, originY);
        g.pose().scale(scale, scale);
        g.pose().translate(-originX, -originY);
    }

    static void pop(GuiGraphics g) {
        g.pose().popMatrix();
    }
}
