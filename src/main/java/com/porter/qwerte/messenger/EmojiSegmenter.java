package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a string into plain text and emoji. An emoji here is a whole sequence: a base with its variation selector, skin
 * tone and keycap mark, joined with others by zero width joiners (family, profession, heart couples), a flag (two regional
 * indicators or a tag sequence). Sequences are never split. The result always joins back to the input.
 * Pure: no Minecraft classes.
 */
public final class EmojiSegmenter {
    /** A run of text or one emoji sequence. */
    public record Segment(String text, boolean emoji) {
    }

    private static final int ZWJ = 0x200D;
    private static final int VS15 = 0xFE0E;
    private static final int VS16 = 0xFE0F;
    private static final int KEYCAP = 0x20E3;

    private EmojiSegmenter() {
    }

    public static List<Segment> segment(String s) {
        List<Segment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int n = s.length();
        int textStart = 0;
        int i = 0;
        while (i < n) {
            int end = sequenceEnd(s, i);
            if (end > i) {
                if (textStart < i) out.add(new Segment(s.substring(textStart, i), false));
                out.add(new Segment(s.substring(i, end), true));
                i = end;
                textStart = i;
            } else {
                i += Character.charCount(s.codePointAt(i));
            }
        }
        if (textStart < n) out.add(new Segment(s.substring(textStart), false));
        return out;
    }

    /** How many emoji sequences the string has. */
    public static int count(String s) {
        int n = 0;
        for (Segment seg : segment(s)) {
            if (seg.emoji()) n++;
        }
        return n;
    }

    /** The end of the emoji sequence that starts at {@code i}, or {@code i} if none starts there. */
    private static int sequenceEnd(String s, int i) {
        int n = s.length();
        int cp = s.codePointAt(i);
        int at = i + Character.charCount(cp);

        if (isRegional(cp)) {
            if (at < n && isRegional(s.codePointAt(at))) return at + Character.charCount(s.codePointAt(at));
            return i;
        }
        if (isKeycapBase(cp)) {
            int p = at;
            if (p < n && s.codePointAt(p) == VS16) p++;
            if (p < n && s.codePointAt(p) == KEYCAP) return p + 1;
            return i;
        }
        int end = element(s, i, false);
        if (end <= i) return i;
        // Joined elements
        while (end < n && s.codePointAt(end) == ZWJ) {
            int next = element(s, end + 1, true);
            if (next <= end + 1) break;
            end = next;
        }
        return end;
    }

    /**
     * One emoji base with its trailing selector, skin tone, keycap mark and tags. Returns the end, or {@code i} if
     * there is no base at {@code i}. After a joiner ({@code joined}) text-default characters count without a selector.
     */
    private static int element(String s, int i, boolean joined) {
        int n = s.length();
        if (i >= n) return i;
        int cp = s.codePointAt(i);
        int at = i + Character.charCount(cp);
        boolean vs16 = at < n && s.codePointAt(at) == VS16;
        boolean vs15 = at < n && s.codePointAt(at) == VS15;
        if (vs15) return i; // the sender asked for the text look
        boolean base = isPresentation(cp) || ((vs16 || joined) && isTextDefault(cp)) || isRegional(cp);
        if (!base) return i;
        int end = at;
        if (vs16) end++;
        // Skin tone
        if (end < n && isSkinTone(s.codePointAt(end))) end += Character.charCount(s.codePointAt(end));
        // Tag sequence (subdivision flags)
        if (cp == 0x1F3F4) {
            while (end < n && isTag(s.codePointAt(end))) end += Character.charCount(s.codePointAt(end));
        }
        return end;
    }

    public static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isKeycapBase(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }

    public static boolean isSkinTone(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static boolean isTag(int cp) {
        return cp >= 0xE0020 && cp <= 0xE007F;
    }

    /** Characters that show as a picture without a variation selector. */
    static boolean isPresentation(int cp) {
        if (cp >= 0x1F300 && cp <= 0x1FAFF) return !isRegional(cp);
        if (cp >= 0x1F004 && cp < 0x1F300) return cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E || (cp >= 0x1F191 && cp <= 0x1F19A)
            || cp == 0x1F201 || cp == 0x1F21A || cp == 0x1F22F || (cp >= 0x1F232 && cp <= 0x1F23A) || cp == 0x1F250 || cp == 0x1F251;
        return inAny(cp, 0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653,
            0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4,
            0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728,
            0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
            0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55);
    }

    /** Characters that are emoji only with a variation selector (or joined to another emoji). */
    public static boolean isTextDefault(int cp) {
        return inAny(cp, 0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139, 0x2194, 0x2199,
            0x21A9, 0x21AA, 0x2328, 0x2328, 0x23CF, 0x23CF, 0x23ED, 0x23EF, 0x23F1, 0x23F2, 0x23F8, 0x23FA, 0x24C2, 0x24C2,
            0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FC, 0x2600, 0x27BF, 0x2934, 0x2935, 0x2B05, 0x2B07,
            0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299);
    }

    private static boolean inAny(int cp, int... ranges) {
        for (int k = 0; k + 1 < ranges.length; k += 2) {
            if (cp >= ranges[k] && cp <= ranges[k + 1]) return true;
        }
        return false;
    }

    /**
     * The picture key of an emoji sequence: its code points in lowercase hex joined with "-", without variation
     * selectors (the same key for a heart with and without them).
     */
    public static String key(String emoji) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < emoji.length()) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == VS16 || cp == VS15) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(Integer.toHexString(cp));
        }
        return sb.toString();
    }

    /**
     * The characters of a key ("2764-200d-1f525") with a variation selector after each text-default one, as an emoji
     * keyboard would type it.
     */
    public static String fromKey(String key) {
        StringBuilder sb = new StringBuilder();
        String[] parts = key.split("-");
        for (int k = 0; k < parts.length; k++) {
            int cp;
            try {
                cp = Integer.parseInt(parts[k], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!Character.isValidCodePoint(cp)) continue;
            sb.appendCodePoint(cp);
            boolean selectorFollows = k + 1 < parts.length && parts[k + 1].equalsIgnoreCase("fe0f");
            if (cp != VS16 && !selectorFollows && isTextDefault(cp) && !isPresentation(cp)) sb.appendCodePoint(VS16);
        }
        return sb.toString();
    }

    /** True for the invisible helper characters that are left over when a sequence is cut or cleaned. */
    public static boolean isInvisible(int cp) {
        return cp == ZWJ || cp == VS15 || cp == VS16 || cp == KEYCAP || isTag(cp);
    }
}
