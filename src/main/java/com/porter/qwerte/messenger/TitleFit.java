package com.porter.qwerte.messenger;

/**
 * What the window's title says when there is little room: the whole "Messenger - Global", else only "Global" (the
 * prefix goes first), else the name cut with "..." at the end. The unread total is not part of the text: it is drawn as
 * a small badge at the right end of the title area, and this says whether there is room for it. Pure.
 */
public final class TitleFit {
    public static final String PREFIX = "Messenger - ";
    public static final String ELLIPSIS = "...";
    /** The title keeps at least this much room for its text before the badge is dropped. */
    public static final int MIN_TEXT_W = 14;
    /** Space between the text and the badge. */
    public static final int BADGE_GAP = 3;

    public interface Measure {
        int width(String text);
    }

    /** @param text the text to draw (may be empty); @param badge whether the badge fits */
    public record Result(String text, boolean badge) {
    }

    private TitleFit() {
    }

    /**
     * @param prefix  what is dropped first ("Messenger - "), or empty
     * @param subject the part that matters: the conversation, "Global", "Console", or a status text; empty for none
     * @param badgeW  the width of the badge, 0 for none
     * @param areaW   the room for the text and the badge
     */
    public static Result fit(Measure m, String prefix, String subject, int badgeW, int areaW) {
        boolean badge = badgeW > 0;
        int avail = badge ? areaW - badgeW - BADGE_GAP : areaW;
        if (badge && avail < MIN_TEXT_W) {
            badge = false;
            avail = areaW;
        }
        if (avail <= 0) return new Result("", badge);
        String full = prefix + subject;
        if (subject.isEmpty()) full = prefix.endsWith(" - ") ? prefix.substring(0, prefix.length() - 3) : prefix;
        if (m.width(full) <= avail) return new Result(full, badge);
        if (!subject.isEmpty() && !prefix.isEmpty() && m.width(subject) <= avail) return new Result(subject, badge);
        return new Result(ellipsize(m, subject.isEmpty() ? full : subject, avail), badge);
    }

    /** The longest start of {@code s} that fits with "..." after it (just "..." or nothing when even that does not fit). */
    public static String ellipsize(Measure m, String s, int avail) {
        if (m.width(s) <= avail) return s;
        if (m.width(ELLIPSIS) > avail) return "";
        int lo = 0; // the longest cut that fits is between lo and hi
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(cut(s, mid) + ELLIPSIS) <= avail) lo = mid;
            else hi = mid - 1;
        }
        return cut(s, lo) + ELLIPSIS;
    }

    /** The first {@code n} chars, never ending in the middle of a surrogate pair. */
    private static String cut(String s, int n) {
        if (n > 0 && n < s.length() && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }
}
