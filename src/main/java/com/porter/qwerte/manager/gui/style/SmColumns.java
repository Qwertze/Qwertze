package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * Side-by-side columns that share the width by weight, like a CSS grid with 1fr 1fr. Add the columns with add(...), in
 * order; set the weights with weights(...). Set maxWidth (physical pixels, from PageRoom.width) before the layout runs, so
 * the columns are the room wide instead of their natural width. Each column widget gets its share of the width and the
 * tallest one sets the height.
 */
public class SmColumns extends WContainer {
    /** Space between columns, in Meteor pixels. */
    public double gap = 10;
    public double maxWidth;
    /** Every column widget is as tall as the tallest one (a row of cards of equal height). */
    public boolean stretch;
    private double[] weights = new double[0];

    /** Relative widths, one per column: weights(1, 1) is two equal columns, weights(3, 2) is 60/40. */
    public SmColumns weights(double... w) {
        weights = w.clone();
        return this;
    }

    private double weight(int i) {
        return i < weights.length && weights[i] > 0 ? weights[i] : 1;
    }

    @Override
    protected void onCalculateSize() {
        double g = theme.scale(gap);
        double natural = 0;
        double h = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell<?> c = cells.get(i);
            natural += c.padLeft() + c.widget().width + c.padRight() + (i > 0 ? g : 0);
            h = Math.max(h, c.padTop() + c.widget().height + c.padBottom());
        }
        width = maxWidth > 0 ? maxWidth : natural;
        height = h;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double g = theme.scale(gap);
        int n = cells.size();
        if (n == 0) return;
        double total = 0;
        for (int i = 0; i < n; i++) total += weight(i);
        double room = width - g * (n - 1);
        double xx = x;
        for (int i = 0; i < n; i++) {
            Cell<?> c = cells.get(i);
            double w = room * weight(i) / total;
            c.x = xx + c.padLeft();
            c.y = y + c.padTop();
            c.width = w - c.padLeft() - c.padRight();
            c.height = c.widget().height;
            c.widget().x = c.x;
            c.widget().y = c.y;
            c.widget().width = c.width;
            if (stretch) c.widget().height = height - c.padTop() - c.padBottom();
            xx += w + g;
        }
    }
}
