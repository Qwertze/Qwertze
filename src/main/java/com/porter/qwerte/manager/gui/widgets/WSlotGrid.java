package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Nine slots per row, a fixed number of them (27 for a shulker box, 54 for a double chest), drawn like the item grid.
 * Slots can be marked (what was already there, what does not fit, the selected one) and clicked. Only Meteor's
 * version-independent events are used.
 */
public class WSlotGrid extends WPressable {
    /** How a slot is drawn on top of its item. */
    public enum Mark { NONE, EXISTING, OVERFLOW, OUTSIDE, SELECTED }

    private static final int PAD = 2;
    private static final int COLS = 9;
    private static final int DIM = 0x96101018;
    private static final int STRIPE = 0x46969AA0;
    private static final int WARN = 0xFFFFBE50;

    private final Supplier<GuiGraphics> graphics;
    private final int slots;
    private List<WItemGrid.Cell> cells;
    private Mark[] marks;
    private int hover = -1;

    /** Called with the slot index and the mouse button (0 left, 1 right). */
    public BiConsumer<Integer, Integer> onSlot;

    /** {@code cells} has one entry per slot; null for an empty one. */
    public WSlotGrid(Supplier<GuiGraphics> graphics, int slots) {
        this.graphics = graphics;
        this.slots = slots;
        this.cells = java.util.Collections.nCopies(slots, null);
        this.marks = new Mark[slots];
    }

    public void setCells(List<WItemGrid.Cell> cells) {
        this.cells = cells;
        hover = -1;
    }

    /** One mark per slot (null for none). */
    public void setMarks(Mark[] marks) {
        this.marks = marks == null ? new Mark[slots] : marks;
    }

    private int u() {
        return SmGuiTheme.unitOf(theme);
    }

    @Override
    protected void onCalculateSize() {
        int u = u();
        width = (COLS * SlotArt.SLOT + 2 * PAD) * u;
        height = (((slots + COLS - 1) / COLS) * SlotArt.SLOT + 2 * PAD) * u;
    }

    @Override
    protected void onPressed(int button) {
        if (hover >= 0 && onSlot != null) onSlot.accept(hover, button);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        int u = t.unit();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, p.borderWidth * u, p.surface2(), p.border());
        boolean blocked = t.overlay != null && t.overlay.isOpen();
        hover = -1;
        if (!blocked && isOver(mouseX, mouseY)) {
            int c = (int) Math.floor((mouseX - x) / u - PAD) / SlotArt.SLOT;
            int r = (int) Math.floor((mouseY - y) / u - PAD) / SlotArt.SLOT;
            double lx = (mouseX - x) / u - PAD;
            double ly = (mouseY - y) / u - PAD;
            int index = r * COLS + c;
            if (lx >= 0 && ly >= 0 && c < COLS && index >= 0 && index < slots && index < cells.size()) hover = index;
        }
        if (hover >= 0) {
            WItemGrid.Cell cd = cells.get(hover);
            if (cd != null) SlotArt.card(t, cd.tip(), cd.stack(), x + (PAD + (hover % COLS) * SlotArt.SLOT) * u, y + (PAD + (hover / COLS) * SlotArt.SLOT) * u, SlotArt.SLOT * u);
        }
        GuiGraphics g = graphics.get();
        double slot = SlotArt.SLOT * u;
        for (int i = 0; i < slots; i++) {
            double sx = x + (PAD + (i % COLS) * SlotArt.SLOT) * u;
            double sy = y + (PAD + (i / COLS) * SlotArt.SLOT) * u;
            SlotArt.draw(renderer, t, sx, sy, u, i == hover);
            WItemGrid.Cell cd = i < cells.size() ? cells.get(i) : null;
            if (cd != null) ItemLayer.draw(renderer, t, g, cd.stack(), cd.count(), sx + u, sy + u);
            Mark mark = i < marks.length && marks[i] != null ? marks[i] : Mark.NONE;
            switch (mark) {
                case EXISTING -> {
                    // Dimmed and hatched: already in the chest
                    Paint.rect(renderer, t, sx + u, sy + u, slot - 2 * u, slot - 2 * u, DIM);
                    for (double s = 4 * u; s < slot - 2 * u; s += 6 * u) Paint.rect(renderer, t, sx + u + s, sy + u, u, slot - 2 * u, STRIPE);
                }
                case OUTSIDE -> Paint.rect(renderer, t, sx + u, sy + u, slot - 2 * u, slot - 2 * u, DIM);
                case OVERFLOW -> Paint.outline(renderer, t, sx, sy, slot, slot, u, WARN);
                case SELECTED -> Paint.outline(renderer, t, sx, sy, slot, slot, u, p.accent());
                default -> {
                }
            }
        }
    }
}
