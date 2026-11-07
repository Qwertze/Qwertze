package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.compat.ItemIcons;
import com.porter.qwerte.manager.gui.frame.WOverlayLayer;
import com.porter.qwerte.manager.gui.layout.CountLayout;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * The one way the window draws item icons, their durability bars and their counts.
 *
 * <p>Item icons are drawn by the game's own GUI code, which always lands above everything Meteor draws (quads and
 * text), so an icon would show through a popup or a hover card drawn by Meteor. Every icon therefore asks the overlay's
 * mask just before it is drawn and is skipped where the overlay is on top of it. The count is drawn right after its
 * icon, in the same task, so it stays on top of it.
 */
public final class ItemLayer {
    private ItemLayer() {
    }

    /**
     * Draws {@code stack} in a 16 by 16 GUI pixel box at ({@code ix, iy}) physical pixels, with its durability bar, and
     * {@code count} (may be null or empty) small in the box's bottom-right corner.
     */
    public static void draw(GuiRenderer renderer, GuiTheme theme, GuiGraphics g, ItemStack stack, String count, double ix, double iy) {
        if (g == null || stack == null || stack.isEmpty()) return;
        int u = SmGuiTheme.unitOf(theme);
        WOverlayLayer overlay = theme instanceof SmGuiTheme t ? t.overlay : null;
        int x = (int) Math.round(ix);
        int y = (int) Math.round(iy);
        int size = 16 * u;
        if (count == null || count.isEmpty()) {
            // The empty string asks for the durability bar and glint but no count
            renderer.post(() -> {
                if (overlay == null || !overlay.mask.blocks(x, y, size, size)) ItemIcons.draw(g, stack, x, y, u, "");
            });
            return;
        }
        float fit = (float) CountLayout.fit(CountLayout.scale(u, size), textUnits(count), size);
        renderer.post(() -> {
            if (overlay != null && overlay.mask.blocks(x, y, size, size)) return;
            ItemIcons.draw(g, stack, x, y, u, "");
            ItemIcons.drawCount(g, count, x + size, y + size, fit);
        });
    }

    private static final java.util.Map<String, Integer> WIDTHS = new java.util.HashMap<>();

    /** The width of a count in the game's font, in font pixels. */
    private static int textUnits(String text) {
        Integer w = WIDTHS.get(text);
        if (w == null) {
            if (WIDTHS.size() > 512) WIDTHS.clear();
            w = Minecraft.getInstance().font.width(text);
            WIDTHS.put(text, w);
        }
        return w;
    }
}
