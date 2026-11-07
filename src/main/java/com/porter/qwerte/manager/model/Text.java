package com.porter.qwerte.manager.model;

import java.util.Locale;

/**
 * Text helpers for strings from the Stash Manager server, which are untrusted: an item or player can carry any name.
 * Nothing here touches Minecraft.
 */
public final class Text {
    private Text() {
    }

    /**
     * Makes a string safe to draw: no section signs (the game's formatting code, which it renders even in a literal
     * component), no control characters, no bidirectional controls (U+202A-202E, U+2066-2069), and no format (except the zero width
     * joiner) or private-use characters or unpaired surrogates. Cut to {@code max} characters, never in the middle of a surrogate pair.
     */
    public static String sanitize(String s, int max) {
        if (s == null || max <= 0) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            if (c == 0xa7) continue;
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
                if (c == 0x0a || c == 0x09) out.append(' ');
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, s.charAt(i + 1));
                    i++;
                    if (hidden(cp)) continue;
                    if (out.length() + 2 > max) break;
                    out.appendCodePoint(cp);
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            if (hidden(c)) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean hidden(int cp) {
        if ((cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069)) return true;
        // The zero width joiner stays: emoji sequences and some scripts need it. Variation selectors are not format characters.
        if (cp == 0x200d) return false;
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    /** 1.2k, 3.4M, 5B like the website's item counts. */
    public static String compact(long n) {
        if (n < 1000) return Long.toString(Math.max(0, n));
        if (n < 9_950) return trimZero(String.format(Locale.ROOT, "%.1f", n / 1e3)) + "k";
        if (n < 999_500) return Math.round(n / 1e3) + "k";
        if (n < 9_950_000) return trimZero(String.format(Locale.ROOT, "%.1f", n / 1e6)) + "M";
        if (n < 999_500_000) return Math.round(n / 1e6) + "M";
        return trimZero(String.format(Locale.ROOT, "%.1f", n / 1e9)) + "B";
    }

    private static String trimZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** 1,234,567 */
    public static String grouped(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** "just now", "5 s ago", "3 min ago", "2 h ago", "4 d ago". */
    public static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 5) return "just now";
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    /** "wrong username or password" becomes "Wrong username or password." */
    public static String sentence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        String first = t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
        char last = first.charAt(first.length() - 1);
        return last == '.' || last == '!' || last == '?' ? first : first + ".";
    }
}
