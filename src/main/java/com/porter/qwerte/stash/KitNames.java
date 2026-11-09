package com.porter.qwerte.stash;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kit names (KIT-CONTRACT.md, "Kit names"): the one place that turns a shulker's custom name into a key and says whether it is a
 * kit. The server, the plugin and this addon use the same rules and the same test vectors. Pure; any thread. (Not the KitBuilder
 * kits of {@link Kits}: those are unnamed shulkers built from a recipe.)
 */
public final class KitNames {
    private KitNames() {
    }

    /** The longest key, in UTF-16 units. */
    public static final int MAX_KEY = 64;

    private static final Pattern KIT = Pattern.compile("(?<![\\p{L}\\p{M}])kits?(?![\\p{L}\\p{M}])");

    /**
     * The plain custom-name text as a key, or null when nothing is left of it (not a kit). Steps: drop each section sign and the
     * code point after it; tab, LF and CR to a space and drop control and format characters; any whitespace or space separator to a
     * space; lowercase (ROOT); collapse spaces and trim; cut to 64 units without leaving half a surrogate pair. No NFKC.
     */
    public static String normalize(String name) {
        if (name == null || name.isEmpty()) return null;

        // 1: a section sign and the code point after it (a trailing one alone as well)
        StringBuilder stripped = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                if (i < name.length()) i += Character.charCount(name.codePointAt(i));
                continue;
            }
            stripped.appendCodePoint(cp);
        }

        // 2 and 3
        StringBuilder spaced = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r') cp = ' ';
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) cp = ' ';
            spaced.appendCodePoint(cp);
        }

        // 4 and 5
        String text = collapse(spaced.toString().toLowerCase(Locale.ROOT));

        // 6
        if (text.length() > MAX_KEY) {
            int end = MAX_KEY;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = collapse(text.substring(0, end));
        }
        return text.isEmpty() ? null : text;
    }

    /** Runs of spaces become one and the ends are trimmed (spaces only). */
    private static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (!space) out.append(' ');
                space = true;
            }
            else {
                out.append(c);
                space = false;
            }
        }
        int n = out.length();
        if (n > 0 && out.charAt(n - 1) == ' ') out.setLength(n - 1);
        return out.toString();
    }

    /** Whether a normalised name has the word kit or kits in it (a boundary is anything that is not a letter or a combining mark). */
    public static boolean isKit(String normalized) {
        return normalized != null && KIT.matcher(normalized).find();
    }

    /** A key as the server stores it: already normal (any name, it no longer needs the word kit) and with no unpaired surrogate. */
    public static boolean validKey(String key) {
        return key != null && key.equals(normalize(key)) && usable(key);
    }

    /** Whether a normalised name can be a key: not null and with no unpaired surrogate (a name with one counts as unnamed). */
    public static boolean usable(String normalized) {
        if (normalized == null) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= normalized.length() || !Character.isLowSurrogate(normalized.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** The key of a custom name: its normal form, or null when it has none or cannot be a key (unnamed). */
    public static String keyOf(String name) {
        String n = normalize(name);
        return usable(n) ? n : null;
    }
}
