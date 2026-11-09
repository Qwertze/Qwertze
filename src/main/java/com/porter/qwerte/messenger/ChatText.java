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

    /** Text of a message: cleaned, trimmed, at most {@link #MAX_TEXT} characters. */
    public static String message(String s) {
        return clean(s, MAX_TEXT).strip();
    }
}
