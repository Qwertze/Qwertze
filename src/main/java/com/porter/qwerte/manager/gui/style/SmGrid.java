package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * Equal-width cells in rows, like a CSS grid with repeat(n, 1fr). Fixed {@link #columns}, or as many as fit when
 * {@link #minCell} (Meteor pixels) is set. Set maxWidth (physical pixels, from PageRoom) before the layout runs; cells are
 * stretched to the column width and a row is as tall as its tallest cell.
 */
public class SmGrid extends WContainer {
    public int columns = 2;
    public double minCell;
    public double gapX = 8;
    public double gapY = 8;
    public double maxWidth;

    private int cols = 1;
    private double colW;
    private double[] rowH = new double[0];

    private int columnsFor(double width, double gx) {
        if (minCell > 0 && width > 0) return Math.max(1, (int) ((width + gx) / (theme.scale(minCell) + gx)));
        return Math.max(1, columns);
    }

    /** The width one cell will get at {@link #maxWidth}, for callers that wrap text to a cell. */
    public double columnWidth() {
        double gx = theme.scale(gapX);
        int c = columnsFor(maxWidth, gx);
        return (maxWidth - gx * (c - 1)) / c;
    }

    @Override
    protected void onCalculateSize() {
        double gx = theme.scale(gapX);
        double gy = theme.scale(gapY);
        int n = cells.size();
        double natural = 0;
        for (Cell<?> c : cells) natural = Math.max(natural, c.padLeft() + c.widget().width + c.padRight());
        cols = columnsFor(maxWidth, gx);
        double w = maxWidth > 0 ? maxWidth : natural * cols + gx * (cols - 1);
        colW = (w - gx * (cols - 1)) / cols;
        int rows = (n + cols - 1) / cols;
        if (rowH.length != rows) rowH = new double[rows];
        java.util.Arrays.fill(rowH, 0);
        for (int i = 0; i < n; i++) {
            Cell<?> c = cells.get(i);
            rowH[i / cols] = Math.max(rowH[i / cols], c.padTop() + c.widget().height + c.padBottom());
        }
        double h = rows > 0 ? gy * (rows - 1) : 0;
        for (double r : rowH) h += r;
        width = w;
        height = h;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double gx = theme.scale(gapX);
        double gy = theme.scale(gapY);
        double yy = y;
        for (int r = 0; r < rowH.length; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= cells.size()) break;
                Cell<?> cell = cells.get(i);
                cell.x = x + c * (colW + gx) + cell.padLeft();
                cell.y = yy + cell.padTop();
                cell.width = colW - cell.padLeft() - cell.padRight();
                cell.height = rowH[r] - cell.padTop() - cell.padBottom();
                cell.widget().x = cell.x;
                cell.widget().y = cell.y;
                cell.widget().width = cell.width;
                cell.widget().height = cell.height;
            }
            yy += rowH[r] + gy;
        }
    }
}
