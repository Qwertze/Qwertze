package com.porter.qwerte.manager.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Greedy word wrap for text measured by a function (the GUI's text width). The break point of a row is found by binary
 * search, so a line of n characters costs about (n / row width) * log n measurements, not n squared. Pure.
 */
public final class TextWrap {
    private TextWrap() {
    }

    /**
     * @param limit the widest a row may be
     * @param width the width of a string; must not shrink when a string grows
     * @return the rows, never empty (an empty text is one empty row)
     */
    public static List<String> wrap(String text, double limit, ToDoubleFunction<String> width) {
        List<String> rows = new ArrayList<>();
        if (text.isEmpty()) {
            rows.add("");
            return rows;
        }
        int start = 0;
        int guard = 0;
        while (start < text.length() && guard++ < 2000) {
            if (width.applyAsDouble(text.substring(start)) <= limit) {
                rows.add(text.substring(start));
                break;
            }
            // the longest end such that text[start, end) fits (at least one character)
            int lo = start + 1;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (width.applyAsDouble(text.substring(start, mid)) <= limit) lo = mid;
                else hi = mid - 1;
            }
            int end = lo;
            // never split a surrogate pair (an emoji) between two rows
            if (end > start + 1 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            int space = text.lastIndexOf(' ', end - 1);
            if (space > start) end = space + 1;
            rows.add(text.substring(start, end).stripTrailing());
            start = end;
        }
        return rows;
    }
}
