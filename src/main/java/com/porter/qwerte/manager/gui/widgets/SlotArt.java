package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.gui.frame.WOverlayLayer;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The vanilla inventory slot, as the website draws it (themes don't change it), and the hover card's text. */
final class SlotArt {
    static final int SLOT = 18;
    private static final int FILL = 0xFF8B8B8B;
    private static final int DARK = 0xFF373737;
    private static final int LIGHT = 0xFFFFFFFF;

    private SlotArt() {
    }

    /** A slot whose top-left corner is ({@code sx, sy}) physical pixels: 18 GUI pixels square. */
    static void draw(GuiRenderer r, SmGuiTheme t, double sx, double sy, int u, boolean hot) {
        double s = SLOT * u;
        Paint.rect(r, t, sx, sy, s, s, FILL);
        Paint.bevel(r, t, sx, sy, s, s, u, DARK, LIGHT);
        if (hot) Paint.rect(r, t, sx + u, sy + u, s - 2 * u, s - 2 * u, 0x80FFFFFF);
    }

    /** The lines of a hover card: what the cell says, and the item's id in grey (unless the text already has it). */
    static List<String> cardLines(String tip, ItemStack stack) {
        List<String> lines = new ArrayList<>(2);
        String text = tip == null ? "" : tip;
        if (!text.isEmpty()) lines.add(text);
        if (stack != null && !stack.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!text.contains(id)) lines.add(id);
        }
        return lines;
    }

    static void card(SmGuiTheme t, String tip, ItemStack stack, double sx, double sy, double slot) {
        WOverlayLayer overlay = t.overlay;
        if (overlay == null) return;
        List<String> lines = cardLines(tip, stack);
        if (!lines.isEmpty()) overlay.hoverCard(lines, sx, sy, slot, slot);
    }
}
