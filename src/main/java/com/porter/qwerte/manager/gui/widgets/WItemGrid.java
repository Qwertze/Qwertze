package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.gui.frame.Buttons;
import com.porter.qwerte.manager.gui.layout.GridLayout;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;



/**
 * A scrolling grid of item slots drawn by one widget: only the visible rows cost anything. It looks like the website's
 * inventory grid: vanilla slots (18 GUI pixels) centred in a tile, small counts in the corner of each slot, durability
 * bars, a white wash under the pointer and an accent outline on the selected slot. The number of columns and rows
 * follows the room it is given ({@link #setViewport}), and it scrolls by whole rows.
 *
 * <p>Left click selects, right click is the secondary action; both go to {@link #onCell}. Only Meteor's
 * version-independent events are used (press, move, scroll): everything else is worked out from the pointer position
 * Meteor passes to {@code render}, so it works on every Minecraft target.
 */
public class WItemGrid extends WPressable {
    /** One slot. {@code count} is the text to draw on it ("" for none); {@code ref} is what the owner wants back. */
    public record Cell(ItemStack stack, String count, String tip, Object ref) {
    }

    /** Padding between the tile's edge and the slots, in GUI pixels (the tile's border and one more). */
    private static final int PAD = 2;
    /** Room kept at the right for the scroll bar of a grid that fills its space (the bar is 4 wide, with room to grab it). */
    private static final int BAR = 7;
    private static final int BAR_DRAWN = 4;

    private final Supplier<GuiGraphics> graphics;
    private List<Cell> cells = List.of();
    private int scrollRow;
    private int hover = -1;
    private int selected = -1;
    private Object selectedRef;
    private int cols = 9;
    private int rows = 6;
    private int viewW;
    private int viewH;
    private int fixedCols;
    private int fixedRows;
    private double lastX;
    private double lastY;
    private boolean dragBar;
    private boolean dragChecked;

    /** Cells this says yes to get a red outline (the Kit Builder's slot whose item is not in the stash index any more). */
    public java.util.function.Predicate<Cell> red;

    /** Called with the cell under the mouse and the mouse button (0 left, 1 right). */
    public BiConsumer<Cell, Integer> onCell;

    /** Builds the hover card of a cell (null lines: the cell's own tip). Called once when the hovered cell changes. */
    public java.util.function.Function<Cell, List<String>> tipFor;
    private Cell tipCell;
    private List<String> tipLines;

    public WItemGrid(Supplier<GuiGraphics> graphics) {
        this.graphics = graphics;
    }

    /** A smaller grid of a fixed size (for a side panel); it doesn't scroll. */
    public WItemGrid compact(int cols, int rows) {
        this.fixedCols = cols;
        this.fixedRows = rows;
        return this;
    }

    /** The room the grid has, in GUI pixels; the columns and rows are worked out from it. */
    public void setViewport(int wGui, int hGui) {
        this.viewW = Math.max(0, wGui);
        this.viewH = Math.max(0, hGui);
    }

    private int u() {
        return SmGuiTheme.unitOf(theme);
    }

    /** Every grid keeps room for its scroll bar, a compact one too (the Orders picker holds thousands of items). */
    private int reserve() {
        return BAR;
    }

    @Override
    protected void onCalculateSize() {
        int u = u();
        int wGui;
        int hGui;
        int newCols;
        int newRows;
        if (fixedCols > 0) {
            newCols = fixedCols;
            newRows = fixedRows;
            wGui = newCols * GridLayout.SLOT + 2 * PAD + reserve();
            hGui = newRows * GridLayout.SLOT + 2 * PAD;
        } else if (viewW > 0 && viewH > 0) {
            newCols = GridLayout.cols(viewW - reserve(), PAD);
            newRows = GridLayout.rows(viewH, PAD);
            wGui = viewW;
            hGui = viewH;
        } else {
            newCols = 9;
            newRows = 6;
            wGui = newCols * GridLayout.SLOT + 2 * PAD + reserve();
            hGui = newRows * GridLayout.SLOT + 2 * PAD;
        }
        if (newCols != cols) scrollRow = GridLayout.scrollAfterReflow(cols, scrollRow, newCols, cells.size(), newRows);
        cols = newCols;
        rows = newRows;
        scrollRow = GridLayout.clampScroll(scrollRow, cells.size(), cols, rows);
        width = wGui * u;
        height = hGui * u;
    }

    public void setCells(List<Cell> cells) {
        this.cells = cells;
        hover = -1;
        selected = -1;
        if (selectedRef != null) {
            for (int i = 0; i < cells.size(); i++) {
                if (Objects.equals(cells.get(i).ref(), selectedRef)) {
                    selected = i;
                    break;
                }
            }
        }
        scrollRow = GridLayout.clampScroll(scrollRow, cells.size(), cols, rows);
    }

