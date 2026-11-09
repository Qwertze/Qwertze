package com.porter.stashmanager;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kit names and container ids (KIT-CONTRACT.md v1.1). The plugin and the addon run the same normalisation; the shared
 * test vectors are in KitNamesTest.
 */
final class KitNames {
    static final int MAX_KEY = 64;
    private static final Pattern KIT = Pattern.compile("(?<![\\p{L}\\p{M}])kits?(?![\\p{L}\\p{M}])");
    private static final Pattern ID = Pattern.compile("^-?[0-9]{1,8},-?[0-9]{1,4},-?[0-9]{1,8}$");

    private KitNames() {
    }

    /** The key for a shulker's plain custom name, or null (no name, or nothing left after cleaning). */
    static String normalize(String name) {
        if (name == null) return null;
        // 1. every section sign and the code point after it (a trailing lone one too).
        StringBuilder a = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\u00A7') {
                if (i < name.length()) i += Character.charCount(name.codePointAt(i));
                continue;
            }
            a.appendCodePoint(c);
        }
        // 2. tab, LF and CR to a space, then drop control (Cc) and format (Cf) code points.
        // 3. whitespace and space separators to a space.
        StringBuilder b = new StringBuilder(a.length());
        for (int i = 0; i < a.length(); ) {
            int c = a.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\t' || c == '\n' || c == '\r') c = ' ';
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) c = ' ';
            b.appendCodePoint(c);
        }
        // 4. lower case, 5. collapse runs of spaces and trim.
        String lower = b.toString().toLowerCase(Locale.ROOT);
        StringBuilder d = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == ' ') {
                space = true;
                continue;
            }
            if (space && !d.isEmpty()) d.append(' ');
            space = false;
            d.append(ch);
        }
        String s = d.toString();
        // 6. at most 64 UTF-16 units: cut, drop a trailing lone high surrogate, trim again.
        if (s.length() > MAX_KEY) {
            int end = MAX_KEY;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            s = s.substring(0, end).stripTrailing();
            // stripTrailing only removes whitespace; the only one left is the space.
        }
        // 7. nothing left: not a kit.
        return s.isEmpty() ? null : s;
    }

    /** Whether a normalised name has the word "kit" or "kits" (a boundary is any non-letter, non-mark). */
    static boolean isKit(String normalized) {
        return normalized != null && KIT.matcher(normalized).find();
    }

    /** A key a rule may use (KIT-CONTRACT.md v1.1): already normalised, no unpaired surrogates. It needs no "kit" in it. */
    static boolean validKey(String key) {
        return wellFormed(key) && key.equals(normalize(key));
    }

    /** Not null and no unpaired surrogate (one can't be written to JSON as UTF-8, and would damage kits.json). */
    static boolean wellFormed(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++;
            else if (Character.isSurrogate(c)) return false;
        }
        return true;
    }

    /** "x,y,z" with the contract's shape and ranges. */
    static boolean validId(String id) {
        if (id == null || !ID.matcher(id).matches()) return false;
        String[] p = id.split(",");
        long x = Long.parseLong(p[0]), y = Long.parseLong(p[1]), z = Long.parseLong(p[2]);
        return Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && y >= -2048 && y <= 2048;
    }

    /** Orders container ids by x, then y, then z (ids must be valid). */
    static int compareIds(String a, String b) {
        String[] p = a.split(","), q = b.split(",");
        for (int i = 0; i < 3; i++) {
            int c = Long.compare(Long.parseLong(p[i]), Long.parseLong(q[i]));
            if (c != 0) return c;
        }
        return 0;
    }
}
