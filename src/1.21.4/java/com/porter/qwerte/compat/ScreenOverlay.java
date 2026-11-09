package com.porter.qwerte.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Drawing on top of everything in a screen, which differs between Minecraft versions, for Minecraft 1.21.4.
 */
public final class ScreenOverlay {
    private ScreenOverlay() {
    }

    /**
     * Runs {@code draw} lifted above everything drawn so far, tooltips included (they sit at 400).
     */
    public static void top(GuiGraphics graphics, Runnable draw) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 300);
        draw.run();
        pose.popPose();
    }

    /**
     * Shows the item's own tooltip at the position, above the overlay drawn with {@link #top}: the tooltip adds its own
     * 400 and the overlay's items sit at about 300 + 401 + 100.
     */
    public static void itemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 600);
        graphics.renderTooltip(font, stack, x, y);
        pose.popPose();
    }
}
