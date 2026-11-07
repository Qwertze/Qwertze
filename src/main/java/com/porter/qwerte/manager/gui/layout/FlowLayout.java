package com.porter.qwerte.manager.gui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps items of known widths into rows, like a CSS flex row with flex-wrap. Pure.
 */
public final class FlowLayout {
    private FlowLayout() {
    }

    /**
     * The rows as {@code [from, to)} index pairs. The gap sits between items only; an item wider than the row gets a
     * row to itself; a zero-width item never forces a break.
     */
    public static List<int[]> rows(double[] widths, double maxWidth, double gap) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        double used = 0;
        for (int i = 0; i < widths.length; i++) {
            double w = Math.max(0, widths[i]);
            double needed = i == from ? w : used + gap + w;
            if (i > from && needed > maxWidth) {
                out.add(new int[] {from, i});
                from = i;
                used = w;
            } else {
                used = needed;
            }
        }
        if (from < widths.length) out.add(new int[] {from, widths.length});
        return out;
    }

    /** The width of the items {@code [from, to)} with the gap between them. */
    public static double rowWidth(double[] widths, int from, int to, double gap) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += Math.max(0, widths[i]);
        return sum + gap * Math.max(0, to - from - 1);
    }
}
