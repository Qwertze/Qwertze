package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.manager.gui.frame.WFrame;
import com.porter.qwerte.manager.gui.widgets.WTextLog;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * A page that fills the window's page area: the widgets added before {@link #addFill} stack at the top, the fill widget takes
 * what is left, and the widgets added after it are pinned at the bottom (the Console's input row). It reads the room from the
 * window frame every time it is laid out, so a resized window needs no rebuild. A text log as the fill widget gets as many
 * rows as fit.
 */
public class SmFillPage extends WContainer {
    private int fillIndex = -1;

    public <T extends WWidget> Cell<T> addFill(T widget) {
        Cell<T> c = add(widget);
        fillIndex = cells.size() - 1;
        return c;
    }

    @Override
    public void clear() {
        fillIndex = -1;
        super.clear();
    }

    private WFrame frame() {
        for (WWidget w = parent; w != null; w = w.parent) if (w instanceof WFrame f) return f;
        return null;
    }

    private double heightOf(Cell<?> c) {
        return c.padTop() + c.widget().height + c.padBottom();
    }

    @Override
    protected void onCalculateSize() {
        WFrame f = frame();
        double gap = theme.scale(6);
        double room = f == null ? 0 : f.pageH - theme.scale(4);
        double used = 0;
        int n = cells.size();
        for (int i = 0; i < n; i++) {
            if (i == fillIndex) continue;
            used += heightOf(cells.get(i)) + gap;
        }
        double fillH = 0;
        if (fillIndex >= 0) {
            Cell<?> fc = cells.get(fillIndex);
            fillH = Math.max(theme.scale(80), room - used);
            if (fc.widget() instanceof WTextLog log) {
                int rows = Math.max(3, (int) ((fillH - theme.scale(6)) / log.rowHeightPx()));
                log.setRows(rows);
                fillH = rows * log.rowHeightPx() + theme.scale(6);
            }
            fc.widget().height = fillH;
            used += fillH;
        } else if (n > 0) {
            used -= gap;
        }
        width = f == null ? 0 : f.pageW;
        for (Cell<?> c : cells) if (f == null) width = Math.max(width, c.padLeft() + c.widget().width + c.padRight());
        height = used;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double gap = theme.scale(6);
        double yy = y;
        double bottom = y + height;
        int n = cells.size();
        // the pinned widgets, from the bottom up
        for (int i = n - 1; fillIndex >= 0 && i > fillIndex; i--) {
            Cell<?> c = cells.get(i);
            bottom -= heightOf(c);
            place(c, bottom);
            bottom -= gap;
        }
        int end = fillIndex >= 0 ? fillIndex + 1 : n;
        for (int i = 0; i < end; i++) {
            Cell<?> c = cells.get(i);
            place(c, yy);
            yy += heightOf(c) + gap;
        }
    }

    private void place(Cell<?> c, double top) {
        c.x = x + c.padLeft();
        c.y = top + c.padTop();
        c.width = width - c.padLeft() - c.padRight();
        c.height = c.widget().height;
        c.widget().x = c.x;
        c.widget().y = c.y;
        c.widget().width = c.width;
    }
}
