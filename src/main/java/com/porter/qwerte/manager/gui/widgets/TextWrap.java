package com.porter.qwerte.manager.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Greedy word wrap for text measured by a function (the GUI's text width). The break point of a row is found by binary
 * search, so a line of n characters costs about (n / row width) * log n measurements, not n squared. Pure.
 *
 * <p>Formatting codes (the section sign and one character: colours, bold, italic...) are kept right: a row never ends on a lone
 * section sign, a code is never split from its letter, and the codes that are active where a row ends are put at the start of the
 * next one, so a bold name stays bold on every row. Measurements include those carried codes. A limit that is tiny (or not a
 * number) becomes the width of one wide glyph, so text always wraps and nothing is lost.
 */
public final class TextWrap {
    private static final char CODE = '§';

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
        double glyph = width.applyAsDouble("W");
        double lim = Double.isNaN(limit) ? glyph : Math.max(limit, glyph);
        int start = 0;
        int guard = 0;
        String active = "";
        while (start < text.length() && guard++ < 5000) {
            String rest = active + text.substring(start);
            if (width.applyAsDouble(rest) <= lim) {
                rows.add(rest);
                start = text.length();
                break;
            }
            // the longest end such that text[start, end) fits with the carried codes (at least one character)
            int lo = start + 1;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (width.applyAsDouble(active + text.substring(start, mid)) <= lim) lo = mid;
                else hi = mid - 1;
            }
            int end = lo;
            // never split a surrogate pair (an emoji) between two rows
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end = end > start + 1 ? end - 1 : Math.min(text.length(), end + 1);
            // never end on a lone section sign, nor split a code from its letter
            if (end < text.length() && text.charAt(end - 1) == CODE) end = end > start + 1 ? end - 1 : Math.min(text.length(), end + 1);
            int space = text.lastIndexOf(' ', end - 1);
            if (space > start && end < text.length()) end = space + 1;
            String piece = text.substring(start, end).stripTrailing();
            rows.add(active + piece);
            active = carry(active, piece);
            start = end;
        }
        // the guard tripped on a pathological input: keep the rest rather than lose it
        if (start < text.length()) rows.add(active + text.substring(start));
        return rows;
    }

    /** The formatting codes in force after {@code piece}, given the ones in force before it (as a string of codes). */
    static String carry(String before, String piece) {
        char color = 0;
        boolean bold = false, italic = false, strike = false, underline = false, obfuscated = false;
        String all = before + piece;
        for (int i = 0; i + 1 < all.length(); i++) {
            if (all.charAt(i) != CODE) continue;
            char c = Character.toLowerCase(all.charAt(i + 1));
            i++;
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                color = c;
                bold = italic = strike = underline = obfuscated = false; // a colour code resets the formats, as in the game
            } else if (c == 'r') {
                color = 0;
                bold = italic = strike = underline = obfuscated = false;
            } else if (c == 'l') bold = true;
            else if (c == 'o') italic = true;
            else if (c == 'm') strike = true;
            else if (c == 'n') underline = true;
            else if (c == 'k') obfuscated = true;
        }
        StringBuilder sb = new StringBuilder();
        if (color != 0) sb.append(CODE).append(color);
        if (bold) sb.append(CODE).append('l');
        if (italic) sb.append(CODE).append('o');
        if (strike) sb.append(CODE).append('m');
        if (underline) sb.append(CODE).append('n');
        if (obfuscated) sb.append(CODE).append('k');
        return sb.toString();
    }
}
