package com.porter.qwerte.messenger;

/**
 * Cleaning of text that comes from the chat (untrusted) or from the input line, and validation of player names.
 * Pure: no Minecraft classes.
 */
public final class ChatText {
    /** A chat line longer than this is not looked at (a whisper is at most 256 characters plus a short prefix). */
    public static final int MAX_LINE = 600;
    /** The longest message text kept. */
    public static final int MAX_TEXT = 512;

    private ChatText() {
    }

    /** A Minecraft player name: 3 to 16 characters of letters, digits and underscore. */
    public static boolean isValidName(String name) {
        if (name == null) return false;
        int n = name.length();
        if (n < 3 || n > 16) return false;
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Removes formatting codes (a section sign and the character after it), control characters, and cuts the text at
     * {@code max} characters (never inside a surrogate pair). Never returns null.
     */
    public static String clean(String s, int max) {
        if (s == null || max <= 0) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        int n = s.length();
        for (int i = 0; i < n && out.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // the code character goes too
                continue;
            }
            if (c == '\t') c = ' ';
            if (c < ' ' || c == 127 || (c >= 0x80 && c < 0xA0)) continue;
            if (c == '‎' || c == '‏' || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩')) continue; // direction marks and overrides
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    if (out.length() + 2 > max) break; // no room for the pair: stop here
                    out.append(c).append(s.charAt(++i));
                }
                continue; // (a lone half is dropped)
            }
            if (Character.isLowSurrogate(c)) continue; // half a pair
            out.append(c);
        }
        // Do not end on half a surrogate pair
        int len = out.length();
        if (len > 0 && Character.isHighSurrogate(out.charAt(len - 1))) out.setLength(len - 1);
        return out.toString();
    }

    /**
     * Removes what other client mods put in front of a line: whitespace, icon glyphs (the object character of chat
     * heads, private-use glyphs) and a timestamp like {@code <15:05>}, {@code [15:05]}, {@code [15:05:30]} or
     * {@code <3:05 PM>}, in any order and number. Linear time; never returns null.
     */
    public static String stripClientPrefix(String s) {
        if (s == null) return "";
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '￼' || (c >= '' && c <= '') || c == ' ') {
                i++;
                continue;
            }
            int end = timestampEnd(s, i);
            if (end < 0) break;
            i = end;
        }
        return i == 0 ? s : s.substring(i);
    }

    /** The index after a timestamp that starts at {@code i}, or -1. */
    private static int timestampEnd(String s, int i) {
        int n = s.length();
        char open = s.charAt(i);
        char close;
        if (open == '<') close = '>';
        else if (open == '[') close = ']';
        else return -1;
        int j = i + 1;
        int digits = 0;
        int colons = 0;
        while (j < n && j - i < 16) {
            char c = s.charAt(j);
            if (c >= '0' && c <= '9') digits++;
            else if (c == ':') colons++;
            else if (c == ' ' || c == 'A' || c == 'P' || c == 'M' || c == 'a' || c == 'p' || c == 'm' || c == '.') { /* am/pm */ }
            else break;
            j++;
        }
        if (j < n && s.charAt(j) == close && colons >= 1 && colons <= 2 && digits >= 3 && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '9') return j + 1;
        return -1;
    }

    /**
     * Text of a message: cleaned, trimmed, at most {@link #MAX_TEXT} characters. Icon glyphs that another mod put in it
     * (see {@link #stripGlyphs}) are removed, since the window draws them as a white box, and a name printed twice in a
     * row ("&lt;Steve&gt; &lt;Steve&gt; hi") is shown once.
     */
    public static String message(String s) {
        return collapseDoubleName(stripGlyphs(clean(s, MAX_TEXT))).strip();
    }

    /**
     * Removes the object replacement character (what a player-head component gives as text) and the private-use
     * characters of the basic plane (what chat-head mods and servers use for icons in a custom font). The default font
     * has no picture for them, so they would show as a white box.
     */
    public static String stripGlyphs(String s) {
        if (s == null) return "";
        int n = s.length();
        int i = 0;
        while (i < n && !isGlyph(s.charAt(i))) i++;
        if (i == n) return s;
        StringBuilder out = new StringBuilder(n);
        out.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (!isGlyph(c)) out.append(c);
        }
        return out.toString();
    }

    private static boolean isGlyph(char c) {
        return c == '￼' || (c >= '' && c <= '');
    }

    private static final java.util.regex.Pattern DOUBLE_NAME = java.util.regex.Pattern.compile("(<[^<>\\s][^<>]{1,40}>)\\s*\\1");

    /** "&lt;Steve&gt; &lt;Steve&gt; hi" to "&lt;Steve&gt; hi", when the repeat is at the start of the line (after a timestamp or icon at most). */
    static String collapseDoubleName(String s) {
        if (s == null || s.length() < 6 || s.indexOf('<') < 0) return s;
        java.util.regex.Matcher m = DOUBLE_NAME.matcher(s);
        if (m.find() && stripClientPrefix(s.substring(0, m.start())).isBlank()) return s.substring(0, m.start()) + m.group(1) + s.substring(m.end());
        return s;
    }
}
