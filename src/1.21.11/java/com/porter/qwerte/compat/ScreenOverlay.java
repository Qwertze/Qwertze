package com.porter.qwerte.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Drawing on top of everything in a screen, which differs between Minecraft versions, for Minecraft 1.21.11.
 */
public final class ScreenOverlay {
    private ScreenOverlay() {
    }

    /**
     * Runs {@code draw} on a new layer above everything drawn so far, tooltips included.
     */
    public static void top(GuiGraphics graphics, Runnable draw) {
        graphics.nextStratum();
        draw.run();
    }

    /**
     * Shows the item's own tooltip at the position, above the overlay drawn with {@link #top}.
     */
    public static void itemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.setTooltipForNextFrame(font, stack, x, y);
    }
}