    public void select(Object ref) {
        selectedRef = ref;
        selected = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (Objects.equals(cells.get(i).ref(), ref)) {
                selected = i;
                break;
            }
        }
    }

    public void resetScroll() {
        scrollRow = 0;
    }

    private int maxScroll() {
        return GridLayout.maxScroll(cells.size(), cols, rows);
    }

    private int offX() {
        return GridLayout.offsetX((int) Math.round(width / u()) - reserve(), PAD, cols);
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        lastX = mouseX;
        lastY = mouseY;
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (!mouseOver || maxScroll() == 0) return false;
        scrollRow = GridLayout.clampScroll(scrollRow - (int) Math.signum(amount) * 2, cells.size(), cols, rows);
        hover = -1;
        return true;
    }

    @Override
    protected void onPressed(int button) {
        if (dragBar) return;
        if (hover >= 0 && hover < cells.size() && onCell != null) onCell.accept(cells.get(hover), button);
    }

    // ---------- the scroll bar ----------

    /** Left edge of the drawn bar; the grab zone starts a GUI pixel before it and runs to the grid's right edge. */
    private double trackX(int u, Palette p) {
        return x + width - (p.borderWidth + BAR_DRAWN) * u;
    }

    private double trackY(int u) {
        return y + PAD * u;
    }

    private double trackH(int u) {
        return height - 2 * PAD * u;
    }

    private double thumbH(int u) {
        return GridLayout.thumbH(trackH(u), rows, GridLayout.totalRows(cells.size(), cols), 6 * u);
    }

    private double thumbY(int u) {
        return GridLayout.thumbY(trackY(u), trackH(u), thumbH(u), scrollRow, maxScroll());
    }

    private boolean inBar(double px, double py, int u, Palette p) {
        return GridLayout.inBar(px, py, trackX(u, p) - u, x + width, trackY(u), trackH(u));
    }

    // ---------- the area a drawer lies over ----------

    private double[] cover;

    /**
     * A panel drawn over part of the grid (the details drawer): the grid doesn't react to the pointer there and draws no
     * icons under it (icons would show through, they are drawn above everything Meteor draws). Physical pixels; null for none.
     */
    public void setCover(double[] rect) {
        cover = rect;
        if (rect != null && covered(lastX, lastY)) mouseOver = false;
    }

    private boolean covered(double px, double py) {
        return cover != null && px >= cover[0] && px < cover[0] + cover[2] && py >= cover[1] && py < cover[1] + cover[3];
    }

    @Override
    public boolean isOver(double mx, double my) {
        return !covered(mx, my) && super.isOver(mx, my);
    }

    // ---------- drawing ----------

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        int u = t.unit();
        lastX = mouseX;
        lastY = mouseY;
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, p.borderWidth * u, p.surface2(), p.border());

        // The scroll bar thumb can be dragged: a press that began on it follows the pointer until it is let go
        // A release outside the window never reaches this widget: stop following the pointer when the button is up
        if (dragBar && !Buttons.leftDown()) {
            pressed = false;
            dragBar = false;
            dragChecked = false;
        }
        if (pressed) {
            if (!dragChecked) {
                dragChecked = true;
                dragBar = maxScroll() > 0 && inBar(mouseX, mouseY, u, p);
            }
            if (dragBar) {
                scrollRow = GridLayout.clampScroll(GridLayout.scrollFromPointer(mouseY, trackY(u), trackH(u), thumbH(u), maxScroll()), cells.size(), cols, rows);
            }
        } else {
            dragChecked = false;
            dragBar = false;
        }

        boolean blocked = t.overlay != null && t.overlay.isOpen();
        hover = -1;
        if (!blocked && !dragBar && isOver(mouseX, mouseY)) {
            hover = GridLayout.hit(cells.size(), cols, rows, scrollRow, offX(), PAD, (mouseX - x) / u, (mouseY - y) / u);
        }
        if (hover >= 0) {
            Cell c = cells.get(hover);
            int hr = hover / cols - scrollRow;
            int hc = hover % cols;
            double cx = x + (offX() + hc * GridLayout.SLOT) * u;
            double cy = y + (PAD + hr * GridLayout.SLOT) * u;
            if (tipFor != null && c.ref() != null && c != tipCell) {
                tipCell = c;
                tipLines = tipFor.apply(c);
            }
            if (tipFor != null && c == tipCell && tipLines != null && !tipLines.isEmpty()) {
                SlotArt.card(t, tipLines, cx, cy, GridLayout.SLOT * u);
            } else {
                SlotArt.card(t, c.tip(), c.stack(), cx, cy, GridLayout.SLOT * u);
            }
        }

        GuiGraphics g = graphics.get();
        int offX = offX();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (scrollRow + r) * cols + c;
                if (index >= cells.size()) break;
                double sx = x + (offX + c * GridLayout.SLOT) * u;
                double sy = y + (PAD + r * GridLayout.SLOT) * u;
                SlotArt.draw(renderer, t, sx, sy, u, index == hover);
                if (index == selected) Paint.outline(renderer, t, sx, sy, GridLayout.SLOT * u, GridLayout.SLOT * u, u, p.accent());
                Cell cell = cells.get(index);
                if (red != null && red.test(cell)) Paint.outline(renderer, t, sx, sy, GridLayout.SLOT * u, GridLayout.SLOT * u, u, 0xFFE04040);
                boolean under = cover != null && sx < cover[0] + cover[2] && sx + GridLayout.SLOT * u > cover[0]
                    && sy < cover[1] + cover[3] && sy + GridLayout.SLOT * u > cover[1];
                if (!under) ItemLayer.draw(renderer, t, g, cell.stack(), cell.count(), sx + u, sy + u);
            }
        }

        if (maxScroll() > 0) {
            double bx = trackX(u, p);
            Paint.rect(renderer, t, bx, trackY(u), BAR_DRAWN * u, trackH(u), Palette.withAlpha(p.trackOff(), 90));
            boolean overBar = isOver(mouseX, mouseY) && inBar(mouseX, mouseY, u, p);
            int thumb = dragBar ? p.accent() : Palette.mix(p.trackOff(), p.text(), overBar ? 0.6 : 0.35);
            Paint.rect(renderer, t, bx, thumbY(u), BAR_DRAWN * u, thumbH(u), thumb);
        }
    }
}
