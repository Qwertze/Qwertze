package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.layout.FlowLayout;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

import java.util.List;

/**
 * A row of widgets that wraps onto more rows when it is too narrow, like a CSS flex row with {@code flex-wrap}. Set
 * {@link #maxWidth} (physical pixels) before the layout runs; the flow is then exactly that wide.
 */
public class WFlow extends WContainer {
    /** Space between items, in Meteor pixels (scaled with the theme). */
    public double gapX = 10;
    public double gapY = 4;
    public double maxWidth;
    /** The last item sits at the right end of the row it is on (the website's margin-left: auto). */
    public boolean lastRight;

    private List<int[]> rows = List.of();
    private double[] rowHeights = new double[0];

    private double widthOf(Cell<?> cell) {
        return cell.padLeft() + cell.widget().width + cell.padRight();
    }

    private double heightOf(Cell<?> cell) {
        return cell.padTop() + cell.widget().height + cell.padBottom();
    }

    @Override
    protected void onCalculateSize() {
        double gx = theme.scale(gapX);
        double gy = theme.scale(gapY);
        double[] widths = new double[cells.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = widthOf(cells.get(i));
        double limit = maxWidth > 0 ? maxWidth : Double.MAX_VALUE;
        rows = FlowLayout.rows(widths, limit, gx);
        rowHeights = new double[rows.size()];
        double natural = 0;
        double h = 0;
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            double rh = 0;
            for (int i = row[0]; i < row[1]; i++) rh = Math.max(rh, heightOf(cells.get(i)));
            rowHeights[r] = rh;
            h += rh + (r > 0 ? gy : 0);
            natural = Math.max(natural, FlowLayout.rowWidth(widths, row[0], row[1], gx));
        }
        width = maxWidth > 0 ? maxWidth : natural;
        height = h;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double gx = theme.scale(gapX);
        double gy = theme.scale(gapY);
        double yy = y;
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            double xx = x;
            for (int i = row[0]; i < row[1]; i++) {
                Cell<?> cell = cells.get(i);
                cell.x = xx + cell.padLeft();
                cell.y = yy + cell.padTop();
                cell.width = cell.widget().width;
                cell.height = rowHeights[r] - cell.padTop() - cell.padBottom();
                cell.alignWidget();
                if (lastRight && i == cells.size() - 1) cell.x = Math.max(cell.x, x + width - cell.padRight() - cell.widget().width);
                cell.alignWidget();
                xx += widthOf(cell) + gx;
            }
            yy += rowHeights[r] + gy;
        }
    }
}
