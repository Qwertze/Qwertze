package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws what follows scaled about a point (Minecraft 1.21.4: a PoseStack). Scissors set while it is on are scaled with
 * it, because {@code GuiGraphics.enableScissor} applies the current pose.
 */
final class PoseScale {
    private PoseScale() {
    }

    static void push(GuiGraphics g, float originX, float originY, float scale) {
        g.pose().pushPose();
        g.pose().translate(originX, originY, 0f);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-originX, -originY, 0f);
    }

    static void pop(GuiGraphics g) {
        g.pose().popPose();
    }
}
