package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * A box of wrapped plain-text lines, newest at the bottom, scrolled with the wheel (the live viewer's chat, the activity
 * events, the console). Text is drawn by the GUI's text renderer as plain text: nothing in it is interpreted. The owner passes
 * cleaned text. The size is a number of rows and a width ({@link #columns} in Meteor pixels, or {@link #size}); a page that
 * fills the window sets {@link #setRows} when it lays out.
 */
public final class WTextLog extends WWidget {
    /** One logical line and its colour. */
    public record Line(String text, Color color) {
    }

    private record Row(String text, Color color) {
    }

    private static final int ROWS = 12;
    private static final int KEEP_LINES = 400;

    private List<Line> lines = List.of();
    private final List<Row> rows = new ArrayList<>();
    private double wrappedFor = -1;
    private boolean rewrap = true;
    private int fromBottom;
    private double columns;
    private double physWidth;
    private int visibleRows = ROWS;
    private final java.util.Map<String, List<String>> cache = new java.util.HashMap<>();

    /** @param columns the width in scaled pixels */
    public WTextLog(double columns) {
        this.columns = columns;
    }

    /** A box of {@code rowCount} rows and a width in physical pixels. */
    public WTextLog size(double physicalWidth, int rowCount) {
        this.physWidth = physicalWidth;
        this.visibleRows = Math.max(2, rowCount);
        return this;
    }

    /** Changes the number of rows (a page that fills the window); the layout is redone only when it differs. */
    public void setRows(int rowCount) {
        int n = Math.max(2, rowCount);
        if (n != visibleRows) {
            visibleRows = n;
            invalidate();
        }
    }

    public int rowsShown() {
        return visibleRows;
    }

    public void setLines(List<Line> lines) {
        this.lines = lines;
        rewrap = true;
    }

    /** Jumps to the newest line. The box otherwise stays where the user scrolled to; at the bottom it follows new lines. */
    public void scrollToEnd() {
        fromBottom = 0;
    }

    public double rowHeightPx() {
        return rowHeight();
    }

    @Override
    protected void onCalculateSize() {
        width = physWidth > 0 ? physWidth : theme.scale(columns);
        height = visibleRows * rowHeight() + theme.scale(6);
    }

    private double rowHeight() {
        return theme.textHeight() + theme.scale(2);
    }

    private void wrap() {
        rows.clear();
        double limit = Math.max(40, width - theme.scale(8) - theme.scale(8));
        if (wrappedFor != width) cache.clear();
        // Only the newest lines are laid out, and each distinct text is measured once
        for (Line l : lines.subList(Math.max(0, lines.size() - KEEP_LINES), lines.size())) {
            List<String> parts = cache.get(l.text());
            if (parts == null) {
                parts = TextWrap.wrap(l.text(), limit, theme::textWidth);
                if (cache.size() > 800) cache.clear();
                cache.put(l.text(), parts);
            }
            for (String p : parts) rows.add(new Row(p, l.color()));
        }
        wrappedFor = width;
        rewrap = false;
        fromBottom = Math.min(fromBottom, Math.max(0, rows.size() - visibleRows));
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (!mouseOver) return false;
        fromBottom = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), fromBottom + (int) Math.signum(amount) * 2));
        return true;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (rewrap || wrappedFor != width) wrap();
        if (theme instanceof SmGuiTheme t) {
            Palette p = t.palette();
            Paint.panel(renderer, t, x, y, width, height, p.radius * t.unit(), p.borderWidth * t.unit(), p.surface2(), p.border());
        } else {
            renderer.quad(x, y, width, height, new Color(20, 20, 24, 235));
        }
        double rh = rowHeight();
        int last = rows.size() - fromBottom; // exclusive
        int first = Math.max(0, last - visibleRows);
        double ty = y + theme.scale(3);
        for (int i = first; i < last; i++) {
            Row r = rows.get(i);
            if (!r.text().isEmpty()) renderer.text(r.text(), x + theme.scale(6), ty, r.color(), false);
            ty += rh;
        }
    }
}
