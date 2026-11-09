package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * The website table (a header row of column names, then rows with a thin line between them). Make it with the column
 * weights, e.g. new SmTable("Stash", "Bot", "Release") with weights(3, 2, 1); name the columns with the constructor,
 * add each row with addRow(widgets...) (exactly one widget per column; use a label for text). Set maxWidth (physical
 * pixels, from PageRoom.width) before the layout runs so the table is the room wide. A column listed in rightAlign(...)
 * puts its widgets at the right edge of the column (the Release buttons). Rebuild with clear() then addRow again.
 */
public class SmTable extends WContainer {
    private final String[] names;
    private double[] weights;
    private boolean[] right;
    public double maxWidth;
    /** Space between columns and the padding of a row, in Meteor pixels. */
    public double gap = 10;
    public double rowPad = 3;

    private double[] colW;
    private double[] rowH;
    private double headerH;

    public SmTable(String... columnNames) {
        this.names = columnNames.clone();
        this.weights = new double[names.length];
        java.util.Arrays.fill(weights, 1);
        this.right = new boolean[names.length];
    }

    public SmTable weights(double... w) {
        for (int i = 0; i < weights.length && i < w.length; i++) weights[i] = w[i] > 0 ? w[i] : 1;
        return this;
    }

    public SmTable rightAlign(int... columns) {
        for (int c : columns) if (c >= 0 && c < right.length) right[c] = true;
        return this;
    }

    /** Adds one row: one widget per column (extra ones are dropped, missing ones left empty). */
    public void addRow(WWidget... widgets) {
        for (int i = 0; i < names.length; i++) {
            if (i < widgets.length && widgets[i] != null) add(widgets[i]);
            else add(new SmLabel("", false, true));
        }
    }

    public int rows() {
        return names.length == 0 ? 0 : cells.size() / names.length;
    }

    private int cols() {
        return names.length;
    }

    @Override
    protected void onCalculateSize() {
        int n = cols();
        double g = theme.scale(gap);
        double rp = theme.scale(rowPad);
        colW = new double[n];
        int rowCount = rows();
        rowH = new double[rowCount];
        SmGuiTheme t = (SmGuiTheme) theme;
        headerH = t.smallHeight() + 2 * rp;
        for (int c = 0; c < n; c++) colW[c] = t.smallWidth(names[c]);
        for (int i = 0; i < rowCount * n; i++) {
            Cell<?> cell = cells.get(i);
            int c = i % n;
            colW[c] = Math.max(colW[c], cell.padLeft() + cell.widget().width + cell.padRight());
            rowH[i / n] = Math.max(rowH[i / n], cell.padTop() + cell.widget().height + cell.padBottom());
        }
        double natural = g * Math.max(0, n - 1);
        double h = headerH;
        for (double w : colW) natural += w;
        for (int r = 0; r < rowCount; r++) {
            rowH[r] += 2 * rp;
            h += rowH[r];
        }
        width = maxWidth > 0 ? Math.max(maxWidth, natural) : natural;
        height = h;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        int n = cols();
        if (n == 0 || colW == null) return;
        double g = theme.scale(gap);
        double rp = theme.scale(rowPad);
        double room = width - g * (n - 1);
        double natural = 0;
        double wsum = 0;
        for (int c = 0; c < n; c++) {
            natural += colW[c];
            wsum += weights[c];
        }
        double extra = Math.max(0, room - natural);
        double[] w = new double[n];
        for (int c = 0; c < n; c++) w[c] = colW[c] + extra * weights[c] / wsum;
        double yy = y + headerH;
        for (int r = 0; r < rows(); r++) {
            double xx = x;
            for (int c = 0; c < n; c++) {
                Cell<?> cell = cells.get(r * n + c);
                WWidget widget = cell.widget();
                cell.x = xx + cell.padLeft();
                cell.y = yy + rp + cell.padTop();
                cell.width = w[c] - cell.padLeft() - cell.padRight();
                cell.height = rowH[r] - 2 * rp - cell.padTop() - cell.padBottom();
                double ww = widget.width;
                widget.x = right[c] ? cell.x + cell.width - ww : cell.x;
                widget.y = cell.y + (cell.height - widget.height) / 2;
                xx += w[c] + g;
            }
            yy += rowH[r];
        }
        colW = w;
        // colW now holds the final widths; the header drawing uses them. The next size pass recomputes the natural ones.
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t) || colW == null || colW.length != cols()) return;
        Palette p = t.palette();
        double u = t.unit();
        double g = theme.scale(gap);
        double rp = theme.scale(rowPad);
        double xx = x;
        for (int c = 0; c < cols(); c++) {
            double tx = right[c] ? xx + colW[c] - t.smallWidth(names[c]) : xx;
            renderer.text(names[c], tx, y + rp, t.color(p.muted()), true);
            xx += colW[c] + g;
        }
        double yy = y + headerH;
        Paint.rect(renderer, t, x, yy - u, width, u, p.border());
        for (int r = 0; r < rowH.length - 1; r++) {
            yy += rowH[r];
            Paint.rect(renderer, t, x, yy - u, width, u, Palette.withAlpha(p.border(), 110));
        }
    }
}
